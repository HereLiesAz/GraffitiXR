#!/usr/bin/env python3
"""
model.py — the DistortionHead.

A small HomographyNet-style head over stacked, frozen SuperPoint descriptor maps. Plain
conv/relu/maxpool/gemm only, so it exports cleanly to opset-12 ONNX for OpenCV DNN.

Two views in (each a [B,256,G,G] desc map, default G=32), four things out:
    corners(8)  pose(3)  matchability(1)  coverage(1)
"""

import torch
import torch.nn as nn


class DistortionHead(nn.Module):
    DESC_DIM = 256

    def __init__(self, grid: int = 32, drop: float = 0.2):
        super().__init__()
        c = self.DESC_DIM
        self.features = nn.Sequential(
            nn.Conv2d(2 * c, 256, 3, padding=1), nn.ReLU(inplace=True), nn.MaxPool2d(2),  # 16
            nn.Conv2d(256, 256, 3, padding=1), nn.ReLU(inplace=True), nn.MaxPool2d(2),     # 8
            nn.Conv2d(256, 128, 3, padding=1), nn.ReLU(inplace=True), nn.MaxPool2d(2),     # 4
        )
        reduced = grid // 8
        flat = 128 * reduced * reduced
        self.trunk = nn.Sequential(
            nn.Flatten(), nn.Linear(flat, 512), nn.ReLU(inplace=True), nn.Dropout(drop),
        )
        self.corners = nn.Linear(512, 8)
        self.pose = nn.Linear(512, 3)         # [tilt_deg, log2_scale, roll_deg] (raw units)
        self.matchability = nn.Linear(512, 1)  # raw logit
        self.coverage = nn.Linear(512, 1)      # raw logit

    def forward(self, desc_cur: torch.Tensor, desc_fp: torch.Tensor) -> dict:
        x = torch.cat([desc_cur, desc_fp], dim=1)
        z = self.trunk(self.features(x))
        return {
            "corners": self.corners(z),
            "pose": self.pose(z),
            "matchability": self.matchability(z).squeeze(-1),
            "coverage": self.coverage(z).squeeze(-1),
        }


class ExportWrapper(nn.Module):
    """Packs head outputs into a single distortion[13] tensor for the native side.

    Layout: [0:8]=corners, [8:11]=pose, [11]=matchability(prob), [12]=coverage(0..1).
    Takes the two gray images and runs the frozen backbone internally so the exported
    graph is self-contained (image_cur, image_fp -> distortion).
    """

    def __init__(self, backbone: nn.Module, head: DistortionHead, grid: int = 32):
        super().__init__()
        self.backbone = backbone
        self.head = head
        self.grid = grid

    def forward(self, image_cur: torch.Tensor, image_fp: torch.Tensor) -> torch.Tensor:
        dc = self.backbone.features_at(image_cur, self.grid)
        df = self.backbone.features_at(image_fp, self.grid)
        out = self.head(dc, df)
        match = torch.sigmoid(out["matchability"]).unsqueeze(-1)
        cov = torch.sigmoid(out["coverage"]).unsqueeze(-1)
        return torch.cat([out["corners"], out["pose"], match, cov], dim=-1)


if __name__ == "__main__":
    head = DistortionHead()
    dc = torch.rand(2, 256, 32, 32)
    df = torch.rand(2, 256, 32, 32)
    o = head(dc, df)
    print({k: tuple(v.shape) for k, v in o.items()})
    print("head params:", sum(p.numel() for p in head.parameters()))
