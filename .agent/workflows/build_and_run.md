---
description: Build, Install, and Run the Android Debug App
---
This workflow builds the debug APK, installs it on a connected device, and launches the main activity.

1. Connect your Android device via USB or start an emulator.
// turbo
2. Run the build and launch script
```bash
.\run_debug_app.bat
```

3. **If Build Fails or App Crashes**:
   The script will generate `ai_debug_error.log` (for build fails) or `ai_debug_crash.log` (for crashes).
   
   **Ask the AI:**
   > "Fix the errors in ai_debug_error.log" 
   > *or* 
   > "Fix the crash in ai_debug_crash.log"
