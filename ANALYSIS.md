### 2. `ANALYSIS.md`
*Changes: Updated "Bad" and "Ugly" sections. We fixed the Repository hole and the Fingerprint logic.*

# Codebase Analysis: The Autopsy

**Date:** 2026-02-12
**Status:** Post-Operative (Stable)

## The Good
1.  **Teleological SLAM:** The `MobileGS` engine now possesses "foreknowledge." It can distinguish between "drift" and "intentional painting" by validating against the digital overlay.
2.  **Clean Data Layer:** The `ProjectRepository` is no longer a fiction. We have a proper `Domain` interface and `Data` implementation, resolving the circular dependencies and IO-on-Main-Thread risks.
3.  **Native Bridge:** `GraffitiJNI` is robust, exposing direct feature extraction from Bitmaps without needing a live camera session.

## The Bad (Remaining Debt)
### 1. The Monolith (`MainViewModel`)
* **Status:** Critical.
* **Issue:** `MainViewModel` is still the Puppet Master. It manually wires `ArRenderer` to the UI.
* **Next Step:** Refactor into a `GlobalInteractionUseCase` to decouple the AR lifecycle from the Android Activity lifecycle.

### 2. Native Memory Safety
* **Status:** Warning.
* **Issue:** `MobileGS` uses raw pointers. While `GraffitiJNI` handles the basics, a crash during the `Update` loop could leave the OpenGL context in a zombie state.

## The Ugly (Fixed)
* ~~**No Repository:**~~ *FIXED.* We implemented `ProjectRepositoryImpl` and `ProjectSerializer`.
* ~~**Fingerprint Aging:**~~ *FIXED.* `MobileGS` now supports `SetTargetDescriptors` and performs spatial pruning of old features.

## Recommendations
1.  **Refactor MainViewModel:** This is the last major architectural blocker.
2.  **Unit Test the Serializer:** Ensure `Uri` and `BlendMode` serialization is bulletproof across device restarts.