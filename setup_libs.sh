#!/bin/bash
set -e

echo "Setting up dependencies..."

# Ensure app/libs exists
mkdir -p app/libs

# Fetch dependencies branch
git fetch origin dependencies

# --- OpenCV ---
echo "Restoring OpenCV..."
# Remove existing to avoid conflicts
rm -rf opencv app/libs/opencv

# Checkout 'opencv' folder from root of dependencies branch
git checkout origin/dependencies -- opencv

# Create expected directory structure app/libs/opencv
mkdir -p app/libs/opencv

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
    
    # 3. LiteRT (if present)
    if [ -d "$TEMP_DIR/litert" ]; then
        echo "Updating LiteRT..."
        rm -rf "$TARGET_DIR/litert"
        cp -r "$TEMP_DIR/litert" "$TARGET_DIR/"
    fi

    # Cleanup temp
    rm -rf "$TEMP_DIR"
    
    echo "Success! Libraries installed in $TARGET_DIR."
    echo "Note: These folders are ignored by .gitignore and will not be committed."
else
    echo "Failed to fetch dependencies branch."
    rm -rf "$TEMP_DIR"
    exit 1
fi

# --- GLM ---
echo "Restoring GLM..."
rm -rf app/libs/glm
mkdir -p app/libs/glm

# Download GLM 1.0.1
echo "Downloading GLM 1.0.1..."
curl -L -o glm.zip https://github.com/g-truc/glm/archive/refs/tags/1.0.1.zip
unzip -q glm.zip

# Move 'glm' folder
mv glm-1.0.1/glm app/libs/glm/glm
rm -rf glm-1.0.1 glm.zip

# --- LiteRT ---
echo "Restoring LiteRT..."
rm -f app/libs/litert-2.1.0.aar
git checkout origin/dependencies -- litert-2.1.0.aar
mv litert-2.1.0.aar app/libs/

# LiteRT NPU Libraries
if git ls-tree origin/dependencies | grep -q "litert_npu_runtime_libraries"; then
    echo "Restoring LiteRT NPU libraries..."
    rm -rf app/libs/litert_npu_runtime_libraries
    git checkout origin/dependencies -- litert_npu_runtime_libraries
    mv litert_npu_runtime_libraries app/libs/
fi

# --- MLKit Subject Segmentation ---
echo "Restoring MLKit Subject Segmentation..."
rm -f app/libs/mlkit-subject-segmentation.aar
git checkout origin/dependencies -- mlkit-subject-segmentation.aar
mv mlkit-subject-segmentation.aar app/libs/

echo "Dependencies setup complete."
