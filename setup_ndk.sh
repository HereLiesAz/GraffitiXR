#!/usr/bin/env bash
# A more robust script to set up the Android NDK and CMake development environment.
# This script attempts to locate sdkmanager and handle potential path issues.

set -e # Exit immediately if a command exits with a non-zero status.
set -u # Treat unset variables as an error.
set -o pipefail # Causes a pipeline to return the exit status of the last command in the pipe that returned a non-zero return value.

echo "--- Android NDK and CMake Setup Script ---"

# 1. Validate ANDROID_HOME
# ========================
if [ -z "${ANDROID_HOME-}" ]; then # The trailing dash handles unbound variable case with set -u
  echo "Error: ANDROID_HOME environment variable is not set." >&2
  echo "Please set it to your Android SDK root directory." >&2
  exit 1
fi

echo "ANDROID_HOME is set to: $ANDROID_HOME"
if [ ! -d "$ANDROID_HOME" ]; then
    echo "Error: ANDROID_HOME directory does not exist at '$ANDROID_HOME'" >&2
    exit 1
fi

# 2. Locate sdkmanager
# ====================
SDKMANAGER=""
# The most common modern path
SDKMANAGER_PATH_1="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# An older, but still common path
SDKMANAGER_PATH_2="$ANDROID_HOME/tools/bin/sdkmanager"

echo "Searching for sdkmanager..."
if [ -f "$SDKMANAGER_PATH_1" ]; then
  SDKMANAGER="$SDKMANAGER_PATH_1"
  echo "Found sdkmanager at: $SDKMANAGER"
elif [ -f "$SDKMANAGER_PATH_2" ]; then
  SDKMANAGER="$SDKMANAGER_PATH_2"
  echo "Found sdkmanager at older path: $SDKMANAGER"
else
  echo "Error: sdkmanager not found in the expected locations:" >&2
  echo "  1. $SDKMANAGER_PATH_1" >&2
  echo "  2. $SDKMANAGER_PATH_2" >&2
  echo "Please ensure the Android SDK command-line tools are installed." >&2
  echo "You can usually install them from within Android Studio (SDK Manager -> SDK Tools -> Android SDK Command-line Tools)." >&2
  exit 1
fi

# Make sure the manager is executable
if [ ! -x "$SDKMANAGER" ]; then
    echo "Error: sdkmanager is not executable. Attempting to set permissions..." >&2
    chmod +x "$SDKMANAGER"
    if [ ! -x "$SDKMANAGER" ]; then
        echo "Error: Failed to make sdkmanager executable. Please check file permissions." >&2
        exit 1
    fi
    echo "Made sdkmanager executable."
fi

# 3. Install NDK and CMake
# ========================
NDK_VERSION="25.2.9519653"
CMAKE_VERSION="3.22.1"

echo "Installing NDK (version $NDK_VERSION) and CMake (version $CMAKE_VERSION)..."

# The 'yes' command automatically pipes 'y' to the sdkmanager license prompt.
# The --sdk_root flag can sometimes help if sdkmanager has trouble locating the root.
if yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION"; then
    echo "--- NDK and CMake installation completed successfully. ---"
    echo "The environment is now set up for Android native development."
else
    echo "Error: The sdkmanager command failed." >&2
    echo "Please review the output above for specific error messages from the tool." >&2
    exit 1
fi

exit 0
