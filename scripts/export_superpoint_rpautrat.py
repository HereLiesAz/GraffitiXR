#!/usr/bin/env python3
"""Export rpautrat/SuperPoint (MIT) to the ONNX contract used by GraffitiXR and SphereSLAM.

Source:     https://github.com/rpautrat/SuperPoint
  model:    superpoint_pytorch.py   @ ccf30088721dba97a9612d1bdd9b8d1bb49ac642 (unchanged at HEAD 1411bbd6)
  weights:  weights/superpoint_v6_from_tf.pth (added in 8b8e9e7e, unchanged at HEAD)
            sha256 cd5d19a5061848e248c17728878ea166b66512076d43c77dbcf27f4a88a56084

ONNX contract (same as the previously shipped superpoint.onnx):
  input  image [1,1,H,W] float32, grayscale in [0,1] (H, W multiples of 8, dynamic)
  output semi  [1,65,H/8,W/8] float32 raw detector logits (softmax is done by the caller)
  output desc  [1,256,H/8,W/8] float32 dense descriptors, L2-normalised over channels
  opset 12

The network is the reference forward exactly: every VGG block is conv -> ReLU -> BatchNorm
(eps 1e-3, BN *after* the ReLU, so it is NOT folded into the conv), the last detector / descriptor
blocks are conv -> BN without ReLU, and desc = F.normalize(descriptor(features), p=2, dim=1).

Usage:
  python -I export_superpoint_rpautrat.py --model-py superpoint_pytorch.py \
      --weights superpoint_v6_from_tf.pth --out superpoint.onnx
"""
import argparse
import hashlib
import importlib.util

import torch

EXPECTED_WEIGHTS_SHA256 = "cd5d19a5061848e248c17728878ea166b66512076d43c77dbcf27f4a88a56084"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_reference(model_py, weights):
    spec = importlib.util.spec_from_file_location("rpautrat_superpoint_pytorch", model_py)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    net = mod.SuperPoint()
    sd = torch.load(weights, map_location="cpu", weights_only=True)
    net.load_state_dict(sd, strict=True)
    return net.eval()


class DenseSuperPoint(torch.nn.Module):
    """Dense part of rpautrat's SuperPoint.forward (before softmax / NMS / sampling)."""

    def __init__(self, ref):
        super().__init__()
        self.backbone = ref.backbone
        self.detector = ref.detector
        self.descriptor = ref.descriptor

    def forward(self, image):
        features = self.backbone(image)
        semi = self.detector(features)
        d = self.descriptor(features)
        # == torch.nn.functional.normalize(d, p=2, dim=1, eps=1e-12), written without expand_as so
        # the exported graph is ReduceL2 -> Clip -> Div (no Shape/Expand; friendlier to OpenCV DNN).
        desc = d / d.norm(p=2, dim=1, keepdim=True).clamp(min=1e-12)
        return semi, desc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-py", required=True)
    ap.add_argument("--weights", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--allow-other-weights", action="store_true")
    a = ap.parse_args()

    digest = sha256(a.weights)
    if digest != EXPECTED_WEIGHTS_SHA256 and not a.allow_other_weights:
        raise SystemExit(f"unexpected checkpoint sha256 {digest}")

    model = DenseSuperPoint(load_reference(a.model_py, a.weights)).eval()
    dummy = torch.rand(1, 1, 480, 640)
    with torch.no_grad():
        torch.onnx.export(
            model, (dummy,), a.out,
            input_names=["image"], output_names=["semi", "desc"],
            dynamic_axes={"image": {2: "H", 3: "W"},
                          "semi": {2: "Hc", 3: "Wc"},
                          "desc": {2: "Hc", 3: "Wc"}},
            opset_version=12, do_constant_folding=True, dynamo=False,
        )

    import onnx
    m = onnx.load(a.out)
    # Pin the static dims of the outputs (the exporter leaves desc's N/C symbolic).
    for o, c in (("semi", 65), ("desc", 256)):
        dims = next(t for t in m.graph.output if t.name == o).type.tensor_type.shape.dim
        dims[0].dim_value = 1; dims[1].dim_value = c
        dims[2].dim_param = "Hc"; dims[3].dim_param = "Wc"
    onnx.checker.check_model(m)
    for k, v in {
        "source": "https://github.com/rpautrat/SuperPoint",
        "source_commit": "ccf30088721dba97a9612d1bdd9b8d1bb49ac642",
        "weights": "weights/superpoint_v6_from_tf.pth",
        "weights_sha256": digest,
        "license": "MIT, Copyright (c) 2018 Paul-Edouard Sarlin & Remi Pautrat",
        "contract": "image[1,1,H,W] gray 0..1 -> semi[1,65,H/8,W/8] logits, desc[1,256,H/8,W/8] L2-normalised",
    }.items():
        p = m.metadata_props.add(); p.key = k; p.value = v
    m.doc_string = "SuperPoint (rpautrat MIT re-implementation), reference forward: conv->ReLU->BN"
    onnx.save(m, a.out)
    print("wrote", a.out, "sha256", sha256(a.out))


if __name__ == "__main__":
    main()
