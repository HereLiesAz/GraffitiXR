#!/bin/bash

# Configuration
REPO_URL="https://github.com/hereliesaz/graffitixr.git"
BRANCH="dependencies"
TARGET_DIR="app/libs"

echo "=============================================="
echo "  GraffitiXR Dependency Fetcher"
echo "  Source: $BRANCH branch"
echo "  Target: $TARGET_DIR (Git Ignored)"
echo "=============================================="

# Ensure target directory exists
mkdir -p "$TARGET_DIR"

# Check dependencies
if ! command -v git &> /dev/null; then
    echo "Error: git is not installed."
    exit 1
fi

# Create a temporary directory for the raw branch pull
TEMP_DIR=$(mktemp -d)
echo "Fetching dependencies branch to temp storage..."

# Shallow clone to save bandwidth
git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$TEMP_DIR"

if [ $? -eq 0 ]; then
    echo "Download successful. Syncing SDKs..."

    # Sync ONLY the specific heavy libraries that are listed in .gitignore
    # This prevents random branch files (like READMEs) from cluttering app/libs
    
    # 1. OpenCV
    if [ -d "$TEMP_DIR/opencv" ]; then
        echo "Updating OpenCV..."
        rm -rf "$TARGET_DIR/opencv"
        mkdir -p "$TARGET_DIR/opencv/sdk"
        cp -r "$TEMP_DIR/opencv/"* "$TARGET_DIR/opencv/sdk/"

        # PATCH: Fix deprecated Proguard file
        OPENCV_BUILD_GRADLE="$TARGET_DIR/opencv/sdk/build.gradle"
        if [ -f "$OPENCV_BUILD_GRADLE" ]; then
            echo "Patching OpenCV build.gradle..."

            # 1. Use proguard-android-optimize.txt
            if [[ "$OSTYPE" == "darwin"* ]]; then
                sed -i '' "s/proguard-android.txt/proguard-android-optimize.txt/g" "$OPENCV_BUILD_GRADLE"
            else
                sed -i "s/proguard-android.txt/proguard-android-optimize.txt/g" "$OPENCV_BUILD_GRADLE"
            fi

            # 2. Add JVM Target 17 for Kotlin
            # We append it to the end of the android block or just append to file if simple
            # But the file structure is complex. Let's just append it to the android block if we can find it.
            # Simpler: Just append the kotlinOptions block inside the android block using a known anchor.

            # Check if kotlinOptions is missing
            if ! grep -q "jvmTarget" "$OPENCV_BUILD_GRADLE"; then
                echo "Adding Kotlin JVM Target 17..."
                 if [[ "$OSTYPE" == "darwin"* ]]; then
                    sed -i '' '/compileOptions {/i\
    kotlinOptions {\
        jvmTarget = "17"\
    }\
' "$OPENCV_BUILD_GRADLE"
                else
                    sed -i '/compileOptions {/i\
    kotlinOptions {\
        jvmTarget = "17"\
    }\
' "$OPENCV_BUILD_GRADLE"
                fi
            fi
        fi
    fi

    # 2. GLM
    if [ -d "$TEMP_DIR/glm" ]; then
        echo "Updating GLM..."
        rm -rf "$TARGET_DIR/glm"
        cp -r "$TEMP_DIR/glm" "$TARGET_DIR/"
    fi
    
    # 3. LiteRT - Removed in favor of remote dependency

    # Cleanup temp
    rm -rf "$TEMP_DIR"
    
    echo "Success! Libraries installed in $TARGET_DIR."
    echo "Note: These folders are ignored by .gitignore and will not be committed."
else
    echo "Failed to fetch dependencies branch."
    rm -rf "$TEMP_DIR"
    exit 1
fi
