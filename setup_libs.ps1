# Setup dependencies for GraffitiXR on Windows
$ErrorActionPreference = "Stop"
Write-Host "Setting up dependencies in root /libs/..." -ForegroundColor Cyan

# Helper for robust deletion (handles locks by attempting rename first)
function Safe-RemoveItem {
    param([string]$Path)
    if (Test-Path $Path) {
        try {
            Remove-Item -Recurse -Force $Path -ErrorAction Stop
        } catch {
            $tempPath = "$Path.old_$(Get-Date -Format 'HHmmss')"
            Write-Warning "Folder $Path is locked. Attempting to rename and delete..."
            try {
                Move-Item -Path $Path -Destination $tempPath -ErrorAction Stop
                Remove-Item -Recurse -Force $tempPath -ErrorAction SilentlyContinue
            } catch {
                Write-Host "CRITICAL: Could not delete $Path." -ForegroundColor Red
                Write-Host "Likely causes: Android Studio is open, Gradle is running, or Google Drive is syncing." -ForegroundColor Yellow
                Write-Host "Please CLOSE Android Studio and stop any Java/Gradle processes, then try again." -ForegroundColor Yellow
                throw "Access Denied to $Path"
            }
        }
    }
}

# 1. Ensure libs exists
if (-not (Test-Path "libs")) { New-Item -ItemType Directory -Path "libs" | Out-Null }

# 2. Fetch from dependencies branch
Write-Host "Fetching dependencies branch..."
git fetch origin dependencies

# 3. Restore OpenCV (CRITICAL: Must be in an 'sdk' subfolder for CMake paths to resolve)
Write-Host "Restoring OpenCV to libs/opencv/sdk..."
Safe-RemoveItem "libs/opencv"
Safe-RemoveItem "opencv"

git checkout origin/dependencies -- opencv
New-Item -ItemType Directory -Path "libs/opencv" | Out-Null
Move-Item -Path "opencv" -Destination "libs/opencv/sdk"

# 4. Patch OpenCV build.gradle
$opencvBuildGradle = "libs/opencv/sdk/build.gradle"
if (Test-Path $opencvBuildGradle) {
    Write-Host "Patching $opencvBuildGradle..."
    $content = Get-Content $opencvBuildGradle -Raw
    $content = $content.Replace("'proguard-android.txt'", "'proguard-android-optimize.txt'")
    $content = $content.Replace('"proguard-android.txt"', '"proguard-android-optimize.txt"')
    if ($content -notmatch "jvmTarget") {
        $content = $content -replace "compileOptions \{", "kotlinOptions {`n        jvmTarget = `"17`"`n    }`n`n    compileOptions {"
    }
    [System.IO.File]::WriteAllText((Resolve-Path $opencvBuildGradle), $content)
    Write-Host "Patch applied successfully." -ForegroundColor Green
}

# 5. Restore GLM
Write-Host "Restoring GLM..."
Safe-RemoveItem "libs/glm"
New-Item -ItemType Directory -Path "libs/glm" | Out-Null
$glmZip = "glm.zip"
Invoke-WebRequest -Uri "https://github.com/g-truc/glm/archive/refs/tags/1.0.1.zip" -OutFile $glmZip
Expand-Archive -Path $glmZip -DestinationPath "glm_temp" -Force
Move-Item -Path "glm_temp/glm-1.0.1/glm" -Destination "libs/glm/glm"
Remove-Item -Recurse -Force "glm_temp"
Remove-Item -Force $glmZip

# 6. Restore AARs
Write-Host "Restoring LiteRT and MLKit..."
Safe-RemoveItem "libs/litert-2.1.0.aar"
Safe-RemoveItem "libs/mlkit-subject-segmentation.aar"
git checkout origin/dependencies -- litert-2.1.0.aar mlkit-subject-segmentation.aar
Move-Item -Path "litert-2.1.0.aar" -Destination "libs/"
Move-Item -Path "mlkit-subject-segmentation.aar" -Destination "libs/"

Write-Host "All libraries installed in /libs/ and patched." -ForegroundColor Green
Write-Host "Please Sync Gradle in Android Studio now."
