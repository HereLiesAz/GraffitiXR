# Development Workflow

## **1. Branching Strategy**
-   **Main Branch:** `main`.
-   **Feature Branches:** Agents typically work directly on the current branch provided by the environment, effectively a feature branch.
-   **Commits:** Small, atomic, and descriptive.
    -   *Bad:* "Fix stuff"
    -   *Good:* "Fix rotation calculation in ArRenderer"

## **2. CI/CD Pipeline**

-   **Pull requests.** No build workflow lives here. `label.yml` (central `pr-labeler.yml`) labels PRs.
-   **Merge to `main` → central builds.** Signed AAB/APK, Google Play and the GitHub release are run by
    the central HereLiesAz/workflows repository (Android Play Release / Android GitHub Release). No
    workflow in this repository builds on push to `main`.
-   **Pages.** `jekyll-gh-pages.yml` stays local: a Pages deploy must run in the repository it deploys.

## **3. Release Process**
-   **Versioning:** `version.properties` belongs to the shared version contract; the build reads it
    and never rewrites it.
-   **versionCode / versionName:** passed by the central release as `-PversionCodeOverride` /
    `-PversionNameOverride`; local and PR builds fall back to 1 and `version.properties`.
    See [`docs/RELEASE.md`](RELEASE.md).

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

*Documentation updated on 2026-10-22: release.yml, merged-build.yml and android-ci.yml removed; merge builds and
publishing moved to the central HereLiesAz/workflows repository.*
