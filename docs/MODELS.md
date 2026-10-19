# Bundled ML models — provenance and licenses

The ONNX models shipped in `core/nativebridge/src/main/assets/` and where their weights come from.
Third-party weights keep their upstream license (see `docs/LICENSING.md`, "Bundled third parties").

| Asset | Weights | License | Notes |
|---|---|---|---|
| `superpoint.onnx` | [rpautrat/SuperPoint](https://github.com/rpautrat/SuperPoint) `weights/superpoint_v6_from_tf.pth` (PyTorch conversion of the authors' TensorFlow `sp_v6` model) | MIT, © 2018 Paul-Edouard Sarlin & Rémi Pautrat — full text in `core/nativebridge/src/main/assets/superpoint.LICENSE.txt` | Keypoints + 256-d descriptors for doodling and relocalization (`SuperPointDetector.cpp`). Input `image [1,1,H,W]` (dynamic H/W), outputs `semi [1,65,H/8,W/8]`, `desc [1,256,H/8,W/8]`. |
| `zerodce.onnx` | Trained independently by the GraffitiXR author | Same as the app (PolyForm Noncommercial 1.0.0, see `/LICENSE`) | Low-light enhancement before feature detection (`LowLightEnhancer.cpp`). |
| `distortion_head.onnx` | See `docs/DISTORTION_HEAD.md` | — | Not covered by this note yet. |

## Corrections

- `superpoint.onnx` is **not** Magic Leap's `SuperPointPretrainedNetwork` (`superpoint_v1.pth`, a
  non-commercial license). Earlier docs and `scripts/convert_superpoint.py` said it was; that script
  targets the Magic Leap checkpoint and layer layout and stays disabled (it is kept only because the
  offline distortion-head training tools import it).
- The shipped asset is now exported by `scripts/export_superpoint_rpautrat.py`, which reproduces the
  rpautrat reference forward exactly (conv→ReLU→BN, no ReLU on `semi` or before the descriptor L2
  norm). The previous asset (sha256 `f78be883…`) folded BN through the ReLU and added two ReLUs; on
  HPatches it reached 5.6% homography accuracy @3 px against 72.8% for the corrected export. Full
  provenance, verification and benchmark: `docs/models/SUPERPOINT_PROVENANCE.md`.
- Bundled file: 5,236,740 B, sha256 `a95d3b7ae1e21833e5eb5cc7e7a99b07b04a9be0ed628f173171d82ec92a7107`.
- SuperPoint descriptors saved by the previous asset (`doodle`, `sphereSlamDoodle`,
  `wallFeatureMap`, `sphereSlamWallFeatureMap`, `paintMarks` in `project.json`) no longer match;
  recapture the target. There is no migration.
- `distortion_head.onnx` takes raw images and embeds its own backbone, so it is unaffected.
- When redistributing the app or the asset, keep `superpoint.LICENSE.txt` with it (the MIT notice
  must accompany copies of the weights).
