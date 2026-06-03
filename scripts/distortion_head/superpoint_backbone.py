#!/usr/bin/env python3
"""
superpoint_backbone.py — frozen SuperPoint feature extractor for the distortion head.

Reuses the *exact* architecture and pre-trained weights from
``scripts/convert_superpoint.py`` (single source of truth — the on-device model and the
training backbone must be identical), and exposes the dense, L2-normalised descriptor map
``desc [B, 256, H/8, W/8]`` that the distortion head consumes.

SuperPoint is always frozen here: we never backprop into it.
"""

from __future__ import annotations

import importlib.util
import pathlib

import torch
import torch.nn as nn
import torch.nn.functional as F

_THIS = pathlib.Path(__file__).resolve()
_CONVERT = _THIS.parents[1] / "convert_superpoint.py"


def _load_convert_module():
    """Import convert_superpoint.py as a module without running its __main__."""
    spec = importlib.util.spec_from_file_location("convert_superpoint", _CONVERT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class FrozenSuperPoint(nn.Module):
    """Wraps the official SuperPointNet, frozen, returning only the dense desc map.

    Input : gray image [B, 1, H, W] in [0, 1], H and W multiples of 8.
    Output: desc       [B, 256, H/8, W/8], L2-normalised over the channel dim.
    """

    DESC_DIM = 256
    STRIDE = 8

    def __init__(self, weights_path: str | None = None):
        super().__init__()
        conv = _load_convert_module()
        self.net = conv.SuperPointNet()
        if weights_path is None:
            conv.download_weights()
            weights_path = str(conv.WEIGHTS_CACHE)
        state = torch.load(weights_path, map_location="cpu")
        self.net.load_state_dict(state)
        self.net.eval()
        for p in self.net.parameters():
            p.requires_grad_(False)

    @torch.no_grad()
    def forward(self, gray: torch.Tensor) -> torch.Tensor:
        # SuperPointNet.forward returns (semi, desc); desc is already L2-normalised.
        _, desc = self.net(gray)
        return desc

    @torch.no_grad()
    def features_at(self, gray: torch.Tensor, out_hw: int) -> torch.Tensor:
        """desc map resized (bilinear) to a fixed out_hw x out_hw grid, re-normalised."""
        desc = self.forward(gray)
        desc = F.interpolate(desc, size=(out_hw, out_hw), mode="bilinear", align_corners=False)
        desc = F.normalize(desc, p=2, dim=1)
        return desc


if __name__ == "__main__":
    # Smoke test: one forward pass on a dummy image.
    sp = FrozenSuperPoint()
    x = torch.rand(2, 1, 256, 256)
    d = sp.features_at(x, out_hw=32)
    print("desc:", tuple(d.shape), "| frozen params:",
          sum(p.numel() for p in sp.parameters()))
