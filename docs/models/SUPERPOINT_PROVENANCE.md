# superpoint.onnx: provenance

**File:** `superpoint.onnx`, sha256 `a95d3b7ae1e21833e5eb5cc7e7a99b07b04a9be0ed628f173171d82ec92a7107` (5,236,740 bytes).
It replaces sha256 `f78be883b40560759e12ac26d6ad9e63730053576c9ac2d7b5d77105e29f7ba9`, which shipped in
GraffitiXR `core/nativebridge/src/main/assets/` and SphereSLAM `models/src/main/assets/`.

## Source
- Repo: https://github.com/rpautrat/SuperPoint (MIT, Copyright (c) 2018 Paul-Edouard Sarlin & Rémi Pautrat; see `superpoint.LICENSE.txt`)
- Model code: `superpoint_pytorch.py` at commit `ccf30088721dba97a9612d1bdd9b8d1bb49ac642` (2024-06-13). The file is byte-identical at HEAD `1411bbd68c50163555d39c1b26e9e046ebd48f27` (2025-05-05). sha256 `c06e3b0a…f365`
- Weights: `weights/superpoint_v6_from_tf.pth`, added in `8b8e9e7e3ce20eb54ab1c83a51619873565e8b28` and unchanged since. sha256 `cd5d19a5061848e248c17728878ea166b66512076d43c77dbcf27f4a88a56084`
- License: `LICENSE.txt` at `1411bbd`, sha256 `c103865860109fdb1111c2ddf9281ebe9b94aea26dbeac1d033d6de34fcebbe1`

## Why the old file was replaced
The old file used the same rpautrat weights, but its graph was wrong in three ways:
- It folded each BatchNorm into the conv before it (conv→BN→ReLU). The reference applies **conv→ReLU→BN**, so BN cannot be folded through the ReLU.
- It added a ReLU on the `semi` logits.
- It added a ReLU on the descriptor before the L2 normalisation.

Compared with the reference on real images, its descriptors have a mean cosine of about −0.17, and its logits are off by up to about 2.7e3.

## Contract (unchanged from the old file)
- input `image`: [1,1,H,W] float32, grayscale /255. Dynamic H and W, multiples of 8.
- output `semi`: [1,65,H/8,W/8] raw logits. The caller does the softmax.
- output `desc`: [1,256,H/8,W/8], L2-normalised over channels.
- ONNX opset 12, IR 7. Metadata props record the source, commit, weights sha256 and license.

The graph is Conv/Relu/BatchNormalization/MaxPool followed by ReduceL2→Clip(1e-12)→Div. It is exactly the reference
`backbone → detector` and `normalize(descriptor(backbone))`.

## Export
~~~
python -I scripts/export_superpoint_rpautrat.py --model-py superpoint_pytorch.py \
    --weights superpoint_v6_from_tf.pth --out superpoint.onnx
~~~
The export ran with torch 2.14.1+cpu (TorchScript exporter, `dynamo=False`) and onnx 1.23.2. Re-running it produces the same file, byte for byte.

## Verification vs PyTorch reference (`scripts/verify.py`)
The ONNX outputs were compared with the reference PyTorch forward on two kinds of input:
- 4 random inputs: 480×640, 256×256, 240×320 and 120×200
- 6 real HPatches images

Worst max-abs differences:

| runtime | semi | desc |
|---|---|---|
| onnxruntime 1.31 | 3.8e-5 | 4.3e-7 |
| OpenCV DNN 5.0 (the GraffitiXR runtime) | 5.7e-5 | 1.1e-6 |

All are within the 1e-4 limit, so the check **passes**.

## Matching benchmark (`scripts/eval_hpatches.py`)
- **Data:** HPatches sequences, 108 of the 116 sequences: all 57 illumination and 51 of 59 viewpoint. The missing 8 are v_wapping…v_yuri, which were not extracted because the disk was full. That gives 540 image pairs. Images were downscaled so the longer side is ≤640, and the homographies were rescaled to match.
- **Detection pipeline:** a Python replica of GraffitiXR `SuperPointDetector.cpp`. An image larger than 640×480 is resized to exactly 640×480; otherwise its sides are floored to multiples of 8 (INTER_AREA). Then /255, softmax, threshold 0.015, 9×9 strict NMS, 4 px border, top-500, bilinear descriptor sampling with renormalisation.
- **Matching:** BruteForce-L2 knn with Lowe ratio 0.75 (`kRelocLoweRatio`). Mutual nearest neighbour (MNN) is also reported.
- **Metrics:**
  - repeatability is measured at 3 px
  - MMA means mean matching accuracy
  - H-acc means homography accuracy: RANSAC 3 px, mean corner error at most N px

App pipeline (threshold 0.015, ratio test):

| subset | model | kpts | repeat. | matches | MMA@1 | MMA@3 | MMA@5 | H-acc@1 | H-acc@3 | H-acc@5 |
|---|---|---|---|---|---|---|---|---|---|---|
| all (540) | old | 16.9 | 0.118 | 4.9 | 0.113 | 0.117 | 0.118 | 0.052 | 0.056 | 0.056 |
| all (540) | **new** | 487.8 | **0.542** | 127.7 | **0.533** | **0.807** | **0.854** | **0.431** | **0.728** | **0.806** |
| illum (285) | old | 21.4 | 0.217 | 7.7 | 0.213 | 0.219 | 0.219 | 0.098 | 0.105 | 0.105 |
| illum (285) | **new** | 486.4 | 0.566 | 159.8 | 0.582 | 0.853 | 0.903 | 0.611 | 0.888 | 0.919 |
| view (255) | old | 12.0 | 0.007 | 1.9 | 0.000 | 0.004 | 0.005 | 0.000 | 0.000 | 0.000 |
| view (255) | **new** | 489.3 | 0.516 | 91.9 | 0.479 | 0.755 | 0.801 | 0.231 | 0.549 | 0.678 |

With MNN matching instead of the ratio test, the new model reaches H-acc@1/3/5 of 0.526/0.833/0.893, against 0.044/0.054/0.054 for the old one.

Lowering the threshold to 0.001 does not rescue the old model. It then finds 74 keypoints per image, with MMA@3 of 0.157 and H-acc@3 of 0.076. The new model at that threshold scores 0.804 and 0.722, so the old file's problem is the network, not the threshold.

## CPU runtime (`scripts/bench.py`, interleaved medians on a shared and loaded 4-vCPU container)

| input | runtime | old | new |
|---|---|---|---|
| 480×640 | ORT, 1 thread | 397 ms | 444 ms |
| 480×640 | ORT, 4 threads | 407 ms | 426 ms |
| 480×640 | OpenCV DNN, 4 threads | 484 ms | 521 ms |
| 256×256 | ORT, 4 threads | 31 ms | 33 ms |
| 256×256 | OpenCV DNN, 4 threads | 66 ms | 71 ms |

The new model is about 5–10% slower because of the 10 unfolded BatchNorm layers. The absolute numbers are noisy, since other jobs were using the machine.

## Compatibility
The new descriptors differ from the old ones (mean cosine about −0.17), so SuperPoint data saved with the old model is stale. That means every `descriptorsType == 5` (CV_32F) blob in GraffitiXR `project.json`, in these fields:
- `doodle`
- `sphereSlamFingerprint`
- `wallFeatureMap`
- `sphereSlamWallFeatureMap`
- `paintMarks`

Any SphereSLAM `WallFeatureMap` snapshot built from SuperPoint is stale for the same reason.

ORB (CV_8U) blobs are not affected. The owner has said backward compatibility is not needed, so no migration is provided.
