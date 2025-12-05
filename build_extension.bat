@echo off
setlocal EnableDelayedExpansion

set "SOURCE_DIR=%CD%\tools\vscode_extension"
set "BUILD_DIR=%TEMP%\GraffitiXR_Extension_Build"
set "LOG_FILE=%CD%\build_debug.txt"

echo Starting Build > "%LOG_FILE%"

echo ========================================================
echo   GraffitiXR Extension Builder (Google Drive Bypass)
echo ========================================================
echo.

echo [SETUP] Creating temporary local build directory...
echo [SETUP] Creating temporary local build directory... >> "%LOG_FILE%"
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"

echo [SETUP] Copying source files to %BUILD_DIR%...
echo [SETUP] Copying source files... >> "%LOG_FILE%"
xcopy "%SOURCE_DIR%" "%BUILD_DIR%" /E /I /Q /Y >nul

pushd "%BUILD_DIR%"

echo [1/4] Installing dependencies (Locally)...
echo [1/4] Installing dependencies... >> "%LOG_FILE%"
call npm install --silent >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] npm install failed.
    echo [ERROR] npm install failed. >> "%LOG_FILE%"
    goto :FAIL
)

echo [2/4] Compiling extension...
echo [2/4] Compiling extension... >> "%LOG_FILE%"
call npm run compile >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed.
    echo [ERROR] Compilation failed. >> "%LOG_FILE%"
    goto :FAIL
)

echo [3/4] Installing latest VSCE packager...
echo [3/4] Installing vsce... >> "%LOG_FILE%"
call npm install --no-save @vscode/vsce >> "%LOG_FILE%" 2>&1

echo [4/4] Packaging VSIX...
echo [4/4] Packaging VSIX... >> "%LOG_FILE%"
:: Use the locally installed vsce
call npx @vscode/vsce package >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Packaging failed.
    echo [ERROR] Packaging failed. >> "%LOG_FILE%"
    goto :FAIL
)

echo [POST] Copying artifacts back to Google Drive...
echo [POST] Copying artifacts back... >> "%LOG_FILE%"
popd
copy /Y "%BUILD_DIR%\*.vsix" "%SOURCE_DIR%\" >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Failed to copy VSIX back to source.
    echo [ERROR] Failed to copy VSIX back to source. >> "%LOG_FILE%"
    goto :FAIL
)

echo.
echo ========================================================
echo [SUCCESS] Build Complete!
echo [SUCCESS] Build Complete! >> "%LOG_FILE%"
echo ========================================================
echo.
echo TO INSTALL:
echo 1. In VS Code, run Command: "Extensions: Install from VSIX..."
echo 2. Select: tools\vscode_extension\antigravity-android-debugger-0.0.1.vsix
echo.

rmdir /s /q "%BUILD_DIR%"
exit /b 0

:FAIL
popd
echo.
echo [ERROR] Build process failed. Check %TEMP%\graffiti_build.log for details.
echo.
exit /b 1
