#!/bin/bash
# GRAFFITIXR: FINALIZATION PROTOCOL (COMPOSE EDITION)
set -e

echo ">>> INITIATING CODEBASE HARMONIZATION..."

# ==============================================================================
# 1. RESOURCE MIGRATION (Strings, Themes, Configs Only)
# ==============================================================================
echo ">>> MIGRATING RESOURCES TO CORE:DESIGN..."
mkdir -p core/design/src/main/res

if [ -d "app/src/main/res" ]; then
    # Move Drawables (Vectors), Values (Strings/Themes), and XML (Network/Provider paths)
    # We explicitly exclude 'layout' and 'navigation' dirs if they accidentally exist.
    cp -r app/src/main/res/drawable core/design/src/main/res/ 2>/dev/null || true
    cp -r app/src/main/res/values core/design/src/main/res/ 2>/dev/null || true
    cp -r app/src/main/res/xml core/design/src/main/res/ 2>/dev/null || true
    cp -r app/src/main/res/font core/design/src/main/res/ 2>/dev/null || true

    # Clean up app module (Keep mipmap/launcher icons in app shell)
    rm -rf app/src/main/res/drawable
    rm -rf app/src/main/res/values
    rm -rf app/src/main/res/xml
    rm -rf app/src/main/res/font

    # Restore minimal app shell strings
    mkdir -p app/src/main/res/values
    echo '<?xml version="1.0" encoding="utf-8"?><resources><string name="app_name">GraffitiXR</string></resources>' > app/src/main/res/values/strings.xml
fi

# ==============================================================================
# 2. MANIFEST GENERATION (Module Identity)
# ==============================================================================
echo ">>> GENERATING MODULE MANIFESTS..."

generate_manifest() {
    local path=$1
    local pkg=$2
    local extra=$3

    mkdir -p "$path"
    cat > "$path/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$pkg">
    $extra
</manifest>
EOF
}

# Core Modules
generate_manifest "core/common/src/main" "com.hereliesaz.graffitixr.common" ""
generate_manifest "core/domain/src/main" "com.hereliesaz.graffitixr.domain" ""
generate_manifest "core/data/src/main" "com.hereliesaz.graffitixr.data" "<uses-permission android:name=\"android.permission.INTERNET\" />"
generate_manifest "core/native/src/main" "com.hereliesaz.graffitixr.native" ""
generate_manifest "core/design/src/main" "com.hereliesaz.graffitixr.design" ""

# Feature AR
generate_manifest "feature/ar/src/main" "com.hereliesaz.graffitixr.feature.ar" \
"
    <uses-permission android:name=\"android.permission.CAMERA\" />
    <uses-feature android:name=\"android.hardware.camera\" />
    <uses-feature android:name=\"android.hardware.camera.ar\" android:required=\"true\" />
    <application>
        <activity android:name=\".MappingActivity\" android:exported=\"false\" />
        <activity android:name=\".PhotoSphereCreationScreen\" android:exported=\"false\" />
    </application>
"

# Feature Editor
generate_manifest "feature/editor/src/main" "com.hereliesaz.graffitixr.feature.editor" ""

# Feature Dashboard
generate_manifest "feature/dashboard/src/main" "com.hereliesaz.graffitixr.feature.dashboard" \
"
    <application>
        <receiver android:name=\".ApkInstallReceiver\" android:exported=\"true\" />
    </application>
"

# ==============================================================================
# 3. PACKAGE & IMPORT REWRITER
# ==============================================================================
echo ">>> REWRITING PACKAGES AND IMPORTS..."

rewrite_code() {
    local module_path=$1
    local new_package=$2

    # Only process if directory exists
    if [ -d "$module_path" ]; then
        echo "  Processing module: $new_package"

        find "$module_path" -name "*.kt" | while read file; do
            # 1. Update Package Declaration
            sed -i "s|^package com.hereliesaz.graffitixr.*|package $new_package|" "$file"

            # 2. Update Imports (Global Mapping)
            sed -i "s|import com.hereliesaz.graffitixr.utils|import com.hereliesaz.graffitixr.common|g" "$file"
            sed -i "s|import com.hereliesaz.graffitixr.data.model|import com.hereliesaz.graffitixr.domain.model|g" "$file"
            sed -i "s|import com.hereliesaz.graffitixr.ui.theme|import com.hereliesaz.graffitixr.design.theme|g" "$file"

            # 3. Remap Resource R references
            # Changes 'import com.hereliesaz.graffitixr.R' to 'import com.hereliesaz.graffitixr.design.R'
            sed -i "s|import com.hereliesaz.graffitixr.R|import com.hereliesaz.graffitixr.design.R|g" "$file"

            # 4. Specific Component Fixes (Composables moved to design)
            sed -i "s|import com.hereliesaz.graffitixr.composables.Knob|import com.hereliesaz.graffitixr.design.components.Knob|g" "$file"
            sed -i "s|import com.hereliesaz.graffitixr.composables.PageIndicator|import com.hereliesaz.graffitixr.design.components.PageIndicator|g" "$file"
        done
    fi
}

# Execute Rewrites
rewrite_code "core/common" "com.hereliesaz.graffitixr.common"
rewrite_code "core/domain" "com.hereliesaz.graffitixr.domain.model"
rewrite_code "core/data" "com.hereliesaz.graffitixr.data"
rewrite_code "core/design" "com.hereliesaz.graffitixr.design"
rewrite_code "feature/ar" "com.hereliesaz.graffitixr.feature.ar"
rewrite_code "feature/editor" "com.hereliesaz.graffitixr.feature.editor"
rewrite_code "feature/dashboard" "com.hereliesaz.graffitixr.feature.dashboard"

# Fix Native package specifically
find "core/native" -name "*.kt" 2>/dev/null | while read file; do
    sed -i "s|^package com.hereliesaz.graffitixr.*|package com.hereliesaz.graffitixr.native|" "$file"
done

echo ">>> FINALIZATION COMPLETE."