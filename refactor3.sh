#!/bin/bash
# GRAFFITIXR: APP MODULE IMPORT REPAIR
set -e

echo ">>> WIRING APP MODULE TO NEW FEATURES..."

APP_SRC="app/src/main/java"

# Function to update imports in the App module
fix_import() {
    local old_pkg=$1
    local new_pkg=$2
    local class_name=$3

    echo "  Linking $class_name -> $new_pkg"
    # Replace explicit imports
    grep -rl "import $old_pkg.$class_name" $APP_SRC | xargs sed -i "s|import $old_pkg.$class_name|import $new_pkg.$class_name|g" 2>/dev/null || true
    # Replace wildcard imports (rare but possible)
    grep -rl "import $old_pkg.*" $APP_SRC | xargs sed -i "s|import $old_pkg.*|import $new_pkg.*|g" 2>/dev/null || true
}

# --- 1. CORE LINKING ---
# Domain Models
find core/domain -name "*.kt" -exec basename {} .kt \; | while read class; do
    fix_import "com.hereliesaz.graffitixr.data.model" "com.hereliesaz.graffitixr.domain.model" "$class"
    fix_import "com.hereliesaz.graffitixr" "com.hereliesaz.graffitixr.domain.model" "$class"
done

# Common Utils
fix_import "com.hereliesaz.graffitixr.utils" "com.hereliesaz.graffitixr.common" "LogUtils"
fix_import "com.hereliesaz.graffitixr.utils" "com.hereliesaz.graffitixr.common" "DispatcherProvider"

# Design Components
fix_import "com.hereliesaz.graffitixr.ui.theme" "com.hereliesaz.graffitixr.design.theme" "GraffitiXRTheme"
fix_import "com.hereliesaz.graffitixr.composables" "com.hereliesaz.graffitixr.design.components" "Knob"
fix_import "com.hereliesaz.graffitixr.composables" "com.hereliesaz.graffitixr.design.components" "AzNavRail"

# Native
fix_import "com.hereliesaz.graffitixr.slam" "com.hereliesaz.graffitixr.native" "SlamManager"

# --- 2. FEATURE LINKING ---
# AR Feature
fix_import "com.hereliesaz.graffitixr" "com.hereliesaz.graffitixr.feature.ar" "ArView"
fix_import "com.hereliesaz.graffitixr" "com.hereliesaz.graffitixr.feature.ar" "MappingActivity"

# Editor Feature
fix_import "com.hereliesaz.graffitixr.composables" "com.hereliesaz.graffitixr.feature.editor" "EditorUi"
fix_import "com.hereliesaz.graffitixr.composables" "com.hereliesaz.graffitixr.feature.editor" "MockupScreen"
fix_import "com.hereliesaz.graffitixr.composables" "com.hereliesaz.graffitixr.feature.editor" "OverlayScreen"
fix_import "com.hereliesaz.graffitixr.composables" "com.hereliesaz.graffitixr.feature.editor" "TraceScreen"

# Dashboard Feature
fix_import "com.hereliesaz.graffitixr.composables" "com.hereliesaz.graffitixr.feature.dashboard" "SettingsScreen"
fix_import "com.hereliesaz.graffitixr.composables" "com.hereliesaz.graffitixr.feature.dashboard" "ProjectLibraryScreen"
fix_import "com.hereliesaz.graffitixr.composables" "com.hereliesaz.graffitixr.feature.dashboard" "OnboardingScreen"

echo ">>> APP MODULE WIRED."