# Implementation Plan - Fix AR Rendering Failure (Black Screen)

## Phase 1: Diagnosis & Reproduction
- [ ] Task: Create a reproduction test case for the rendering failure.
    - [ ] Identify the critical initialization flow in `feature:ar` (e.g., `ArViewModel` or `ArRenderer`).
    - [ ] Write an instrumentation or unit test that mocks the ARCore session and attempts to initialize the `MobileGS` renderer.
    - [ ] Assert that the renderer state transitions to 'error' or 'uninitialized' as observed in the bug.
    - [ ] **CRITICAL:** Run the test and confirm it fails as expected (Red Phase).

## Phase 2: Core Rendering Implementation
- [ ] Task: Audit the ARCore and MobileGS initialization sequence.
    - [ ] Inspect `feature:ar` for race conditions during ARCore session creation and Surface availability.
    - [ ] Verify that the `NativeBridge` is correctly loading and initializing the C++ `MobileGS` engine.
    - [ ] Check for any JNI exceptions or native errors being logged during startup.
- [ ] Task: Fix the rendering initialization bug.
    - [ ] Apply the necessary fix to the AR view or native bridge to ensure the rendering surface is correctly bound.
    - [ ] Ensure the ARCore frame is properly dispatched to the `MobileGS` renderer.
    - [ ] **CRITICAL:** Run the reproduction test again and confirm it now passes (Green Phase).
- [ ] Task: Conductor - User Manual Verification 'Core Rendering Fix' (Protocol in workflow.md)

## Phase 3: Stability & Resource Management
- [ ] Task: Refactor initialization logic for improved robustness.
    - [ ] Implement better error handling and state reporting in the AR renderer.
    - [ ] Ensure proper resource cleanup (Surface release, Native engine shutdown) when the AR view is paused or destroyed.
    - [ ] Verify that the reproduction test and all existing `feature:ar` tests pass (Refactor Phase).
- [ ] Task: Expand test coverage for AR lifecycle transitions.
    - [ ] Add tests for pausing and resuming AR mode to ensure rendering remains stable.
    - [ ] Verify code coverage for the fix meets the >80% requirement.
- [ ] Task: Conductor - User Manual Verification 'Stability & Resource Management' (Protocol in workflow.md)
