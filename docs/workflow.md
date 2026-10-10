# Development Workflow

## **1. Branching Strategy**
-   **Main Branch:** `main`.
-   **Feature Branches:** Agents typically work directly on the current branch provided by the environment, effectively a feature branch.
-   **Commits:** Small, atomic, and descriptive.
    -   *Bad:* "Fix stuff"
    -   *Good:* "Fix rotation calculation in ArRenderer"

## **2. CI/CD Pipeline (`.github/workflows/`)**

The workflow files in this repository are `android-ci.yml`, `merged-build.yml`, `release.yml`,
`jekyll-gh-pages.yml` and `label.yml`.

### **`android-ci.yml`** (with `merged-build.yml` running an overlapping build+test job)
-   **Triggers:** Pull Request into `main`, push to `main`, manual dispatch.
-   **Steps (common to both):**
    1.  Checkout code.
    2.  Set up JDK 21 (Temurin).
    3.  **Test:** `./gradlew test` (unit tests, in a separate job).
    4.  **Build:** `./gradlew assembleDebug` (always the default debug key; no keystore is decoded).

`android-ci.yml` is the canonical publisher: on a push, it creates/updates the shared GitHub Release
tagged `latest-debug-v<major>.<minor>` with the built debug APK. The two workflows previously raced
each other over that same tag; check each workflow file's own header comment for the current division
of responsibility, since it has changed more than once.

Signed release builds and Google Play publishing are `release.yml`: on every push to `main` it runs
the unit + native host tests, then builds a signed AAB/APK, publishes the AAB to Play and the APK
to the `latest-release-v<major>.<minor>` GitHub release. See [`docs/RELEASE.md`](RELEASE.md).

## **3. Release Process**
-   **Versioning:** Update `version.properties` (Major/Minor).
-   **Build Number:** CI builds take `versionCode` from `CI_VERSION_CODE` (per workflow, from the
    run number); local builds auto-increment `version.properties`' `versionBuild`. See
    [`docs/RELEASE.md`](RELEASE.md) §1.
-   **Distribution:** Google Play + GitHub release via `release.yml`; debug APK prerelease via
    `android-ci.yml` (see §2 above).

## **4. Local Environment Setup**
-   **SDK:** Ensure `local.properties` points to your Android SDK.
-   **Keys:** None needed to compile. No module applies the google-services plugin, so no
    `google-services.json` is required (the old template and CI injection step were removed as dead).


---
*Documentation updated on 2026-03-17 during website redesign and Stencil generation integration phase.*

*Documentation updated on 2026-09-22: corrected the workflow file names, which named three files that
don't exist (`android-ci-jules.yml`, `google-services-injection.yml`, `auto-release.yml`) — the Google
Services injection is an inline step inside the real CI workflows, not a separate file. Corrected the
build-number claim (auto-increments per build from `version.properties`, not from git commit count).*
