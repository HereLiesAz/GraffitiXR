#!/usr/bin/env bash
# Builds a host (x86-64 Linux) OpenCV 5.0 into $PREFIX (default ~/ocv5/install) so the native host
# tests can run against the OpenCV the Android build links, instead of the distro's OpenCV 4.
#
#   tools/build_opencv5_host.sh
#   OPENCV_DIR=~/ocv5/install/lib/cmake/opencv5 tools/run_native_host_tests.sh
#
# The OpenCV source comes from the opencv-python 5.0.0.93 source distribution on PyPI (its tarball is
# the OpenCV 5.0.0 tree), because GitHub's archive endpoints are not always reachable. Needs cmake,
# ninja and a C++ compiler; takes a while (about 30 minutes on 4 cores).
set -euo pipefail
WORK="${WORK:-$HOME/ocv5}"
PREFIX="${PREFIX:-$WORK/install}"
SDIST_URL="https://files.pythonhosted.org/packages/source/o/opencv-python/opencv_python-5.0.0.93.tar.gz"
mkdir -p "$WORK"
cd "$WORK"
[ -f src.tar.gz ] || curl -sSL -o src.tar.gz "$SDIST_URL"
[ -d opencv_python-5.0.0.93 ] || tar xzf src.tar.gz
cmake -S opencv_python-5.0.0.93/opencv -B build -GNinja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DBUILD_LIST=core,imgproc,features,calib,dnn,flann,geometry,imgcodecs,video,photo,objdetect,ptcloud,stereo \
  -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_opencv_apps=OFF -DBUILD_DOCS=OFF \
  -DBUILD_JAVA=OFF -DBUILD_opencv_python3=OFF -DBUILD_opencv_python2=OFF -DWITH_IPP=OFF -DWITH_OPENCL=OFF \
  -DWITH_FFMPEG=OFF -DWITH_GSTREAMER=OFF -DWITH_GTK=OFF -DBUILD_PROTOBUF=ON -DWITH_PROTOBUF=ON \
  -DWITH_PNG=ON -DWITH_JPEG=ON -DWITH_TIFF=OFF -DWITH_WEBP=OFF -DWITH_OPENEXR=OFF -DWITH_JASPER=OFF -DWITH_OPENJPEG=OFF
cmake --build build -j "$(nproc 2>/dev/null || echo 2)"
cmake --install build
echo "OpenCV 5 installed. Run the host tests with:"
echo "  OPENCV_DIR=$PREFIX/lib/cmake/opencv5 tools/run_native_host_tests.sh"
