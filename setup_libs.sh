#!/bin/bash
set -e

# Paths
OPENCV_DEST="app/libs/opencv"
GLM_DEST="app/src/main/cpp/include/glm"
LITERT_DEST="app/libs/litert"
MLKIT_DEST="app/libs/mlkit"

echo "Fetching libraries from dependencies branch..."
# Fetch the dependencies branch (force update as history might be rewritten)
git fetch origin +dependencies:dependencies

# Create temp dir
rm -rf temp_libs
mkdir -p temp_libs

# Extract files from the dependencies branch without switching branches
echo "Extracting files..."
git archive dependencies | tar -x -C temp_libs

# Setup OpenCV
if [ ! -d "$OPENCV_DEST" ]; then
    echo "Setting up OpenCV 4.13.0..."

    # Check for split files and rejoin if necessary
    if ls temp_libs/opencv-4.13.0-android-sdk.zip.part* 1> /dev/null 2>&1; then
        echo "Rejoining split OpenCV files..."
        cat temp_libs/opencv-4.13.0-android-sdk.zip.part* > temp_libs/opencv-4.13.0-android-sdk.zip
    fi

    if [ -f "temp_libs/opencv-4.13.0-android-sdk.zip" ]; then
        unzip -q temp_libs/opencv-4.13.0-android-sdk.zip -d temp_libs/
        # Move the inner SDK folder to destination
        mkdir -p app/libs
        if [ -d "temp_libs/OpenCV-android-sdk" ]; then
             mv temp_libs/OpenCV-android-sdk "$OPENCV_DEST"
        else
             echo "Error: OpenCV SDK folder not found in zip"
        fi
    else
        echo "Error: OpenCV zip not found (checked for split parts too)"
    fi
else
    echo "OpenCV already present."
fi

# Setup GLM
if [ ! -d "$GLM_DEST" ]; then
    echo "Setting up GLM 1.0.3..."
    if [ -f "temp_libs/glm-1.0.3.zip" ]; then
        unzip -q temp_libs/glm-1.0.3.zip -d temp_libs/
        mkdir -p app/src/main/cpp/include
        # GLM zip structure varies. Check common patterns.
        if [ -d "temp_libs/glm" ]; then
             mv temp_libs/glm app/src/main/cpp/include/
        elif [ -d "temp_libs/glm-1.0.3/glm" ]; then
             mv temp_libs/glm-1.0.3/glm app/src/main/cpp/include/
        elif [ -d "temp_libs/glm-1.0.3" ]; then
             # Assuming root is glm
             mv temp_libs/glm-1.0.3 app/src/main/cpp/include/glm
        else
             echo "Error: GLM folder structure not recognized"
        fi
    else
        echo "Error: GLM zip not found"
    fi
else
    echo "GLM already present."
fi

# Setup LiteRT
if [ ! -d "$LITERT_DEST" ]; then
    echo "Setting up LiteRT 2.1.0..."
    if [ -f "temp_libs/litert-2.1.0.aar" ]; then
        mkdir -p "$LITERT_DEST"
        cp temp_libs/litert-2.1.0.aar "$LITERT_DEST/"
    else
        echo "Error: LiteRT AAR not found"
    fi
else
    echo "LiteRT already present."
fi

# Setup MLKit
if [ ! -d "$MLKIT_DEST" ]; then
    echo "Setting up MLKit..."
    if [ -f "temp_libs/mlkit-subject-segmentation.aar" ]; then
        mkdir -p "$MLKIT_DEST"
        cp temp_libs/mlkit-subject-segmentation.aar "$MLKIT_DEST/"
    else
        echo "Error: MLKit AAR not found"
    fi
else
    echo "MLKit already present."
fi

# Patch OpenCV build.gradle for AGP 9.0 compatibility (proguard-android.txt is deprecated)
if [ -f "$OPENCV_DEST/sdk/build.gradle" ]; then
    if grep -q "proguard-android.txt" "$OPENCV_DEST/sdk/build.gradle"; then
        echo "Patching OpenCV build.gradle for AGP 9.0 compatibility..."
        # Portable sed in-place
        if [[ "$OSTYPE" == "darwin"* ]]; then
             sed -i '' 's/proguard-android.txt/proguard-android-optimize.txt/g' "$OPENCV_DEST/sdk/build.gradle"
        else
             sed -i 's/proguard-android.txt/proguard-android-optimize.txt/g' "$OPENCV_DEST/sdk/build.gradle"
        fi
    fi
fi

# Cleanup
rm -rf temp_libs

# Ensure they are ignored (just in case)
echo "Libraries setup complete. Ensure .gitignore includes these paths."
