# Data Layer Documentation

This document describes how GraffitiXR handles data persistence, state management, and
serialization. For the exact on-disk/archive byte layout, see `docs/data_formats.md` — this
document stays at the architecture level and defers to that one for format specifics so the two
can't drift out of sync with each other again (see "History" below).

## 1. Core Data Models

All of the following live in `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/`
(not `app/`, despite what an earlier version of this document said — the UI/data model layer was
extracted into `core/common` so `core/data`, `core/domain`, and every feature module can depend on
it without depending on `app/`).

### `GraffitiProject` (Serializable)
- **Location:** `GraffitiProject.kt`
- **Purpose:** The single persisted manifest for a project — everything `ProjectManager` reads and
  writes as `project.json`. Roughly 30 fields; highlights:
  - `design: OverlayLayer?` — the one overlay image a project places (multilayer editing moved to
    a companion design app; see `layers` below).
  - `layers: List<OverlayLayer>` — read-only migration path for projects saved when this app still
    held a layer list; collapsed into `design` on load (`ProjectManager.migrateInMemory`) and never
    written again.
  - `targetImageUris`, `fingerprint`, `wallFeatureMap`, `captureEnvironment`,
    `fingerprintIntrinsics`/`fingerprintAnchor`/`fingerprintViewMatrix` — everything captured about
    the AR target: the marks descriptor blob, the passively-built wide-area feature map, and the
    device/camera state at capture time (see `CaptureEnvironment.kt`'s KDoc for why each group is
    independently optional).
  - `gpsData`, `sensorData`, `calibrationSnapshots`, `drawingPaths`, `refinementPaths`,
    `modeAdjustments`, `railExpansion` — contextual/UI state that rides along with the project so it
    restores exactly as the user left it.

### `OverlayLayer` (Serializable)
- **Location:** `OverlayLayer.kt`
- **Purpose:** A single visual layer (image + properties): `uri`, transform (scale, rotation X/Y/Z,
  offset), tone adjustments (opacity, brightness, contrast, saturation, color balance), and
  `blendMode`.

### `Fingerprint`
- **Location:** `Fingerprint.kt`
- **Purpose:** The OpenCV ORB/SuperPoint feature descriptors that identify an AR target's marks
  region. `descriptorsData` (raw byte blob) plus `descriptorsRows`/`descriptorsCols`/
  `descriptorsType` to reconstruct an OpenCV `Mat` on the native side.

### `WallFeatureMap`
- **Location:** `WallFeatureMap.kt`
- **Purpose:** The passively-built, confidence-weighted feature map of the wall surrounding the
  marks fingerprint — the wide-area relocalization backbone (see `docs/RELOC_MAP_DESIGN.md`).
  Stored as primitive arrays (`FloatArray`/`ByteArray`/`IntArray`), not boxed lists, for the same
  reason the native side hands them to JNI without copying. Every array-typed field is bounds- and
  overflow-checked in its `init` block (all size relations computed in `Long`, not `Int`, so a
  crafted/corrupt row count can't wrap a multiplication and slip past the check) — the same
  defensive posture as the native-side deserialization it mirrors
  (`core/nativebridge/.../GraffitiJNI.cpp`'s `nativeRestoreWallFeatureMap`).

## 2. Serialization Strategy

The app uses `kotlinx.serialization` with custom serializers (`core/common/.../serialization/
Serializers.kt`) for Android/Compose types that don't have one built in:

- **`UriSerializer`** — `Uri` <-> string, via `Uri.toString()`/`Uri.parse()`.
- **`OffsetSerializer`** — Compose `Offset` <-> string; sanitizes `Unspecified`/non-finite values to
  `Offset.Zero` on encode so a bad in-memory value can never corrupt a save.
- **`BlendModeSerializer`** — Compose `BlendMode` <-> its name.

Every `Json` instance that serializes a `GraffitiProject` (`ProjectManager`'s, in particular) sets
`allowSpecialFloatingPointValues = true`. This is required, not cosmetic: several fields use `NaN`
as a documented "not measured" sentinel (`CaptureEnvironment`'s `LocationFix.bearingDeg`,
`DeviceAttitude.azimuthDeg`, and others), and those sentinels are genuinely produced by an ordinary
capture — a GPS fix with no bearing because the device isn't moving. Without the flag,
`Json.encodeToString` throws on encountering the first `NaN` field instead of persisting the
project.

## 3. Project Management

### `ProjectManager`
- **Location:** `core/data/src/main/java/com/hereliesaz/graffitixr/data/ProjectManager.kt`
- **Function:** Owns all project file I/O — `project.json`, target/thumbnail PNGs, and `.gxr`
  export/import/co-op transfer. See `docs/data_formats.md` for the exact layout.
- **Notable behaviors:**
  - **Atomic writes.** `project.json` is written to a sibling `.tmp` file and renamed over the
    target, so a crash mid-write can't leave a truncated, unparseable manifest.
  - **In-memory migration.** Old-shape projects (a `layers` list instead of `design`, or the older
    `legacyVisuals` grouping) are migrated on every load (`migrateInMemory`), purely in memory —
    nothing re-persists the migrated shape, so a project keeps its legacy fields in the JSON file
    until the next routine save overwrites them for some other reason. This is deliberate: nothing
    in the app needs a migration persisted eagerly, and re-migrating on read is cheap (a couple of
    reference/equality checks).
  - **Target-image pruning.** Captured target images accumulate under a per-project cap
    (`MAX_TARGET_IMAGES`); the oldest are pruned — and their files deleted — once a capture pushes
    the count past it, so repeated re-captures on a long-lived project can't grow storage without
    bound.
  - **Hardened import/co-op-receive.** Both untrusted-archive paths (`importProjectFromUri`,
    `loadAsSpectator`) stream each ZIP entry straight to a temp file (never buffered whole in
    memory — a `ByteArrayOutputStream` that doubles its backing array could OOM well under the size
    cap before the cap is ever checked), enforce a cumulative decompressed-size cap
    (`MAX_IMPORT_BYTES`), reject path-traversal ("Zip-Slip") entry names, validate the archive's
    project id before using it as a path segment, and clean up every temp file — including a
    superseded one on a duplicate entry name, and on any exception mid-extraction.

### `ProjectRepository` / `ProjectRepositoryImpl`
- **Location:** `core/domain/.../repository/ProjectRepository.kt` (interface),
  `core/data/.../repository/ProjectRepositoryImpl.kt` (implementation).
- **Function:** The single source of truth for `currentProject` (a `StateFlow`) and the project
  list, sitting between the UI/viewmodel layer and `ProjectManager`.
- **The atomic-transform pattern.** `updateProject(transform: (GraffitiProject) -> GraffitiProject)`
  is how every writer that might run concurrently with another must persist a change: it applies
  `transform` to the CURRENT in-memory project via `MutableStateFlow.updateAndGet` (a lock-free
  CAS loop — safe under real concurrent callers, not just sequential ones), then persists the
  LATEST merged state (not necessarily this call's own locally-computed value) under a mutex, so a
  concurrent transform's disk write can never be overwritten by a staler one. Three writers rely on
  this to coexist safely: the editor's design-layer save, AR's wall-feature-map save, and target
  capture's fingerprint/environment save — each transform touches only the fields that writer owns.
  A writer that instead calls `ProjectManager.saveProject` directly with a whole-object snapshot
  bypasses this entirely and can silently clobber (or be clobbered by) one of the other two.

### File Storage
- **Cache:** Temporary files during import/co-op extraction live in `context.cacheDir` and are
  cleaned up once extraction completes (success, failure, or abort all clean up their own temp
  files).
- **Persistence:** Saved projects live in `context.filesDir/projects/{projectId}/`.
- **Export / co-op transfer:** A `.gxr` ZIP archive — see `docs/data_formats.md`.

## History

An earlier version of this document described `ProjectData`/`UiState` living under `app/`, and
carried a duplicate (and internally inconsistent with `docs/data_formats.md`) `.gxr` archive
section describing files and a binary voxel-map format that no code has ever written. Both have
been corrected: the model layer now lives in `core/common` as described above, and the archive
format's single source of truth is `docs/data_formats.md`.
