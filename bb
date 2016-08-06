#!/bin/sh

set -e
set -x

if ! [ -r /etc/pkgos/pkgos.conf ] ; then
    echo "Could not read /etc/pkgos/pkgos.conf"
    exit 1
else
    . /etc/pkgos/pkgos.conf
fi

# Manage parameters of this script
usage () {
    echo "Usage: $0 [-d <DISTRO>] <source-package>"
    echo " -d <DISTRO>: Define from which distro to backport"
    exit 1
}

UPLOAD=no
SRC_DISTRO=sid
for i in $@ ; do
	case ${1} in
	"-d")
		if [ -z "${2}" ] || [ -z "${3}" ] ; then usage ; fi
		SRC_DISTRO=${2}
		shift
		shift
	;;
	*)
	;;
	esac
done

if [ -z "${1}" ] ; then usage ; fi

PKG_NAME=${1}

HERE=$(pwd)
BUILD_ROOT=$(pwd)/bpo-src


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
dch --newversion ${DEB_VERSION}~${BPO_POSTFIX} -b --allow-lower-version --distribution ${TARGET_DISTRO}-backports -m  "Rebuilt for ${TARGET_DISTRO}."

# Build the package
CURDIR=$(pwd)
ssh -o "StrictHostKeyChecking no" localhost "cd ${CURDIR} ; sbuild"
#sbuild

# Check the output files with ls
ls -lah ..

# Copy in the FTP repo
cd ..
rm *.build
TARGET_FTP_FOLDER=${HERE}/uploads
mkdir -p ${TARGET_FTP_FOLDER}
cp *bpo* ${TARGET_FTP_FOLDER}
# We need || true in the case of a native package
cp *.orig.tar.* ${TARGET_FTP_FOLDER} || true
