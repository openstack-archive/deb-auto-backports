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

download_the_backport () {
    prep_upload_folder
    TMPFILE=$(mktemp -t download-list-of-binaries.XXXXXX)
    madison-lite --source-and-binary -a amd64 --mirror ${HERE}/etc/pkgos/fake-jessie-backports-mirror ${PKG_NAME} >${TMPFILE}
    while read DTB_PKG_SOURCE_LINE ; do
        compute_download_url "${DTB_PKG_SOURCE_LINE}"
        wget http://httpredir.debian.org/${URL} -O ${TARGET_FTP_FOLDER}/${BINARY_PACKAGE_FILENAME}
    done < ${TMPFILE}
    rm ${TMPFILE}
    cd ${TARGET_FTP_FOLDER}
    dget ${DSC_URL}
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
    dch --newversion ${DEB_VERSION}~${BPO_POSTFIX} -b --allow-lower-version --distribution ${TARGET_DISTRO}-backports -m  "Rebuilt for ${TARGET_DISTRO}."

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
