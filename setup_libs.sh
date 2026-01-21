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

# Create expected directory structure app/libs/opencv/sdk
mkdir -p app/libs/opencv

# Move checked out 'opencv' folder to 'app/libs/opencv/sdk'
mv opencv app/libs/opencv/sdk

# Patch OpenCV build.gradle
if [ -f "app/libs/opencv/sdk/build.gradle" ]; then
    echo "Patching OpenCV build.gradle..."
     if [[ "$OSTYPE" == "darwin"* ]]; then
             sed -i '' 's/proguard-android.txt/proguard-android-optimize.txt/g' "app/libs/opencv/sdk/build.gradle"
        else
             sed -i 's/proguard-android.txt/proguard-android-optimize.txt/g' "app/libs/opencv/sdk/build.gradle"
        fi

     # Add kotlinOptions { jvmTarget = '17' } before buildTypes to fix JVM target incompatibility
     # We check if it's already there to avoid duplication if run multiple times (though we nuked the folder above, so it's fresh)
     if ! grep -q "jvmTarget = '17'" "app/libs/opencv/sdk/build.gradle"; then
         echo "Adding kotlinOptions to OpenCV build.gradle..."
         awk '/buildTypes \{/ { print "    kotlinOptions {\n        jvmTarget = \04717\047\n    }\n" } { print }' "app/libs/opencv/sdk/build.gradle" > temp_gradle && mv temp_gradle "app/libs/opencv/sdk/build.gradle"
     fi
fi

# --- GLM ---
echo "Restoring GLM..."
rm -rf app/libs/glm
mkdir -p app/libs/glm

# Download GLM 1.0.1
echo "Downloading GLM 1.0.1..."
curl -L -o glm.zip https://github.com/g-truc/glm/archive/refs/tags/1.0.1.zip
unzip -q glm.zip

# Move 'glm' folder from the extracted directory to app/libs/glm
# Structure is glm-1.0.1/glm
mv glm-1.0.1/glm app/libs/glm/glm

# Cleanup GLM temp files
rm -rf glm-1.0.1 glm.zip

# --- LiteRT ---
echo "Restoring LiteRT..."
rm -rf app/libs/litert
mkdir -p app/libs/litert

# Checkout litert aar
git checkout origin/dependencies -- litert-2.1.0.aar

mv litert-2.1.0.aar app/libs/litert/

# Also check for 'litert_npu_runtime_libraries' folder if it exists
if git ls-tree origin/dependencies | grep -q "litert_npu_runtime_libraries"; then
    echo "Restoring LiteRT NPU libraries..."
    rm -rf litert_npu_runtime_libraries
    git checkout origin/dependencies -- litert_npu_runtime_libraries
    mv litert_npu_runtime_libraries app/libs/litert/
fi

echo "Dependencies setup complete."
