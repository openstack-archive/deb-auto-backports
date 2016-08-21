#!/bin/sh

set -e
set -x

### SOME GLOBAL VARS ###
HERE=$(pwd)
BUILD_ROOT=$(pwd)/bpo-src

LAST_GIT_COMMIT=$(git log | head -n 1 | awk '{print $2}')

### INCLUDES ###
. ${HERE}/bb-parse-params

if ! [ -r /etc/pkgos/pkgos.conf ] ; then
    echo "Could not read /etc/pkgos/pkgos.conf"
    exit 1
else
    . /etc/pkgos/pkgos.conf
fi

if ! [ -r /etc/nodepool/provider ] ; then
    echo "Could not read /etc/nodepool/provider"
    exit 1
else
    . /etc/nodepool/provider
    NODEPOOL_MIRROR_HOST=${NODEPOOL_MIRROR_HOST:-mirror.$NODEPOOL_REGION.$NODEPOOL_CLOUD.openstack.org}
    NODEPOOL_MIRROR_HOST=$(echo $NODEPOOL_MIRROR_HOST|tr '[:upper:]' '[:lower:]')
fi

# Get info from the packages.debian.org html file and rmadison
pkgos_bb_get_package_info () {
    local PKG_INFO_FILE RMADURL

    # Get info from packages.debian.org
    PKG_INFO_FILE=`mktemp -t pkg_info_file.XXXXXX`
    wget --no-check-certificate -O ${PKG_INFO_FILE} http://packages.debian.org/source/${SRC_DISTRO}/${PKG_NAME}
    if [ `lsb_release -i -s` = "Ubuntu" ] ; then
        RMADURL="--url=http://qa.debian.org/madison.php"
    else
        RMADURL=""
    fi
    DEB_VERSION=`rmadison $RMADURL -a source --suite=${SRC_DISTRO} ${PKG_NAME} | awk '{print $3}'`
    UPSTREAM_VERSION=`echo ${DEB_VERSION} | sed 's/-[^-]*$//' | cut -d":" -f2`
    DSC_URL=`cat ${PKG_INFO_FILE} | grep dsc | cut -d'"' -f2`
    rm ${PKG_INFO_FILE}
}

# param: $PKG_NAME = source package name
#        $1 = line as returned by madison-lite --source-and-binary -a amd64 ${PKG_NAME}
# return: URL = path in the repo
compute_download_url () {
    local FIRST_LETTER BINARY_NAME VERSION_NUM ARCHI

    echo "Source package name: xxx${PKG_NAME}xxx"
    FIRST_LETTER=$(echo ${PKG_NAME} | cut -c1-1)
    echo "First letter: xxx${FIRST_LETTER}xxx"
    BINARY_NAME=$(echo ${1} | awk '{print $1}')
    VERSION_NUM=$(echo ${1} | awk '{print $3}')
    ARCHI=$(echo ${1} | awk '{print $7}')

    BINARY_PACKAGE_FILENAME=${BINARY_NAME}_${VERSION_NUM}_${ARCHI}.deb
    URL=/debian/pool/main/${FIRST_LETTER}/${PKG_NAME}/${BINARY_PACKAGE_FILENAME}
}

prep_upload_folder () {
    TARGET_FTP_FOLDER=${HERE}/uploads/${LAST_GIT_COMMIT}
    mkdir -p ${TARGET_FTP_FOLDER}
}

# Generate a .changes file out of a folder containing a source + binaries package
# params: $1 = folder where the files are located
make_changes_file () {
    local MCF_EXTRACTED_FOLDER MCF_CHANGELOG_DATE MCF_SOURCE_PKGNAME MCF_BINARY_LIST MCF_DEBIAN_VERSION MCF_DEBIAN_VERSION_NO_EPOCH MCF_NUM_LINES_PARSECHANGE MCF_START_CHANGES_LINE MCF_MAINTAINER MCF_binary_package MCF_SHORT_DESC MCF_FILE_LIST MCF_OLD_DIR
    MCF_OLD_DIR=$(pwd)
    cd ${1}
    # Extract the package to get metadata with dpkg-parsechangelog and friends
    dpkg-source -x *.dsc
    MCF_EXTRACTED_FOLDER=$(find . -maxdepth 1 -type d | tail -n1 | cut -d/ -f2)
    cd ${MCF_EXTRACTED_FOLDER}
    MCF_CHANGELOG_DATE=$(dpkg-parsechangelog -SDate)
    MCF_SOURCE_PKGNAME=$(dpkg-parsechangelog -SSource)
    MCF_BINARY_LIST=$(cat debian/control | grep -E '^Package:' | sed -e 's/^Package: //' | tr '\n' ' ')
    # This trims last char (ie: a space)
    MCF_BINARY_LIST=${MCF_BINARY_LIST%?}
    MCF_DEBIAN_VERSION=$(dpkg-parsechangelog -SVersion)
    MCF_DEBIAN_VERSION_NO_EPOCH=$(echo ${MCF_DEBIAN_VERSION} | sed -e 's/^[[:digit:]]*://')
    # Calculate the num of lines of the "Changes:" part
    MCF_NUM_LINES_PARSECHANGE=$(dpkg-parsechangelog | wc -l)
    MCF_START_CHANGES_LINE=$(dpkg-parsechangelog | grep -n "Changes:" | cut -d: -f1)
    MCF_CHANGE_FIELD_NUM_LINES=$((${MCF_NUM_LINES_PARSECHANGE} - ${MCF_START_CHANGES_LINE} + 1))
    MCF_MAINTAINER=$(dpkg-parsechangelog -SMaintainer)

    echo "Format: 1.8
Date: ${MCF_CHANGELOG_DATE}
Source: ${MCF_SOURCE_PKGNAME}
Binary: ${MCF_BINARY_LIST}
Architecture: source all
Version: ${MCF_DEBIAN_VERSION}
Distribution: jessie-newton-backports
Urgency: medium
Maintainer: ${MCF_MAINTAINER}
Changed-By: Thomas Goirand <zigo@debian.org>
Description:"
    # Get all binary package descriptions
    for MCF_binary_package in ${MCF_BINARY_LIST} ; do
        MCF_SHORT_DESC=$(dpkg-deb -I ../${MCF_binary_package}_${MCF_DEBIAN_VERSION_NO_EPOCH}_*.deb | grep -E '^ Description:' | sed -e 's/^ Description: //')
        echo " ${MCF_binary_package} - ${MCF_SHORT_DESC}"
    done
    dpkg-parsechangelog | tail -n ${MCF_CHANGE_FIELD_NUM_LINES}
    cd ..
    rm -rf ${MCF_EXTRACTED_FOLDER}

    # Generate all the sums
    MCF_FILE_LIST=$(find . -maxdepth 1 -type f | grep -v $0 | cut -d/ -f2 | tr '\n' ' ')
    MCF_FILE_LIST=${MCF_FILE_LIST%?}
    echo "Checksums-Sha1:"
    for MCF_file in ${MCF_FILE_LIST} ; do
        SHA1SUM=$(sha1sum ${MCF_file} | cut -d' ' -f1)
        FILESIZE=$(ls -l ${MCF_file} | awk '{print $5}')
        echo " ${SHA1SUM} ${FILESIZE} ${MCF_file}"
    done
    echo "Checksums-Sha256:"
    for MCF_file in ${MCF_FILE_LIST} ; do
        SHA256SUM=$(sha256sum ${MCF_file} | cut -d' ' -f1)
        FILESIZE=$(ls -l ${MCF_file} | awk '{print $5}')
        echo " ${SHA256SUM} ${FILESIZE} ${MCF_file}"
    done
    echo "Files:"
    for MCF_file in ${MCF_FILE_LIST} ; do
        MD5SUM=$(md5sum ${MCF_file} | cut -d' ' -f1)
        FILESIZE=$(ls -l ${MCF_file} | awk '{print $5}')
        echo " ${MD5SUM} ${FILESIZE} ${MCF_file}"
    done
    cd ${MCF_OLD_DIR}
}

# Download a package (binary + source), generate a .changes, and push to the uploads folder
# Params: ${PKG_NAME} = source package name
download_the_backport () {
    local DTB_TMPDIR DTB_CURDIR DTB_TMPFILE

    DTB_CURDIR=$(pwd)
    DTB_TMPFILE=$(mktemp -t download-list-of-binaries.XXXXXX)
    DTB_TMPDIR=$(mktemp -d -t download-folder-of-binaries.XXXXXX)

    madison-lite --source-and-binary -a amd64 --mirror ${HERE}/etc/pkgos/fake-jessie-backports-mirror ${PKG_NAME} >${DTB_TMPFILE}
    cd ${DTB_TMPDIR}
    while read DTB_PKG_SOURCE_LINE ; do
        compute_download_url "${DTB_PKG_SOURCE_LINE}"
        wget http://httpredir.debian.org/${URL}
    done < ${DTB_TMPFILE}
    rm ${DTB_TMPFILE}
    cd ${TARGET_FTP_FOLDER}
    dget -d -u ${DSC_URL}
    make_changes_file ${DTB_TMPDIR}
    cd ${DTB_CURDIR}

    prep_upload_folder
    cp ${DTB_TMPDIR}/* ${TARGET_FTP_FOLDER}/${BINARY_PACKAGE_FILENAME}
    rm -rf ${DTB_TMPFILE} ${DTB_TMPDIR}
}


pgkos_build_the_bpo () {
    # Prepare build folder and go in it
    MY_CWD=`pwd`
    rm -rf ${BUILD_ROOT}/$PKG_NAME
    mkdir -p ${BUILD_ROOT}/$PKG_NAME
    cd ${BUILD_ROOT}/$PKG_NAME

    # Download the .dsc and extract it
    #DSC_URL=$(echo ${DSC_URL} | sed "s#http://http.debian.net/debian#${CLOSEST_DEBIAN_MIRROR}#")
    dget -d -u ${DSC_URL}
    PKG_SRC_NAME=`ls *.dsc | cut -d_ -f1`
    PKG_NAME_FIRST_CHAR=`echo ${PKG_SRC_NAME} | awk '{print substr($0,1,1)}'`

    # Guess source package name using an ls of the downloaded .dsc file
    DSC_FILE=`ls *.dsc`
    DSC_FILE=`basename $DSC_FILE`
    SOURCE_NAME=`echo $DSC_FILE | cut -d_ -f1`

    # Rename the build folder if the source package name is different from binary
    if ! [ "${PKG_NAME}" = "${SOURCE_NAME}" ] ; then
        cd ..
        rm -rf $SOURCE_NAME
        mv $PKG_NAME $SOURCE_NAME
        cd $SOURCE_NAME
    fi

    # Extract the source and make it a backport
    dpkg-source -x *.dsc
    cd ${SOURCE_NAME}-${UPSTREAM_VERSION}
    rm -f debian/changelog.dch
    CURDIR=$(pwd)
    BUILDCURDIR=$CURDIR
    if grep -q native debian/source/format ; then
        PACKAGE_IS_NATIVE="yes"
    else
        PACKAGE_IS_NATIVE="no"
    fi
    # We don't need to dch --bpo if we're only rebuilding from official
    # jessie-backports.
    if ! [ "${SRC_DISTRO}" = "jessie-backports" ] ; then
        dch --newversion ${DEB_VERSION}~${BPO_POSTFIX} -b --allow-lower-version --distribution ${TARGET_DISTRO}-backports -m  "Rebuilt for ${TARGET_DISTRO}."
    fi

    if [ ${PACKAGE_IS_NATIVE} = "yes" ] ; then
        cd ../*bpo8+1
        BUILDCURDIR=$(pwd)
    fi

    ssh -o "StrictHostKeyChecking no" localhost "cd ${BUILDCURDIR} ; sbuild --force-orig-source"

    # Check the output files with ls
    ls -lah ..

    # Copy in the FTP repo
    cd ..
    rm *.build
    prep_upload_folder
    if [ ${PACKAGE_IS_NATIVE} = "yes" ] ; then
        cp *~bpo*+1.dsc *~bpo*+1.tar* *~bpo*+1*.deb *~bpo*+1*.changes ${TARGET_FTP_FOLDER}
    else
        cp *bpo* ${TARGET_FTP_FOLDER}
        # We need || true in the case of a native package
        cp *.orig.tar.* ${TARGET_FTP_FOLDER} || true
    fi
}

bb_parse_params $@
pkgos_bb_get_package_info
if [ "${DOWNLOAD_FROM_JESSIE_BACKPORTS}" = "yes" ] ; then
    download_the_backport
else
    pgkos_build_the_bpo
fi
cd ${HERE}
