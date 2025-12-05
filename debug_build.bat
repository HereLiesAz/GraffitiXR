@echo off
setlocal EnableDelayedExpansion

set "SOURCE_DIR=%CD%\tools\vscode_extension"
set "BUILD_DIR=%TEMP%\debug_ext_build"

if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"

xcopy "%SOURCE_DIR%" "%BUILD_DIR%" /E /I /Q /Y >nul

pushd "%BUILD_DIR%"

echo [DEBUG] Installing...
call npm install --silent
if %ERRORLEVEL% NEQ 0 echo [ERROR] npm install failed.

echo [DEBUG] Compiling...
call npm run compile
if %ERRORLEVEL% NEQ 0 echo [ERROR] compile failed.

echo [DEBUG] Packaging...
call npm install --no-save @vscode/vsce >nul 2>&1
call npx @vscode/vsce package
if %ERRORLEVEL% NEQ 0 echo [ERROR] packaging failed.

echo [DEBUG] Listing directory...
dir

popd
