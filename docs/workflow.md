# Development Workflow

## **1. Branching Strategy**
-   **Main Branch:** `main`.
-   **Feature Branches:** Agents typically work directly on the current branch provided by the environment, effectively a feature branch.
-   **Commits:** Small, atomic, and descriptive.
    -   *Bad:* "Fix stuff"
    -   *Good:* "Fix rotation calculation in ArRenderer"

## **2. CI/CD Pipeline (`.github/workflows/`)**

The actual workflow files present in this repository are `android-ci.yml`, `merged-build.yml`,
`jekyll-gh-pages.yml`, `jules-review.yml`, `jules-triage.yml`, and `label.yml` — there is no
`android-ci-jules.yml`, `google-services-injection.yml`, or `auto-release.yml`.

### **`android-ci.yml`** (with `merged-build.yml` running an overlapping build+test job)
-   **Triggers:** Push to any branch, Pull Request to `main`, manual dispatch.
-   **Steps (common to both):**
    1.  Checkout code.
    2.  **Inject Google Services:** an inline shell step (not a separate workflow file) substitutes
        secrets into `app/google-services.json.template` to produce `app/google-services.json`, when
        the template exists.
    3.  Set up JDK 21 (Temurin).
    4.  Decode the base64 `KEYSTORE_RAW` secret to `app/keystore.jks` (falls back to the debug key if
        the secret is empty).
    5.  **Test:** `./gradlew test` (unit tests, in a separate job).
    6.  **Build:** `./gradlew assembleDebug`.

`android-ci.yml` is the canonical publisher: on a push, it creates/updates the shared GitHub Release
tagged `latest-debug-v<major>.<minor>` with the built debug APK. The two workflows previously raced
each other over that same tag; check each workflow file's own header comment for the current division
of responsibility, since it has changed more than once.

There is currently no separate signed-AAB / Google Play publishing workflow — see
[`docs/RELEASE.md`](RELEASE.md) for what publishing automation does and doesn't exist yet.

## **3. Release Process**
-   **Versioning:** Update `version.properties` (Major/Minor).
-   **Build Number:** Auto-increments on every build (local or CI) from `version.properties`'
    `versionBuild` value — not from git commit count; see [`docs/RELEASE.md`](RELEASE.md) §1.
-   **Distribution:** Automated via GitHub Releases (debug APK only — see §2 above).

## **4. Local Environment Setup**
-   **SDK:** Ensure `local.properties` points to your Android SDK.
-   **Keys:** You need `app/google-services.json`. Use the template `app/google-services.json.template` and fill it with dummy data for local compilation if real keys are not available.


---
*Documentation updated on 2026-03-17 during website redesign and Stencil generation integration phase.*

*Documentation updated on 2026-09-22: corrected the workflow file names, which named three files that
don't exist (`android-ci-jules.yml`, `google-services-injection.yml`, `auto-release.yml`) — the Google
Services injection is an inline step inside the real CI workflows, not a separate file. Corrected the
build-number claim (auto-increments per build from `version.properties`, not from git commit count).*
