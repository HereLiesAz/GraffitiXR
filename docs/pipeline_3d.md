# 3D Pipeline Specification: SphereSLAM & Gaussian Splatting

This document details the implementation of the environment mapping and 3D visualization subsystems.

## 1. SphereSLAM (Simultaneous Localization and Mapping)

**Module:** `:feature:ar`
**Key Class:** `SlamManager`

Unlike full-scale photogrammetry (which requires post-processing), SphereSLAM focuses on rapid, sparse point cloud accumulation for immediate on-device visualization.

### A. Tracking & Keyframing
The system piggybacks on the ARCore `Session` for visual odometry (VO). We do not perform visual-inertial odometry (VIO) manually; we trust the `arFrame.camera.pose`.

**Keyframe Heuristic:**
A new keyframe is captured only if the delta from the last keyframe exceeds thresholds:
* **Translation:** $\Delta T > 0.1$ meters (10cm)
* **Rotation:** $\Delta R > 10.0$ degrees
* **Quality:** `arFrame.camera.trackingState == TRACKING`

### B. Point Cloud Accumulation
[Image of SLAM mapping process diagram]
1.  **Acquisition:** On every frame, `frame.acquirePointCloud()` is called.
2.  **Filtering:** Points with a confidence score $< 0.3$ are discarded.
3.  **World Mapping:** Points are transformed from Camera Space to World Space using the current Frame Pose.
    $$P_{world} = T_{camera \to world} \times P_{local}$$
4.  **Deduplication:** We use a basic Voxel Grid filter (leaf size 5cm) to merge redundant points and prevent memory bloat during long scans.

### C. Map Serialization
The map is saved as a binary definition containing:
1.  **Sparse Cloud:** $X, Y, Z, R, G, B$ (Color extracted from camera image at point projection).
2.  **Keyframe Poses:** $T_x, T_y, T_z, Q_x, Q_y, Q_z, Q_w$.
3.  **Reference Images:** JPEG buffers linked to Keyframe IDs.

---

## 2. Gaussian Splatting Visualization

**Module:** `:feature:editor`
**Key Class:** `GsViewer`

We use a simplified 3D Gaussian Splatting rasterizer adapted for mobile OpenGL ES 3.0.

### A. Data Loading
The viewer accepts a `.splat` or custom `.map` file.
* **Parsing:** Binary data is mapped to a `FloatBuffer` containing Center ($xyz$), Covariance (encoded as scale/rotation), Color ($rgba$), and Opacity.

### B. Sorting (The Bottleneck)
Gaussian Splatting requires strict back-to-front rendering for alpha blending to work.
* **Algorithm:** Radix Sort (GPU-based via Compute Shader if supported, else CPU parallel sort).
* **Frequency:** Resorting occurs every time the camera view vector changes by $> 5$ degrees.

### C. Rasterization (Vertex Shader)
Instead of processing full 3D Gaussians, we render screen-aligned quads (billboards).
1.  **Projection:** The 3D mean is projected to 2D screen space.
2.  **Covariance Construction:** We reconstruct the 3D covariance matrix $\Sigma$ from the stored scale ($S$) and rotation ($R$) quaternions:
    $$\Sigma = R S S^T R^T$$
3.  **EWA Splatting:** The 3D covariance is projected to 2D ray space ($J W \Sigma W^T J^T$) to determine the quad's extent and axes.

### D. Rendering (Fragment Shader)
[Image of Gaussian Splatting rasterization pipeline]
The shader computes the gaussian falloff:
$$\alpha = e^{-\frac{1}{2} (x^T \Sigma^{-1} x)}$$
If $\alpha < \text{threshold}$, the fragment is discarded.

---

## 3. Projection & Occlusion Logic

When in **3D Mockup Mode**, the Editor layers are no longer just 2D bitmaps.

1.  **Projection:** The user's 2D canvas is projected as a "decal" into the 3D scene using a Projective Texture Mapping technique.
2.  **Occlusion:**
    * The `GsViewer` renders the depth of the splats into a Depth Texture.
    * The Graffiti Layers are rendered with a depth test enabled.
    * **Result:** If a splat (e.g., a pillar) is in front of the projected graffiti wall, the graffiti is correctly occluded.