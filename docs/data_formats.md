# Data Formats Specification

This document defines the storage formats for GraffitiXR artifacts.

## 1. Project File (`.gxr`)
A JSON manifest file describing a user project.

```json
{
  "version": 1,
  "id": "uuid-v4",
  "created_at": 1715420000000,
  "mode": "STATIC",
  "background": {
    "type": "IMAGE", // or "3D_MAP"
    "source": "uri/to```json/background.jpg"
  },
  "layers": [
    {
      "id": "layer-1",
      "name": "Outline",
      "source": "layers/layer-1.png",
      "transform": {
        "scale": 1.0,
        "offset": { "x": 0.0, "y": 0.0 },
        "rotation": { "x": 0.0, "y": 0.0, "z": 45.0 }
      },
      "properties": {
        "opacity": 0.8,
        "blend_mode": "MULTIPLY"
      }
    }
  ]
}
```

2. SLAM Map (.map)
A custom binary format for SphereSLAM data. Little Endian.

Offset,Type,Description
0x00,char[4],"Magic Header ""GXRM"""
0x04,int32,Version (1)
0x08,int32,Point Count (N)
0x0C,int32,Keyframe Count (K)
0x10,byte[],Point Data (N×24 bytes)
...,byte[],Keyframe Data (K×64 bytes)


Point Structure (24 bytes):

float x, y, z (Position)

float r, g, b (Color normalized 0-1)

Keyframe Structure (64 bytes):

float[16] (ModelMatrix - Column Major)

3. Splat File (.splat)
Standard Gaussian Splatting format (similar to .ply but optimized).

Component,Type
Position,float[3]
Scale,float[3] (Log scale)
Color,uint8[4] (RGBA)
Rotation,"uint8[4] (Quaternion, mapped 0-255)"


---

### 3. `docs/ARCHITECTURE.md` (Update)
**Status:** Updated the Data Layer section to reference the new formats and 3D structure.

```markdown
# Architecture

## ... (Previous Sections)

## 3D Subsystem Architecture

The 3D Mockup mode introduces a specific set of interactions between `:feature:editor` and `:feature:ar`.

### The `GsViewer` Component
Located in `:feature:editor`, this is an `AndroidView` wrapping a `GLSurfaceView`.
* **Renderer:** `GaussianSplatRenderer` (Custom implementation).
* **Input:** Receives `EditorUiState.mapPath`.
* **Interaction:** Touches are raycast against the point cloud to determine surface normals for "sticking" graffiti to the 3D mesh.

### The `SlamManager`
Located in `:feature:ar`.
* **Lifecycle:** Bound to the ARCore Session in `MappingScreen`.
* **Thread Safety:** Accumulates points on a background thread (`Dispatchers.IO`).
* **Export:** converting the live `HashMap<Block, Point[]>` spatial index into the flat `.map` binary format described in `DATA_FORMATS.md`.

## Memory Management

* **Bitmap Layers:** Loaded via Coil with aggressive memory caching and downsampling for high-res layers.
* **Point Clouds:** Can reach 100k+ points. We use `ByteBuffer.allocateDirect()` for native heap storage to avoid OOM in the Java heap.
