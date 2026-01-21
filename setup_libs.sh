#!/bin/bash
set -e

echo "Setting up dependencies..."

# Check if origin/dependencies exists
if ! git ls-remote --exit-code --heads origin dependencies >/dev/null 2>&1; then
    echo "Warning: 'dependencies' branch not found on remote."
    echo "Please run the 'Update Dependencies' GitHub workflow to generate the libraries branch."
    echo "If you are running this locally and have not pushed yet, ensure the branch exists."
    # Try to fetch anyway in case it's there but ls-remote failed for some reason or just proceed to fail at checkout
fi

git fetch origin dependencies

# Checkout libs
# We use -- to separate path
echo "Restoring libraries from dependencies branch..."
git checkout origin/dependencies -- app/libs/opencv app/libs/glm app/libs/litert

echo "Dependencies restored to app/libs/."

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
