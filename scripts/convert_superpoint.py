#!/usr/bin/env python3
"""
convert_superpoint.py — Export SuperPoint weights → ONNX for GraffitiXR.

SuperPoint is the self-supervised feature detector from Magic Leap:
  https://github.com/magicleap/SuperPointPretrainedNetwork

Outputs:
  app/src/main/assets/superpoint.onnx

  Tensor outputs from the exported model:
    semi  [1, 65,  H/8, W/8]  — keypoint probability heatmap (raw logits)
    desc  [1, 256, H/8, W/8]  — dense descriptor map (L2-normalised)

  Post-processing (softmax NMS + bilinear descriptor sampling) is done in
  native C++ by SuperPointDetector.cpp.  The ONNX model is intentionally
  kept small and simple (raw heatmap format) for full OpenCV DNN compatibility.

Usage:
  pip install torch requests
  python3 scripts/convert_superpoint.py

  Or specify an alternate input size (must be multiples of 8):
  python3 scripts/convert_superpoint.py --height 360 --width 480

The exported model supports dynamic input sizes at runtime via dynamic axes.
"""

import sys
import pathlib
import argparse
import urllib.request

import torch
import torch.nn as nn


# Official Magic Leap pre-trained weights
WEIGHTS_URL = (
    "https://github.com/magicleap/SuperPointPretrainedNetwork"
    "/raw/master/superpoint_v1.pth"
)
WEIGHTS_CACHE = pathlib.Path(".cache") / "superpoint_v1.pth"


# ---------------------------------------------------------------------------
# Minimal SuperPoint network (encoder + detector head + descriptor head).
# Architecture matches the official Magic Leap implementation exactly so
# the pre-trained weights load without adaptation.
# ---------------------------------------------------------------------------

class SuperPointNet(nn.Module):
    """Outputs raw semi [1,65,H/8,W/8] and desc [1,256,H/8,W/8] tensors."""

    def __init__(self):
        super().__init__()
        c1, c2, c3, c4, d1 = 64, 64, 128, 128, 256
        self.relu = nn.ReLU(inplace=True)
        self.pool = nn.MaxPool2d(kernel_size=2, stride=2)

        # Shared encoder
        self.conv1a = nn.Conv2d(1, c1, 3, padding=1)
        self.conv1b = nn.Conv2d(c1, c1, 3, padding=1)
        self.conv2a = nn.Conv2d(c1, c2, 3, padding=1)
        self.conv2b = nn.Conv2d(c2, c2, 3, padding=1)
        self.conv3a = nn.Conv2d(c2, c3, 3, padding=1)
        self.conv3b = nn.Conv2d(c3, c3, 3, padding=1)
        self.conv4a = nn.Conv2d(c3, c4, 3, padding=1)
        self.conv4b = nn.Conv2d(c4, c4, 3, padding=1)

        # Detector head
        self.convPa = nn.Conv2d(c4, 256, 3, padding=1)
        self.convPb = nn.Conv2d(256, 65, 1)

        # Descriptor head
        self.convDa = nn.Conv2d(c4, 256, 3, padding=1)
        self.convDb = nn.Conv2d(256, d1, 1)

    def forward(self, x):
        x = self.relu(self.conv1a(x))
        x = self.relu(self.conv1b(x))
        x = self.pool(x)
        x = self.relu(self.conv2a(x))
        x = self.relu(self.conv2b(x))
        x = self.pool(x)
        x = self.relu(self.conv3a(x))
        x = self.relu(self.conv3b(x))
        x = self.pool(x)
        x = self.relu(self.conv4a(x))
        x = self.relu(self.conv4b(x))

        # Detector head → raw logits (softmax done in C++ post-processing)
        semi = self.convPb(self.relu(self.convPa(x)))

        # Descriptor head → L2-normalised dense map
        desc = self.convDb(self.relu(self.convDa(x)))
        dn = torch.norm(desc, p=2, dim=1, keepdim=True).clamp(min=1e-8)
        desc = desc / dn

        return semi, desc


# ---------------------------------------------------------------------------

def download_weights():
    if WEIGHTS_CACHE.exists():
        print(f"[✓] Using cached weights: {WEIGHTS_CACHE}")
        return
    WEIGHTS_CACHE.parent.mkdir(parents=True, exist_ok=True)
    print(f"[+] Downloading SuperPoint weights from:\n    {WEIGHTS_URL}")
    urllib.request.urlretrieve(WEIGHTS_URL, WEIGHTS_CACHE)
    print(f"[✓] Saved to {WEIGHTS_CACHE}")


def main():
    parser = argparse.ArgumentParser(description="Export SuperPoint → ONNX")
    parser.add_argument("--height", type=int, default=480,
                        help="Export dummy input height (multiple of 8, default: 480)")
    parser.add_argument("--width",  type=int, default=640,
                        help="Export dummy input width  (multiple of 8, default: 640)")
    parser.add_argument("--output", type=str,
                        default="app/src/main/assets/superpoint.onnx",
                        help="Output ONNX path")
    args = parser.parse_args()

    if args.height % 8 or args.width % 8:
        print("Error: height and width must be multiples of 8", file=sys.stderr)
        sys.exit(1)

    download_weights()

    print("[+] Loading model...")
    model = SuperPointNet()
    state = torch.load(WEIGHTS_CACHE, map_location="cpu")
    model.load_state_dict(state)
    model.eval()

    out_path = pathlib.Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    dummy = torch.zeros(1, 1, args.height, args.width)
    print(f"[+] Exporting ONNX with dummy input [1, 1, {args.height}, {args.width}] ...")

    torch.onnx.export(
        model, dummy, str(out_path),
        opset_version=12,
        input_names=["image"],
        output_names=["semi", "desc"],
        dynamic_axes={
            "image": {2: "H",  3: "W"},
            "semi":  {2: "Hc", 3: "Wc"},
            "desc":  {2: "Hc", 3: "Wc"},
        },
    )

    size_kb = out_path.stat().st_size // 1024
    print(f"[✓] Exported → {out_path}  ({size_kb} KB)")
    print()
    print("Next steps:")
    print("  1. Place the .onnx file in app/src/main/assets/ (already done above).")
    print("  2. Rebuild the app:  ./gradlew assembleDebug")
    print("  3. At startup, Logcat will show:  SuperPoint: ready")


if __name__ == "__main__":
    main()
