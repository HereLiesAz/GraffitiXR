
# Architecture & Rendering Pipeline

## Module Architecture (Clean Architecture)
The application is structured into three layers:
1.  **App Layer (`:app`)**: Entry point, Navigation Graph, Dependency Injection.
2.  **Feature Layer**:
    *   `:feature:ar` - AR Rendering, Mapping, Camera.
    *   `:feature:editor` - Image manipulation, Trace/Mockup UI.
    *   `:feature:dashboard` - Project management, Settings.
3.  **Core Layer**:
    *   `:core:domain` - Pure data models, Repository interfaces.
    *   `:core:data` - Repository implementations, File I/O.
    *   `:core:common` - Universal utilities.
    *   `:core:design` - UI Components, Theme.
    *   `:core:native` - Native C++ Engine (MobileGS).

## System Diagram

```mermaid
graph TD
    A[ARCore Camera/Depth] -->|Frame Update| B(ArRenderer.kt)
    B -->|Main Thread| C{SlamManager.kt}
    
    C -->|Texture Data| D[GraffitiJNI (C++)]
    C -->|Depth Data| D
    
    subgraph Native Heap
        D -->|JNI Call| E[MobileGS.cpp]
        E -->|Voxel Hashing| F[Point Cloud Storage]
        E -->|Sorting Thread| G[Depth Sorter]
        G -->|Sorted Index| E
    end
    
    E -->|glDrawArraysInstanced| H[OpenGL ES 3.0 Surface]
    
    subgraph ViewModels
        VM_AR[ArViewModel]
        VM_ED[EditorViewModel]
        VM_DB[DashboardViewModel]
    end
    
    VM_AR -- Updates --> B
    B -- Status --> VM_AR
    
    VM_ED -- Modifies --> Repo[ProjectRepository]
    Repo -- Observes --> VM_AR
    Repo -- Observes --> VM_ED
    
    VM_ED -->|UI State| J[Jetpack Compose / AzNavRail]
    J -->|Overlay| H
```
Algorithm Details
Voxel Hashing: Space is divided into 5mm cubes.

Duplication as Signal:

Most SLAM systems treat duplication as inefficiency.

GraffitiXR treats duplication as Verification.

If a point lands in a voxel that already has a splat, we pull the splat towards the new point (Rolling Average) and boost its opacity/confidence.

The "Ghost" Effect:

New points are invisible (Alpha = 0).

As confidence grows, Alpha increases.

The wall "fades in" as the user scans it multiple times.

3. Data Persistence (.gxr)
   The Project Bundle is a self-contained ZIP file that acts as the "save state" for the wall.

target.fingerprint (The Anchor)
Library: OpenCV.

Algorithm: ORB (Oriented FAST and Rotated BRIEF).

Usage:

Save: When the user saves, we extract ORB features from the current camera view.

Load: When the user returns, the app runs feature matching on the live feed.

Lock: When > 20 inliers are found (Homography match), we calculate the transform matrix and snap the world.map to the physical wall.

world.map (The Volumetric Data)
Format: Custom Binary.

Content: A direct dump of the high-confidence Gaussian Splats.

Structure:

int32 version

int32 splat_count

struct Splat { float x,y,z; float r,g,b,a; uint8 confidence; }

meta.json (The Context)
Stores:

GPS Coordinates (Lat/Long) for map placement.

Compass Heading (IMU).

Image Edits (Opacity, Blend Mode, crop rect).

The "Lazy Grid" configuration (Plane normal/center).

4. Rendering Pipeline
   The rendering engine is designed to handle 50k+ splats on a mobile GPU without choking the UI thread.

Instanced Rendering: * We do not upload geometry for every splat.

We hold ONE static Quad (4 vertices) in VRAM.

We use glDrawArraysInstanced to draw that quad thousands of times, using a Texture Buffer to pass the Position/Scale/Color data for each instance.

Sorting (The Bottleneck):

Gaussian Splatting requires strict Back-to-Front sorting for alpha blending to look correct.

Solution: A dedicated std::thread runs a Radix Sort on the CPU continuously.

Sync: mDataMutex protects the double-buffered index swap between the Sorter and the Renderer.

Save Sync: Saving is handled synchronously in C++ (saveModel), wrapped by Kotlin Coroutines (Dispatchers.IO) to avoid blocking the UI thread or causing race conditions with the renderer.