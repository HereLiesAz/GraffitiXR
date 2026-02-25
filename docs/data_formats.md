# Data Formats Specification

This document defines the storage formats for GraffitiXR artifacts.

## 1. Project File (`.gxr`)
A serialized `GraffitiProject` JSON manifest file describing a user project via `kotlinx.serialization`.

## 2. SLAM Map (`.bin`)
A custom binary format for MobileGS Voxel Hash data. Little Endian.

| Byte Offset | Type | Description |
| :--- | :--- | :--- |
| `0x00` | `char[4]` | Magic Header `"GXRM"` |
| `0x04` | `int32` | Version (1) |
| `0x08` | `int32` | Splat Count (N) |
| `0x0C` | `int32` | Keyframe Count (K) |
| `0x10` | `byte[]` | Splat Payload (N × 32 bytes) |
| `...` | `float[16]` | Tracked Alignment Matrix (64 bytes) |

**Splat Structure (32 bytes aligned):**
*   `float x, y, z` (Position)
*   `float r, g, b, a` (Color & Opacity)
*   `float confidence` (Observations)