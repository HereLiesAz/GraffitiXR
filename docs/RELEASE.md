# Release & Google Play Delivery

GraffitiXR ships to Google Play as a **signed Android App Bundle (AAB)**. This
document covers building a signed bundle locally, how the `versionCode` is
derived, modular delivery, the publishing workflow, and the one‑time setup the
maintainer must perform.

## TL;DR

```bash
# Local signed AAB — set these env vars to your own keystore first
export KEYSTORE_FILE=$(pwd)/app/keystore.jks
export KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...
./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

CI publishing is `.github/workflows/release.yml` (see [§2](#2-publishing-via-the-workflow)):
every push to `main` runs the unit + native host tests, then builds a signed AAB + APK, publishes
the AAB to Google Play and the APK to a GitHub release. `android-ci.yml` separately publishes a
**debug** APK to the `latest-debug-v<major>.<minor>` prerelease; `merged-build.yml` only builds and
tests.

---

## 1. Building a signed AAB

The release artifact is an `.aab`, not an APK. Google Play uses the bundle to
generate optimized per‑device APKs (see [Modular delivery](#3-modular-delivery--size)).

Signing is a checked-in `signingConfigs.release` block in `app/build.gradle.kts`
(not CI-injected `-Pandroid.injected.signing.*` properties, despite an earlier
version of this document), populated from **environment variables**:
`KEYSTORE_FILE` (path, defaults to `app/keystore.jks`), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. **No keystore is committed** (`*.jks` /
`*.keystore` are git‑ignored) — CI decodes the base64 `KEYSTORE_RAW` secret to
that path and exports the three credential env vars before building.

The `release` signing config is only created when the keystore file exists
*and* all three credentials are present; otherwise `bundleRelease` produces an
**unsigned** bundle (fine for inspection, not for upload) rather than failing.

### versionCode and versionName

`app/build.gradle.kts` resolves the version from two sources:

- **CI builds** set `CI_VERSION_CODE`, which becomes the `versionCode` verbatim, and
  `version.properties` is never rewritten. The value is per workflow:

  | Workflow | `CI_VERSION_CODE` | Published to |
  |----------|-------------------|--------------|
  | `release.yml` | `1,000,000 + run_number × 10 + (run_attempt − 1)` | Google Play + GitHub release |
  | `android-ci.yml` | `20,000 + run_number` | `latest-debug-v<maj>.<min>` debug prerelease |
  | `merged-build.yml` | `20,000 + run_number` | nothing (build/test only) |

  Only `release.yml` codes reach Play. They are monotonic across runs and distinct per re-run
  attempt, and the 1,000,000 floor clears every code published before the switch (~14,731).
  The publishing jobs also set `CI_VERSION_PATCH` to the commit count of the built commit
  (`git rev-list --count HEAD`), so `versionName` is `major.minor.<commit count>`: the same commit
  gets the same `versionName` from every workflow, and it only grows along `main`. CI jobs that
  don't publish leave it unset and keep the tracked `versionPatch`.
- **Local builds** (no `CI_VERSION_CODE`) auto‑increment `versionBuild` (the `versionCode`) and
  `versionPatch` in `version.properties` on every compiling Gradle invocation; a `versionMinor`
  bump resets the patch to 0 (see the comments in `app/build.gradle.kts`). There is no
  `-PversionBuild` override and no git-commit-count `versionCode` formula.

---

## 2. Publishing via the workflow

Workflow: **`.github/workflows/release.yml`**.

Triggers: every push to `main` (except pushes that only touch `version.properties`) and
`workflow_dispatch` with one input:

| Input     | Default | Description |
|-----------|---------|-------------|
| `publish` | `true`  | Off ⇒ build + upload the signed AAB/APK as the `graffitixr-release` workflow artifact only. |

Publishing (Play and GitHub release) only ever happens for `refs/heads/main`; a dispatch on another
branch builds the artifact and stops. Runs share the `release-publish` concurrency group with
`cancel-in-progress: false`, so a newer push queues behind an in-progress publish instead of
cancelling it mid-upload.

Jobs:

1. **`unit-tests`** — `./gradlew test` (the same command as `android-ci.yml`'s unit-tests job) and
   `tools/run_native_host_tests.sh`. Nothing below runs unless this passes.
2. **`build-and-publish`** (`needs: unit-tests`):
   1. Checks out with `fetch-depth: 0`; decodes the base64 `KEYSTORE_RAW` secret to
      `app/keystore.jks` and fails if it or any of `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
      `KEY_PASSWORD` is missing, so an unsigned build is never published.
   2. Sets up JDK 21 (Temurin) + Gradle, reads `applicationId` from `app/build.gradle.kts`, and
      derives `CI_VERSION_CODE` / `CI_VERSION_PATCH` (see [versionCode](#versioncode-and-versionname)).
   3. Runs `./gradlew bundleRelease assembleRelease` with the signing env vars, uploads the AAB and
      APK as the `graffitixr-release` artifact, and runs the SphereSLAM packaging/manifest checks
      against the release variant.
   4. Publishes the AAB to Google Play with `.github/scripts/play_multitrack_publish.py`: one Play
      edit, one bundle upload, then `internal` = `completed` and `alpha` / `beta` / `production` =
      `draft`, then commit. An uncommitted edit is deleted on failure or cancellation.
   5. Only after Play succeeded, creates or updates the moving GitHub release
      `latest-release-v<major>.<minor>` with the signed APK.

Nothing is committed back to the repository; the version comes from the run, not from
`version.properties`.

---

## 3. Modular delivery & size

### Automatic bundle splits (already in effect)

An AAB does **not** need separate per‑device artifacts. From a single
`bundleRelease`, Play generates and serves optimized APKs split by:

- **ABI** — this is the big win here. The native payload
  (`:core:nativebridge` and OpenCV) is large; with per‑ABI splits a device only
  downloads its own architecture's `.so` files. *(An earlier version of this document also cited
  LiteRT NPU runtime libraries under `core/nativebridge/libs/litert_npu_runtime_libraries/*` as part
  of this payload; no such directory exists in the current source — verify with `find` before relying
  on that claim.)*
- **Screen density** — only the matching drawable densities.
- **Language** — only the device's locale resources.

These splits are enabled explicitly in `app/build.gradle.kts`
(`bundle { abi/density/language { enableSplit = true } }`), which matches the
AAB defaults — documented in code so the intent is obvious.

### Dynamic feature modules — current status & rationale

The project is already cleanly multi‑module (`:feature:ar`, `:feature:editor`,
`:feature:dashboard`, `:android_collaboration_module`, `:core:*`), but these are
`com.android.library` modules **statically linked** into `:app`. They are
**compile‑time dependencies**: `app/.../MainScreen.kt` imports and uses their
types directly (`ArViewModel`, `CameraPreview`, `ArRenderer`, `EditorViewModel`, …). *(An earlier
version of this document also named `FreezePreviewScreen` and `DrawingCanvas` here; both have since
been deleted from the codebase — grep confirms neither exists anymore. The size-analysis argument
below should be re-verified against current `MainScreen.kt` dependencies before being relied on.)*

Converting these to **on‑demand** `com.android.dynamic-feature` modules was
evaluated and intentionally **not** done in this change, because:

- **They aren't optional.** AR and the editor are the app's core surfaces, not
  rarely‑used add‑ons. The README positions AR/precision tracing and the
  multi‑layer editor as the primary product.
- **Tight coupling.** On‑demand delivery requires the base module to *not*
  reference feature types at compile time. That means decoupling through
  interfaces + `SplitInstallManager` + reflective entry points, plus making
  Hilt work across dynamic features — a large refactor that **cannot be
  build‑verified in this environment**. Shipping an unverified conversion risks
  breaking the app.
- **The size win is already captured** by the automatic per‑ABI split above —
  the dominant size driver is the native/NPU payload, not optional UI code.

> **Correction (2026-09-22):** an earlier version of this document claimed the
> `com.android.dynamic-feature` plugin alias (`libs.plugins.android.dynamic.feature`) was already
> added to the version catalog. It is not — `gradle/libs.versions.toml` has no such entry. That
> infrastructure has not actually been set up; treat everything below as a proposal, not a
> already-started migration.

**Recommended future candidates** (each as a separately reviewed, build‑verified
PR), in priority order:

1. **LiteRT NPU runtimes** — *(this candidate cited a
   `core/nativebridge/libs/litert_npu_runtime_libraries/*` directory that does not exist in the
   current source. If NPU-vendor-specific native runtime libraries are added to the project in the
   future, splitting them as conditional / install-time dynamic features per device would still be
   worth evaluating — these tend to be large and vendor-specific, with only one vendor's runtime ever
   used on a given device — but as of this writing there is no such directory to split.)*
2. **Co‑op / collaboration** (`:android_collaboration_module`) as an **on‑demand**
   feature — genuinely optional (peer‑to‑peer multiplayer painting), but first
   needs decoupling from `:feature:ar`/`:app`.

When implementing, wire the module into `settings.gradle.kts`, list it under
`android { dynamicFeatures = setOf(":feature:xxx") }` in `:app`, add a
`<dist:module dist:onDemand="true|false">` block to the feature manifest, and
load on‑demand modules with the Play Feature Delivery `SplitInstall` APIs.

### R8 / minify + resource shrinking

Already enabled for `release` (`isMinifyEnabled = true`,
`isShrinkResources = true`) with a well‑maintained `app/proguard-rules.pro`
(explicit keeps for ARCore, OpenCV/native JNI, the SLAM bridge, serialization,
and AzNavRail). No change needed; left on.

### Play In‑App Updates (optional follow‑up)

Consider the Play Core **In‑App Updates** API to prompt users to update from
within the app (flexible for minor, immediate for critical). Optional and not
included here.

---

## 4. Required repository secrets

### Signing (read only by `release.yml`)

`android-ci.yml` and `merged-build.yml` build debug APKs with the default debug key and read no
signing secrets.

| Secret | Purpose |
|--------|---------|
| `KEYSTORE_RAW`      | Base64-encoded `.jks` keystore file — decoded to `app/keystore.jks` in CI |
| `KEYSTORE_PASSWORD` | Keystore (store) password |
| `KEY_ALIAS`         | Key alias |
| `KEY_PASSWORD`      | Key password |

### Google Play publishing

| Secret | Purpose |
|--------|---------|
| `PLAY_SERVICE_ACCOUNT_JSON` | Full JSON key of a Google Cloud service account with Play release access |

### Build config

None. `GOOGLE_SERVICES*`, `PROJECT_ID`, `CLIENT_ID` and `ARCORE_API_KEY` are no longer required:
no module applies the google-services plugin and nothing in the build reads an ARCore API key.

Crash reporting is **not** credentialed at build time. The old `CRASH_REPORT_TOKEN` build secret was
removed: it was compiled into `BuildConfig` and shipped inside every published APK, where decompiling
it took minutes. The credential is now a GitHub token the maintainer/tester enters in **Settings →
Crash-report token**; it is stored only on that device (DataStore) and never in the binary. Only an
account with **Issues: write** on `HereLiesAz/GraffitiXR` can file, so a blank token (the default)
simply disables uploads.

---

## 5. One‑time maintainer setup (manual)

The automated publish step cannot work until these are done **once**:

1. **Create a Google Cloud service account** in the project linked to your Play
   Console, and create a **JSON key** for it.
2. In **Play Console → Users and permissions**, invite that service account and
   grant it release permissions (at least *Release to testing tracks* /
   *Release to production* as needed) for this app.
3. Put the JSON key contents into the **`PLAY_SERVICE_ACCOUNT_JSON`** repo
   secret, and add the signing secrets above if not already present.
4. **Upload the very first release manually.** For a brand‑new app the Play
   Developer API **cannot** create the first release — the first `.aab` must be
   uploaded by hand in the Play Console (Internal testing is fine). Run
   `release.yml` via *Run workflow* with `publish` unchecked, download the
   `graffitixr-release` artifact, and upload its `.aab` in the console. After
   that first manual upload, pushes to `main` publish automatically.

> If you opt into **Play App Signing** (recommended), the keystore above becomes
> your **upload** key; Google re‑signs with the managed app‑signing key.

---

## 6. Data safety & privacy

Play requires an accurate **Data safety** form and a privacy policy. For
GraffitiXR:

- **No AdMob / ads.** The manifest declares no ads SDK and the app does **not**
  request the `com.google.android.gms.permission.AD_ID` permission. Declare
  "no advertising ID" accordingly.
- **Core product is offline / local.** The README states zero cloud dependencies
  and local‑only processing — reflect that (no/minimal data collection) in the
  form.
- **Third‑party SDKs that may collect data:** Google Play Services / ARCore is present. Review its
  data practices and disclose anything it collects on your behalf. *(An earlier version of this
  document also named a "Meta Wearables (mwdat)" integration here — grepping
  `gradle/libs.versions.toml` and `app/build.gradle.kts` finds no such dependency in the project, so
  that instruction has been removed.)*
- **Permissions to justify:** `CAMERA` (core), plus optional `BLUETOOTH*`,
  `ACCESS_*_LOCATION`, Wi‑Fi, and `INTERNET` — all already marked as optional
  hardware features in the manifest so they don't filter the listing.
- The network security config (`@xml/network_security_config`) sets
  `cleartextTrafficPermitted="false"` globally (and for `api.github.com`), so no
  cleartext HTTP is allowed — good for the security/data‑safety posture.

Keep the Data safety declaration in sync whenever an SDK or permission changes.
