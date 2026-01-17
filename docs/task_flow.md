# Task Flow & Contribution

## 1. Pick a Task
Check `TODO.md`. Look for "High Priority".

## 2. Create a Branch
Format: `feature/your-feature-name` or `fix/issue-description`.

## 3. The Implementation Loop
1.  **Code:** Write Kotlin/C++.
2.  **Build:** Run `./gradlew assembleDebug`.
3.  **Test:** If touching Native code, you **MUST** test on a physical device. The Emulator does not support ARCore depth efficiently.

## 4. Documentation
* If you changed a UI pattern, update `UI_UX.md`.
* If you changed a C++ parameter, update `SLAM_SETUP.md`.

## 5. Merge
* Open a PR.
* Ensure CI passes (Lint + Build).
* Squash and Merge.