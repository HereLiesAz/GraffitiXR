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
        cp -r "$TEMP_DIR/opencv" "$TARGET_DIR/"
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
