# Data Formats Specification

This document defines the storage formats for GraffitiXR artifacts.

## 1. Project File (`.gxr`)
A serialized `GraffitiProject` JSON manifest file describing a user project via `kotlinx.serialization`.

## 2. SLAM Map (`.bin`)
A custom binary format for Persistent Voxel Memory data. Little Endian.

| Byte Offset | Type | Description |
| :--- | :--- | :--- |
| `0x00` | `char[4]` | Magic Header `"GXRM"` |
| `0x04` | `int32` | Version (2) |
| `0x08` | `int32` | Splat Count (N) |
| `0x10` | `byte[]` | Splat Payload (N × 44 bytes) |

**Splat Structure (44 bytes aligned):**
*   `float x, y, z` (Position in World Space)
*   `float r, g, b, a` (Color & Opacity)
*   `float nx, ny, nz` (Surface Normal)
*   `float confidence` (Quality score 0.0 to 1.0)

---
*Documentation updated on 2026-04-24 during Persistent Voxel Memory and Pocket-Ready recovery implementation.*
