#!/usr/bin/env python3
"""
export_onnx.py — trained DistortionHead → opset-12 ONNX for OpenCV DNN.

Exports a self-contained graph (frozen SuperPoint + head) so the native side only feeds two
gray images and reads back distortion[13]:
    [0:8]=corners, [8:11]=pose(tilt_deg,log2_scale,roll_deg), [11]=matchability, [12]=coverage

Usage:
    python3 scripts/distortion_head/export_onnx.py \
        --head scripts/distortion_head/checkpoints/head_best.pth \
        --patch 256 --grid 32 \
        --output core/nativebridge/src/main/assets/distortion_head.onnx
"""

import argparse
import pathlib

import torch

from model import DistortionHead, ExportWrapper
from superpoint_backbone import FrozenSuperPoint


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--head", type=str, required=True, help="trained head .pth")
    ap.add_argument("--weights", type=str, default=None, help="SuperPoint .pth (optional)")
    ap.add_argument("--patch", type=int, default=256)
    ap.add_argument("--grid", type=int, default=32)
    ap.add_argument("--output", type=str,
                    default="core/nativebridge/src/main/assets/distortion_head.onnx")
    args = ap.parse_args()

    backbone = FrozenSuperPoint(args.weights).eval()
    head = DistortionHead(grid=args.grid).eval()
    head.load_state_dict(torch.load(args.head, map_location="cpu"))

    model = ExportWrapper(backbone, head, grid=args.grid).eval()

    P = args.patch
    dummy_cur = torch.zeros(1, 1, P, P)
    dummy_fp = torch.zeros(1, 1, P, P)

    out_path = pathlib.Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    torch.onnx.export(
        model, (dummy_cur, dummy_fp), str(out_path),
        opset_version=12,
        input_names=["image_cur", "image_fp"],
        output_names=["distortion"],
        dynamic_axes={"image_cur": {0: "B"}, "image_fp": {0: "B"}, "distortion": {0: "B"}},
    )
    size_kb = out_path.stat().st_size // 1024
    print(f"[✓] exported → {out_path} ({size_kb} KB)")
    print("    distortion[13] = [corners(8), pose(3), matchability(1), coverage(1)]")

    # Sanity: re-load with OpenCV DNN if available (the on-device runtime).
    try:
        import cv2
        net = cv2.dnn.readNetFromONNX(str(out_path))
        net.setInput(torch.zeros(1, 1, P, P).numpy(), "image_cur")
        net.setInput(torch.zeros(1, 1, P, P).numpy(), "image_fp")
        y = net.forward("distortion")
        print(f"[✓] OpenCV DNN load OK, output shape {y.shape}")
    except Exception as e:  # noqa: BLE001
        print(f"[!] OpenCV DNN check skipped/failed: {e}")


if __name__ == "__main__":
    main()
