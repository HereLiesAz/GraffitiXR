# Setup dependencies for GraffitiXR on Windows
$ErrorActionPreference = "Stop"
Write-Host "Setting up dependencies in root /libs/..." -ForegroundColor Cyan

if (-not (Test-Path "libs")) { New-Item -ItemType Directory -Path "libs" | Out-Null }

Write-Host "Fetching dependencies branch..."
git fetch origin dependencies

# --- OpenCV ---
Write-Host "Restoring OpenCV..."
if (Test-Path "libs/opencv") { Remove-Item -Recurse -Force "libs/opencv" }
if (Test-Path "opencv") { Remove-Item -Recurse -Force "opencv" }

git checkout origin/dependencies -- opencv
Move-Item -Path "opencv" -Destination "libs/opencv"

# PATCH: Fix deprecated Proguard file and JVM target in OpenCV
$opencvBuildGradle = "libs/opencv/build.gradle"
if (Test-Path $opencvBuildGradle) {
    Write-Host "Patching OpenCV build.gradle..."
    $content = Get-Content $opencvBuildGradle -Raw

    # Force replace the exact string causing the error
    $content = $content.Replace("proguard-android.txt", "proguard-android-optimize.txt")

    # Ensure Kotlin JVM Target 17
    if ($content -notmatch "jvmTarget") {
        Write-Host "Adding Kotlin JVM Target 17..."
        $content = $content -replace "compileOptions \{", "kotlinOptions {`n        jvmTarget = `"17`"`n    }`n`n    compileOptions {"
    }

    [System.IO.File]::WriteAllText((Resolve-Path $opencvBuildGradle), $content)
    Write-Host "Patch applied to $opencvBuildGradle"
}

# --- GLM ---
Write-Host "Restoring GLM..."
if (Test-Path "libs/glm") { Remove-Item -Recurse -Force "libs/glm" }
New-Item -ItemType Directory -Path "libs/glm" | Out-Null
$glmZip = "glm.zip"
Invoke-WebRequest -Uri "https://github.com/g-truc/glm/archive/refs/tags/1.0.1.zip" -OutFile $glmZip
Expand-Archive -Path $glmZip -DestinationPath "glm_temp" -Force
Move-Item -Path "glm_temp/glm-1.0.1/glm" -Destination "libs/glm/glm"
Remove-Item -Recurse -Force "glm_temp"
Remove-Item -Force $glmZip

# --- LiteRT & MLKit ---
Write-Host "Restoring LiteRT and MLKit AARs..."
if (Test-Path "libs/litert-2.1.0.aar") { Remove-Item -Force "libs/litert-2.1.0.aar" }
if (Test-Path "libs/mlkit-subject-segmentation.aar") { Remove-Item -Force "libs/mlkit-subject-segmentation.aar" }

git checkout origin/dependencies -- litert-2.1.0.aar mlkit-subject-segmentation.aar
Move-Item -Path "litert-2.1.0.aar" -Destination "libs/"
Move-Item -Path "mlkit-subject-segmentation.aar" -Destination "libs/"

Write-Host "Dependencies setup complete. Please Sync Gradle." -ForegroundColor Green
