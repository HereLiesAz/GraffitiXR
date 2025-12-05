
param(
    [string]$AdbPath = "adb",
    [string]$GradlewPath = ".\gradlew.bat"
)

$ErrorLogFile = "ai_debug_error.log"
$CrashLogFile = "ai_debug_crash.log"
$PackageName = "com.hereliesaz.graffitixr"

function Write-Status($msg) {
    Write-Host "[STATUS] $msg" -ForegroundColor Cyan
}

function Write-ErrorMsg($msg) {
    Write-Host "[ERROR] $msg" -ForegroundColor Red
}

function Capture-Build-Errors {
    param($logFile)
    $lines = Get-Content $logFile
    $errors = $lines | Select-String "error:|FAILED|Exception" -Context 2,2
    if ($errors) {
        $errors | Out-File $ErrorLogFile
        Write-ErrorMsg "Build failed. Relevant errors saved to $ErrorLogFile"
        Write-Host ">>> ACTION REQUIRED: Ask the AI to 'Fix the build errors in $ErrorLogFile'" -ForegroundColor Yellow
    } else {
        # Fallback: copy whole log
        Copy-Item $logFile $ErrorLogFile
        Write-ErrorMsg "Build failed. Log saved to $ErrorLogFile"
    }
}

# 1. BUILD PHASE
Write-Status "Building Debug APK..."
$buildLog = "build_temp.log"
& $GradlewPath assembleDebug | Tee-Object -FilePath $buildLog
if ($LASTEXITCODE -ne 0) {
    Capture-Build-Errors $buildLog
    exit 1
}

# 2. INSTALL PHASE
Write-Status "Installing APK..."
& $AdbPath install -r app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) {
    Write-ErrorMsg "Installation failed."
    exit 1
}

# 3. LAUNCH PHASE
Write-Status "Clearing Logcat..."
& $AdbPath logcat -c

Write-Status "Launching App..."
& $AdbPath shell am start -n "$PackageName/.MainActivity"
if ($LASTEXITCODE -ne 0) {
    Write-ErrorMsg "Launch failed."
    exit 1
}

# 4. MONITOR PHASE
Write-Status "Monitoring for crashes (Ctrl+C to stop)..."
Write-Host "If the app crashes, the logs will be captured for the AI." -ForegroundColor Gray

# We run adb logcat and read the stream
$adbProcess = Start-Process -FilePath $AdbPath -ArgumentList "logcat", "-v", "threadtime", "*:E" -NoNewWindow -PassThru -RedirectStandardOutput "logcat_stream.txt"

try {
    Get-Content "logcat_stream.txt" -Wait -Tail 0 | ForEach-Object {
        Write-Host $_  # Echo to console
        
        if ($_ -match "FATAL EXCEPTION" -or $_ -match "Process $PackageName died") {
            Write-ErrorMsg "CRASH DETECTED!"
            
            # Capture the full log context
            Write-Status "Capturing crash details..."
            Stop-Process -Id $adbProcess.Id -Force
            
            # Dump the specific crash log
            $fullLog = Get-Content "logcat_stream.txt"
            $crashIndex = [array]::IndexOf($fullLog, $_)
            $start = [math]::Max(0, $crashIndex - 50)
            $end = $fullLog.Count - 1
            
            $fullLog[$start..$end] | Out-File $CrashLogFile
            
            Write-Host "---------------------------------------------------"
            Write-Host "CRASH LOG SAVED TO: $CrashLogFile" -ForegroundColor Yellow
            Write-Host ">>> ACTION REQUIRED: Ask the AI to 'Debug the crash in $CrashLogFile'" -ForegroundColor Yellow
            Write-Host "---------------------------------------------------"
            
            exit 1
        }
    }
}
finally {
    if (-not $adbProcess.HasExited) {
        Stop-Process -Id $adbProcess.Id -Force
    }
}
