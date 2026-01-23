#!/bin/bash
set -e

echo "Setting up dependencies in root /libs/..."

# Ensure libs exists
mkdir -p libs

# Fetch dependencies branch
git fetch origin dependencies

# --- OpenCV ---
echo "Restoring OpenCV..."
rm -rf opencv libs/opencv
git checkout origin/dependencies -- opencv
mv opencv libs/opencv

# PATCH: Fix deprecated Proguard file and JVM target in OpenCV
OPENCV_BUILD_GRADLE="libs/opencv/build.gradle"
if [ -f "$OPENCV_BUILD_GRADLE" ]; then
    echo "Patching OpenCV build.gradle..."

    # Use a robust sed command to replace the Proguard file reference
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "s/proguard-android.txt/proguard-android-optimize.txt/g" "$OPENCV_BUILD_GRADLE"
    else
        sed -i "s/proguard-android.txt/proguard-android-optimize.txt/g" "$OPENCV_BUILD_GRADLE"
    fi

    # Ensure Kotlin JVM Target 17
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
    echo "Patch applied successfully to $OPENCV_BUILD_GRADLE"
fi

# --- GLM ---
echo "Restoring GLM..."
rm -rf libs/glm
mkdir -p libs/glm
curl -L -o glm.zip https://github.com/g-truc/glm/archive/refs/tags/1.0.1.zip
unzip -q glm.zip
mv glm-1.0.1/glm libs/glm/glm
rm -rf glm-1.0.1 glm.zip

# --- AARs (LiteRT & MLKit) ---
echo "Restoring LiteRT and MLKit AARs..."
git checkout origin/dependencies -- litert-2.1.0.aar mlkit-subject-segmentation.aar
mv litert-2.1.0.aar libs/
mv mlkit-subject-segmentation.aar libs/

echo "Dependencies setup complete. Please Sync Gradle in Android Studio."
