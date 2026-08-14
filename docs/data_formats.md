# Data Formats Specification

This document defines the on-disk and wire storage formats GraffitiXR actually produces, as
implemented by `ProjectManager` (`core/data/src/main/java/com/hereliesaz/graffitixr/data/ProjectManager.kt`).
It replaces an earlier version that described a binary voxel/splat map format and a `.gxr` file
layout that no code in this repository has ever written — see "History" at the bottom.

## 1. On-disk project layout

A project lives at `context.filesDir/projects/<projectId>/` as a plain directory, not an archive.
Its contents, all produced by `ProjectManager`:

| File | Written by | Contents |
| :--- | :--- | :--- |
| `project.json` | `ProjectManager.saveProject` | The full `GraffitiProject` (see below), kotlinx.serialization JSON, pretty-printed. |
| `thumbnail.png` | `saveProject` (when a thumbnail bitmap is passed) | PNG, quality 80. |
| `target_<unique>.png` | `saveProject` / `ProjectManager.appendTargetImage` | One PNG per captured target image (quality 100). Filenames are unique per file (`File.createTempFile`), not sequentially numbered — nothing round-trips the filename itself, only the URI stored in `project.json`. |
| arbitrary filenames | `ProjectRepository.saveArtifact` | Design-layer image exports and other editor-written artifacts (e.g. `feature/editor`'s `EditorViewModel`), written as raw bytes under the same project directory. |

There is **no separate binary map/voxel/splat file of any kind**. The persistent wall-feature map
(`WallFeatureMap` — points, ORB/SuperPoint descriptor blob, confidence, anchor, intrinsics) lives
entirely as fields inside `project.json`, alongside everything else (see §2). Likewise the marks
`Fingerprint`'s descriptor blob, GPS/sensor data, and `CaptureEnvironment` are all just fields of
the one JSON document — nothing is broken out into its own file format.

Writes to `project.json` are atomic: `ProjectManager.atomicWriteText` writes to a sibling `.tmp`
file and renames it over the target, so a crash mid-write can never leave a truncated, unparseable
`project.json`.

## 2. The `.gxr` archive (export / import / co-op transfer)

A `.gxr` file is a **plain ZIP archive** (`java.util.zip.ZipOutputStream`/`ZipInputStream`, no
custom container, no magic header) whose entries are exactly the files in a project directory,
zipped flat into the archive root — `ProjectManager.zipFolder` strips the project-id path segment,
so the ZIP root directly contains `project.json`, `thumbnail.png`, `target_*.png`, and any
artifact files, with no top-level folder wrapping them.

- **Export** (`ProjectManager.exportProjectToUri`): zips the project directory as-is to a
  user-chosen URI.
- **Import** (`ProjectManager.importProjectFromUri`): the archive is untrusted — it may come from
  anywhere on the user's filesystem. Each entry is size-capped (512 MiB cumulative decompressed,
  `MAX_IMPORT_BYTES`) and streamed straight to a temp file (never fully buffered in memory) so a
  zip bomb can't exhaust the heap before the cap is checked; entry names are validated against
  path traversal ("Zip-Slip") before being written into the destination project directory; and the
  decoded `project.json`'s `id` field is validated (`isSafeProjectId`) before it's used as a path
  segment.
- **Co-op bulk transfer** (`ProjectManager.serializeCurrentProject` / `loadAsSpectator`): the exact
  same ZIP format, sent as raw bytes over the co-op session instead of through a `Uri`, with the
  same streaming/cap/Zip-Slip hardening applied on the receiving (spectator) end — the wire is just
  as untrusted as an imported file.

## 3. `project.json` — the `GraffitiProject` manifest

A `kotlinx.serialization` JSON encoding of `GraffitiProject`
(`core/common/.../model/GraffitiProject.kt`), with `ignoreUnknownKeys = true` (old files with
since-removed fields still load) and `encodeDefaults = true` (every field is always written, so
`ignoreUnknownKeys` on the READ side is what actually carries backward compatibility — a field
missing from an old file just uses its Kotlin default). Notably:

- `allowSpecialFloatingPointValues = true` is set on this `Json` instance specifically because
  several fields — `CaptureEnvironment`'s attitude/location groups in particular — use `NaN` as a
  documented "not measured" sentinel (e.g. `LocationFix.bearingDeg`, `DeviceAttitude.azimuthDeg`),
  and that sentinel is genuinely produced at runtime by an ordinary capture (GPS fix, no bearing —
  a stationary user). Without the flag, `Json.encodeToString` throws on a `NaN` field.
- URIs (`backgroundImageUri`, `targetImageUris`, etc.) are strings via `UriSerializer`
  (`Uri.toString()` / `Uri.parse()`), always local `file://` paths under the project directory.
- `fingerprint: Fingerprint?` carries the marks-region ORB/SuperPoint descriptor blob as a
  base64-ish byte array field (kotlinx.serialization's default `ByteArray` encoding), plus
  `descriptorsRows`/`descriptorsCols`/`descriptorsType` to reconstruct an OpenCV `Mat`.
- `wallFeatureMap: WallFeatureMap?` carries the passively-built wide-area feature map the same
  way — flat `FloatArray`/`ByteArray`/`IntArray` fields, not a separate file (see §1).
- `captureEnvironment: CaptureEnvironment?` carries device attitude, ARCore poses, frame
  orientation, and a location fix at capture time (all independently optional/nullable — see the
  KDoc on `CaptureEnvironment` for why).

## History

An earlier version of this document (and of `docs/data_layer.md`) described a `.gxr` archive
containing a `meta.json`/`model.map`/`target.fingerprint`/`reference.png` split, with `model.map`
specified as a custom binary "Persistent Voxel Memory" surfel dump behind a magic header — the two
documents even disagreed with each other on what that magic number was (`"GXR1"` vs. `"GXRM"`).
No code in this repository has ever written that format; the actual, and only, persistence format
is the one described above. That speculative section has been removed rather than corrected in
place, since nothing in it was ever implemented.
