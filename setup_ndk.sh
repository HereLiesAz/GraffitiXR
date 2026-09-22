#!/usr/bin/env bash
# A more robust script to set up the Android NDK and CMake development environment.

set -e
set -u
set -o pipefail

echo "--- Android NDK and CMake Setup Script ---"

if [ -z "${ANDROID_HOME-}" ]; then
  echo "Error: ANDROID_HOME environment variable is not set." >&2
  exit 1
fi

echo "ANDROID_HOME is set to: $ANDROID_HOME"
if [ ! -d "$ANDROID_HOME" ]; then
    echo "Error: ANDROID_HOME directory does not exist at '$ANDROID_HOME'" >&2
    exit 1
fi

SDKMANAGER=""
SDKMANAGER_PATH_1="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
SDKMANAGER_PATH_2="$ANDROID_HOME/tools/bin/sdkmanager"

if [ -f "$SDKMANAGER_PATH_1" ]; then
  SDKMANAGER="$SDKMANAGER_PATH_1"
elif [ -f "$SDKMANAGER_PATH_2" ]; then
  SDKMANAGER="$SDKMANAGER_PATH_2"
else
  echo "Error: sdkmanager not found." >&2
  exit 1
fi

if [ ! -x "$SDKMANAGER" ]; then
    chmod +x "$SDKMANAGER"
fi

# Default version, but can be overridden or ignored if user has something close
NDK_VERSION="25.2.9519653"
CMAKE_VERSION="3.22.1"

echo "Checking for NDK..."
# `ls` on an existing-but-empty directory still exits 0, so check for actual contents rather
# than mere existence of the directory.
if [ -n "$(ls -A "$ANDROID_HOME/ndk" 2>/dev/null)" ]; then
    echo "NDK detected. Skipping forced install to prevent version conflicts."
    echo "Ensure your local.properties or build.gradle points to a valid NDK."
else
    echo "Installing NDK (version $NDK_VERSION)..."
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install "ndk;$NDK_VERSION"
fi

# CMake is an independent concern from the NDK: install it regardless of whether an NDK was
# already present, since CMakeLists.txt requires it either way.
echo "Installing CMake (version $CMAKE_VERSION)..."
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install "cmake;$CMAKE_VERSION"

exit 0