# Distortion Head — training pipeline

Offline, self-supervised training for the viewpoint/partial-aware relocalization head.
Design + integration plan: [`docs/DISTORTION_HEAD.md`](../../docs/DISTORTION_HEAD.md).

The head sits on top of the **frozen** SuperPoint already shipped in the app. It takes two
views — the live frame crop and the canonical fingerprint patch — and outputs the planar
distortion between them plus a confidence and a coverage (= painting-progress) signal.

## What it predicts

`distortion[13]` = `[corners(8), pose(3), matchability(1), coverage(1)]`
- `corners` — 4-corner offsets of the homography (the distortion), normalised by patch size.
- `pose` — `[tilt°, log2(scale), roll°]`, the interpretable distortion.
- `matchability` — is this the target, at this overlap (drives reject + fusion confidence).
- `coverage` — fraction of the target visible (drives `mPaintingProgress`).

## Setup

```bash
pip install torch opencv-python numpy
```

SuperPoint weights are auto-downloaded (same source as `scripts/convert_superpoint.py`) and
cached under `.cache/superpoint_v1.pth`.

## Smoke test (no data, procedural textures)

```bash
python3 scripts/distortion_head/superpoint_backbone.py   # backbone forward pass
python3 scripts/distortion_head/data.py                  # dataset sample shapes
python3 scripts/distortion_head/model.py                 # head shapes / param count
python3 scripts/distortion_head/train.py --steps 50 --batch 8 --epochs 1 --workers 0
```

## Real training

Point `--images` at a corpus of a few thousand wall/texture/mural photos (domain is not
critical — the backbone is frozen and the runtime target is unseen at train time):

```bash
python3 scripts/distortion_head/train.py --images /path/to/corpus --epochs 20 --batch 32
```

Validation reports corner error (px), tilt/log2-scale MAE, and matchability AUC on held-out
synthetic warps. Best checkpoint → `checkpoints/head_best.pth`.

## Export to ONNX (OpenCV DNN compatible)

```bash
python3 scripts/distortion_head/export_onnx.py \
    --head scripts/distortion_head/checkpoints/head_best.pth \
    --output core/nativebridge/src/main/assets/distortion_head.onnx
```

The exported graph is self-contained (frozen SuperPoint + head): native code feeds two gray
images and reads `distortion[13]`. The exporter also attempts an OpenCV-DNN reload as a
compatibility check.

## Next (device wiring — not in this scaffold)

See §5–6 of the spec: add the canonical patch to `Fingerprint`, run the head in
`relocThreadFunc` between SuperPoint and PnP, use the warp to *guide* matching / seed IPPE
(never to write the pose directly), and route `coverage → mPaintingProgress`.
