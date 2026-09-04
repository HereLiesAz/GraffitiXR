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

Publishing is automated by the **Build & Publish AAB (Play)**
(`.github/workflows/release-aab.yml`) workflow — run it from the Actions tab.

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

### versionCode

`app/build.gradle.kts` derives `versionCode` from `version.properties`'
`versionBuild` value, which **auto‑increments on every build** (local or CI —
there is no `-PversionBuild` override and no git-commit-count formula, despite
an earlier version of this document). It never resets and is not
git‑history‑dependent, so it stays monotonic across both local and CI builds
by construction. `versionName` is `major.minor.patch`, also from
`version.properties`, with the patch component likewise auto‑incrementing per
build (see the file's own comments for the minor‑bump‑resets‑patch rule).

---

## 2. Publishing via the workflow

Workflow: **`.github/workflows/release-aab.yml`** — `workflow_dispatch` only.

Inputs:

| Input     | Default    | Description |
|-----------|------------|-------------|
| `track`   | `internal` | `internal` / `alpha` / `beta` / `production` |
| `status`  | `draft`    | `draft` or `completed` |
| `publish` | `false`    | **Off ⇒ build + upload the `.aab` as a workflow artifact only.** On ⇒ also upload to Play. |

What it does:

1. Checks out with `fetch-depth: 0` and write access, so a publish run can
   commit the incremented `versionBuild` in `version.properties` back to `main`.
2. Injects `google-services.json` and decodes the base64 `KEYSTORE_RAW` secret
   to `app/keystore.jks` (same steps as `release-apk.yml`). OpenCV needs no
   fetch step — it's a Maven Central dependency (`org.opencv:opencv`, Java +
   native via Prefab).
3. Sets up JDK 17 (Temurin) + Gradle.
4. Reads `applicationId` from `app/build.gradle.kts` (not hardcoded), for the
   later Play-upload step.
5. Runs `bundleRelease` — no `-PversionBuild` override; `versionCode` comes
   from `version.properties`' auto-incrementing `versionBuild`, and signing
   from the `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` env
   vars (see §1).
6. Uploads the `.aab` as a build artifact (`graffitixr-release-aab`), then —
   only once the Play upload (next step) has actually succeeded — commits the
   new `versionBuild` back to `main` so it never falls behind builds already
   shipped.
7. **Only if `publish == true`:** uploads to Play with
   [`r0adkll/upload-google-play@v1`](https://github.com/r0adkll/upload-google-play)
   using `serviceAccountJsonPlainText`, the resolved `packageName`
   (`com.hereliesaz.graffitixr`), the `.aab` glob, and the chosen `track` /
   `status`.

Default behaviour is safe: leaving `publish` off just produces a downloadable,
signed bundle for manual inspection or manual console upload.

---

## 3. Modular delivery & size

### Automatic bundle splits (already in effect)

An AAB does **not** need separate per‑device artifacts. From a single
`bundleRelease`, Play generates and serves optimized APKs split by:

- **ABI** — this is the big win here. The native payload
  (`:core:nativebridge`, OpenCV, and the LiteRT **NPU runtime** libraries for
  Qualcomm/MediaTek/Google Tensor) is large; with per‑ABI splits a device only
  downloads its own architecture's `.so` files.
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
types directly (`ArViewModel`, `CameraPreview`, `FreezePreviewScreen`,
`ArRenderer`, `EditorViewModel`, `DrawingCanvas`, …).

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

The `com.android.dynamic-feature` plugin alias is added to the version catalog
(`libs.plugins.android.dynamic.feature`) so the infrastructure is ready.

**Recommended future candidates** (each as a separately reviewed, build‑verified
PR), in priority order:

1. **LiteRT NPU runtimes** (`core/nativebridge/libs/litert_npu_runtime_libraries/*`)
   as **conditional / install‑time** dynamic features targeted by device — these
   are large, vendor‑specific, and only one vendor's runtime is ever used on a
   given device.
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

### Signing (already used by `release-apk.yml`)

| Secret | Purpose |
|--------|---------|
| `KEYSTORE_PRIVATE` | PEM private key — assembled into `app/keystore.jks` in CI |
| `KEYSTORE_CHAIN`   | PEM certificate chain |
| `KEYSTORE_PASSWORD`| Keystore (store) password |
| `KEY_ALIAS`        | Key alias |
| `KEY_PASSWORD`     | Key password |

### Google Play publishing (new)

| Secret | Purpose |
|--------|---------|
| `PLAY_SERVICE_ACCOUNT_JSON` | Full JSON key of a Google Cloud service account with Play release access |

### Build config (already used)

`GOOGLE_SERVICES_API_KEY`, `PROJECT_ID`, `CLIENT_ID`, `ARCORE_API_KEY`,
and `GH_TOKEN` (for the GitHub Packages Maven repo). See `release-apk.yml`.

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
   uploaded by hand in the Play Console (Internal testing is fine). Run the
   workflow with `publish = false`, download the `graffitixr-release-aab`
   artifact, and upload it in the console. After that first manual upload, the
   workflow can publish subsequent builds with `publish = true`.

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
- **Third‑party SDKs that may collect data:** the **Meta Wearables (mwdat)**
  integration and Google Play Services / ARCore are present. Review their data
  practices and disclose anything they collect on your behalf.
- **Permissions to justify:** `CAMERA` (core), plus optional `BLUETOOTH*`,
  `ACCESS_*_LOCATION`, Wi‑Fi, and `INTERNET` — all already marked as optional
  hardware features in the manifest so they don't filter the listing.
- The network security config (`@xml/network_security_config`) sets
  `cleartextTrafficPermitted="false"` globally (and for `api.github.com`), so no
  cleartext HTTP is allowed — good for the security/data‑safety posture.

Keep the Data safety declaration in sync whenever an SDK or permission changes.
