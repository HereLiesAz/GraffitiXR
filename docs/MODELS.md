# Bundled ML models — provenance and licenses

The ONNX models shipped in `core/nativebridge/src/main/assets/` and where their weights come from.
Third-party weights keep their upstream license (see `docs/LICENSING.md`, "Bundled third parties").

| Asset | Weights | License | Notes |
|---|---|---|---|
| `superpoint.onnx` | [rpautrat/SuperPoint](https://github.com/rpautrat/SuperPoint) `weights/superpoint_v6_from_tf.pth` (PyTorch conversion of the authors' TensorFlow `sp_v6` model) | MIT, © 2018 Paul-Edouard Sarlin & Rémi Pautrat — full text in `core/nativebridge/src/main/assets/superpoint.LICENSE.txt` | Keypoints + 256-d descriptors for fingerprinting and relocalization (`SuperPointDetector.cpp`). Input `image [1,1,H,W]` (dynamic H/W), outputs `semi [1,65,H/8,W/8]`, `desc [1,256,H/8,W/8]`. |
| `zerodce.onnx` | Trained independently by the GraffitiXR author | Same as the app (PolyForm Noncommercial 1.0.0, see `/LICENSE`) | Low-light enhancement before feature detection (`LowLightEnhancer.cpp`). |
| `distortion_head.onnx` | See `docs/DISTORTION_HEAD.md` | — | Not covered by this note yet. |

## Corrections

- `superpoint.onnx` is **not** Magic Leap's `SuperPointPretrainedNetwork` (`superpoint_v1.pth`, a
  non-commercial license). Earlier docs and `scripts/convert_superpoint.py` said it was. That script
  targets the Magic Leap checkpoint and layer layout, cannot load the rpautrat checkpoint, and did not
  produce the shipped asset; it is disabled until a corrected export script for the rpautrat
  checkpoint replaces it.
- When redistributing the app or the asset, keep `superpoint.LICENSE.txt` with it (the MIT notice
  must accompany copies of the weights).
