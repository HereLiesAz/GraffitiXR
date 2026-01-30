#!/bin/bash
# GRAFFITIXR: THE FINAL REFACTOR
# Strict Mode: Exit on error.
set -e

# --- CONFIGURATION ---
ROOT_PKG="com/hereliesaz/graffitixr"
APP_SRC="app/src/main/java/$ROOT_PKG"

# Destination Paths
CORE_COMMON="core/common/src/main/java/$ROOT_PKG/common"
CORE_DOMAIN="core/domain/src/main/java/$ROOT_PKG/domain"
CORE_DATA="core/data/src/main/java/$ROOT_PKG/data"
CORE_NATIVE="core/native/src/main/java/$ROOT_PKG/native"
CORE_NATIVE_CPP="core/native/src/main/cpp"
CORE_DESIGN="core/design/src/main/java/$ROOT_PKG/design"
FEAT_AR="feature/ar/src/main/java/$ROOT_PKG/feature/ar"
FEAT_EDITOR="feature/editor/src/main/java/$ROOT_PKG/feature/editor"
FEAT_DASHBOARD="feature/dashboard/src/main/java/$ROOT_PKG/feature/dashboard"

echo ">>> INITIATING FINAL ARCHITECTURAL REFACTOR..."

# ==============================================================================
# 1. SCAFFOLDING & GRADLE SETUP
# ==============================================================================
echo ">>> CREATING MODULE SKELETONS..."

# Helper to create dir and build.gradle
create_module() {
    local path=$1
    local namespace=$2
    local extra_deps=$3
    local has_compose=$4
    local is_native=$5

    mkdir -p "$path"

    # Calculate build.gradle path (strip /src/main...)
    local module_root=$(echo "$path" | sed "s|/src/main/java.*||")
    local gradle_file="$module_root/build.gradle.kts"

    echo "Configuring $module_root..."

    cat > "$gradle_file" <<EOF
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    $( [[ "$namespace" == *"domain"* ]] && echo 'id("kotlin-parcelize")' )
}

android {
    namespace = "$namespace"
    compileSdk = 34
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        $( [[ "$is_native" == "true" ]] && echo 'externalNativeBuild { cmake { cppFlags("-std=c++17"); arguments("-DANDROID_STL=c++_shared") } }' )
    }

    $( [[ "$is_native" == "true" ]] && echo 'externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }' )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    $( [[ "$has_compose" == "true" ]] && echo 'buildFeatures { compose = true }; composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }' )
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    $extra_deps
    $( [[ "$has_compose" == "true" ]] && echo 'implementation(platform("androidx.compose:compose-bom:2024.01.00")); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.material3:material3"); implementation("androidx.compose.ui:ui-tooling-preview")' )
}
EOF
}

# --- CORE MODULES ---
create_module "$CORE_COMMON" "com.hereliesaz.graffitixr.common" \
    'implementation("com.jakewharton.timber:timber:5.0.1")' "false" "false"

create_module "$CORE_DOMAIN" "com.hereliesaz.graffitixr.domain" \
    'implementation(project(":core:common"))' "false" "false"

create_module "$CORE_DATA" "com.hereliesaz.graffitixr.data" \
    'implementation(project(":core:common")); implementation(project(":core:domain")); implementation("com.google.code.gson:gson:2.10.1")' "false" "false"

create_module "$CORE_NATIVE" "com.hereliesaz.graffitixr.native" \
    'implementation(project(":core:common")); implementation(project(":core:domain"))' "false" "true"

create_module "$CORE_DESIGN" "com.hereliesaz.graffitixr.design" \
    'implementation(project(":core:common"))' "true" "false"

# --- FEATURE MODULES ---
create_module "$FEAT_AR" "com.hereliesaz.graffitixr.feature.ar" \
    'implementation(project(":core:common")); implementation(project(":core:domain")); implementation(project(":core:design")); implementation(project(":core:native"))' "true" "false"

create_module "$FEAT_EDITOR" "com.hereliesaz.graffitixr.feature.editor" \
    'implementation(project(":core:common")); implementation(project(":core:domain")); implementation(project(":core:design"))' "true" "false"

create_module "$FEAT_DASHBOARD" "com.hereliesaz.graffitixr.feature.dashboard" \
    'implementation(project(":core:common")); implementation(project(":core:domain")); implementation(project(":core:design")); implementation(project(":core:data"))' "true" "false"

# --- UPDATE ROOT SETTINGS ---
echo ">>> UPDATING settings.gradle.kts..."
cat > settings.gradle.kts <<EOF
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "GraffitiXR"
include(":app")
include(":core:common", ":core:domain", ":core:data", ":core:native", ":core:design")
include(":feature:ar", ":feature:editor", ":feature:dashboard")
EOF

# ==============================================================================
# 2. FILE MIGRATION ENGINE
# ==============================================================================
echo ">>> MIGRATING FILES..."

# Function to find and move files
move_files() {
    local dest=$1
    shift
    local files=("$@")

    mkdir -p "$dest"

    for file in "${files[@]}"; do
        # Find file in app/src/main, excluding the core/feature folders we just made
        found=$(find app/src/main -name "$file" -not -path "*/core/*" -not -path "*/feature/*" -print -quit)
        if [ -n "$found" ]; then
            echo "  -> Moving $file to $(basename $(dirname $dest))"
            mv "$found" "$dest/"
        else
            # Check if check common root folder provided in tree
            found_root=$(find common -name "$file" 2>/dev/null | head -n 1)
            if [ -n "$found_root" ]; then
                echo "  -> Moving $file (from root) to $(basename $(dirname $dest))"
                mv "$found_root" "$dest/"
            fi
        fi
    done
}

# --- 1. CORE: COMMON ---
move_files "$CORE_COMMON" \
    "DispatcherProvider.kt" "Utils.kt" "LogUtils.kt" "Resource.kt"

# --- 2. CORE: DOMAIN ---
# Models and Interfaces
mkdir -p "$CORE_DOMAIN/model"
move_files "$CORE_DOMAIN/model" \
    "ProjectData.kt" "OverlayLayer.kt" "Fingerprint.kt" "Events.kt" "Layer.kt" "ProjectMetadata.kt" "UiState.kt"

# --- 3. CORE: DATA ---
# Persistence and Serialization
mkdir -p "$CORE_DATA/repository"
mkdir -p "$CORE_DATA/serialization"
move_files "$CORE_DATA" "ProjectManager.kt" "GithubRelease.kt"
move_files "$CORE_DATA/serialization" \
    "Serializers.kt" "BlendModeSerializer.kt" "FingerprintSerializer.kt" "KeyPointSerializer.kt" \
    "MatSerializer.kt" "OffsetSerializer.kt" "RefinementPathSerializer.kt"

# --- 4. CORE: NATIVE ---
# C++ Source
mkdir -p "$CORE_NATIVE_CPP"
# Move all C++ content from app/src/main/cpp
if [ -d "app/src/main/cpp" ]; then
    echo "  -> Moving C++ Engine"
    mv app/src/main/cpp/* "$CORE_NATIVE_CPP/" 2>/dev/null || true
fi
# JNI Wrappers
move_files "$CORE_NATIVE" "SlamManager.kt" "SlamReflectionHelper.kt"

# --- 5. CORE: DESIGN ---
# Theme and Generic UI
mkdir -p "$CORE_DESIGN/theme"
mkdir -p "$CORE_DESIGN/components"
move_files "$CORE_DESIGN/theme" "Color.kt" "Theme.kt" "Typography.kt"
move_files "$CORE_DESIGN/components" \
    "Knob.kt" "PageIndicator.kt" "ProgressIndicator.kt" "TapFeedbackEffect.kt" \
    "GestureFeedback.kt" "RotationAxisFeedback.kt" "CustomHelpOverlay.kt" "DoubleTapHintDialog.kt"

# --- 6. FEATURE: AR ---
# Rendering and AR Logic
mkdir -p "$FEAT_AR/rendering"
move_files "$FEAT_AR" \
    "ArView.kt" "ArRenderer.kt" "ArState.kt" \
    "TargetCreationOverlay.kt" "TargetRefinementScreen.kt" "UnwarpScreen.kt" \
    "MappingScreen.kt" "MappingActivity.kt" "PhotoSphereCreationScreen.kt"
move_files "$FEAT_AR/rendering" \
    "SimpleQuadRenderer.kt" "PointCloudRenderer.kt" "PlaneRenderer.kt" \
    "BackgroundRenderer.kt" "AugmentedImageRenderer.kt" "ProjectedImageRenderer.kt" \
    "GridRenderer.kt" "MiniMapRenderer.kt" "ShaderUtil.kt" "HomographyHelper.kt"

# --- 7. FEATURE: EDITOR ---
# Image Manipulation
move_files "$FEAT_EDITOR" \
    "EditorUi.kt" "DrawingCanvas.kt" "AdjustmentsPanel.kt" "AdjustmentsControls.kt" \
    "CurvesAdjustment.kt" "CurvesDialog.kt" "MockupScreen.kt" "OverlayScreen.kt" "TraceScreen.kt" \
    "ImageUtils.kt" "ImageProcessingUtils.kt" "BackgroundRemover.kt" "CurvesUtil.kt" \
    "YuvToRgbConverter.kt" "BitmapUtils.kt" "LayerTransformState.kt" \
    "AdjustmentSliderDialog.kt" "ColorBalanceDialog.kt" "RotationAxis.kt"

# --- 8. FEATURE: DASHBOARD ---
# App Management
move_files "$FEAT_DASHBOARD" \
    "ProjectLibraryScreen.kt" "SettingsScreen.kt" \
    "OnboardingScreen.kt" "OnboardingManager.kt" "OnboardingDialog.kt" \
    "SaveProjectDialog.kt" "ApkInstallReceiver.kt"

# ==============================================================================
# 3. APP CLEANUP & CONFIGURATION
# ==============================================================================
echo ">>> CONFIGURING APP MODULE..."

cat > app/build.gradle.kts <<EOF
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.hereliesaz.graffitixr"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.hereliesaz.graffitixr"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
}
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(project(":core:native"))
    implementation(project(":feature:ar"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:dashboard"))
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
EOF

echo ">>> CLEANING UP EMPTY DIRECTORIES..."
# Aggressively clean up empty dirs in app/src/main/java
find app/src/main/java -type d -empty -delete 2>/dev/null || true
find app/src/main/cpp -type d -empty -delete 2>/dev/null || true

echo ">>> REFACTOR COMPLETE. SYNC WITH GRADLE NOW."