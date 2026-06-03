#!/usr/bin/env python3
"""
data.py — self-supervised synthetic-homography dataset for the distortion head.

Each sample is a (canonical_patch, warped_view) pair plus labels derived for free from a
synthetic plane-induced homography:

    corners       4-corner offsets of H, normalised by patch size  (TL,TR,BR,BL ; x,y)
    pose          [tilt_deg, log2_scale, roll_deg]
    coverage      visible fraction of the target after occlusion (0..1)
    matchability  1 = same target (warped), 0 = a different image / out-of-range warp

No labelled mural data is needed — the homography is constructed, so all labels are exact.
If no image corpus is supplied, a procedural-texture fallback keeps the pipeline runnable
end-to-end for smoke tests.
"""

from __future__ import annotations

import math
import pathlib
import random

import cv2
import numpy as np
import torch
from torch.utils.data import Dataset

_IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


# --------------------------------------------------------------------------- #
# Homography construction                                                      #
# --------------------------------------------------------------------------- #

def _rot_xyz(yaw: float, pitch: float, roll: float) -> np.ndarray:
    cy, sy = math.cos(yaw), math.sin(yaw)
    cx, sx = math.cos(pitch), math.sin(pitch)
    cz, sz = math.cos(roll), math.sin(roll)
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]], np.float64)
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], np.float64)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]], np.float64)
    return Rz @ Ry @ Rx


def build_homography(P: int, tilt_deg: float, azimuth_deg: float, roll_deg: float,
                     log2_scale: float, tx: float, ty: float) -> np.ndarray:
    """Plane-induced homography for a patch of size P, in pixel coordinates.

    tilt is split into yaw/pitch by azimuth; scale + translation are applied as a
    similarity about the patch centre. Focal ~= P (a plausible-ish FOV).
    """
    f = float(P)
    c = P / 2.0
    K = np.array([[f, 0, c], [0, f, c], [0, 0, 1]], np.float64)
    Kinv = np.linalg.inv(K)

    az = math.radians(azimuth_deg)
    t = math.radians(tilt_deg)
    yaw = t * math.sin(az)
    pitch = t * math.cos(az)
    R = _rot_xyz(yaw, pitch, math.radians(roll_deg))
    H_rot = K @ R @ Kinv

    s = 2.0 ** log2_scale
    T_neg = np.array([[1, 0, -c], [0, 1, -c], [0, 0, 1]], np.float64)
    S = np.array([[s, 0, 0], [0, s, 0], [0, 0, 1]], np.float64)
    T_pos = np.array([[1, 0, c + tx], [0, 1, c + ty], [0, 0, 1]], np.float64)
    H = T_pos @ S @ T_neg @ H_rot
    return H / H[2, 2]


def corner_offsets(H: np.ndarray, P: int) -> np.ndarray:
    """Normalised offsets of the 4 patch corners under H. Order TL,TR,BR,BL."""
    corners = np.array([[0, 0], [P, 0], [P, P], [0, P]], np.float64)
    warped = cv2.perspectiveTransform(corners.reshape(1, 4, 2), H).reshape(4, 2)
    return ((warped - corners) / float(P)).astype(np.float32).reshape(-1)


# --------------------------------------------------------------------------- #
# Augmentation                                                                 #
# --------------------------------------------------------------------------- #

def photometric(img: np.ndarray, rng: random.Random) -> np.ndarray:
    a = rng.uniform(0.6, 1.4)          # contrast
    b = rng.uniform(-40, 40)           # brightness
    out = np.clip(img.astype(np.float32) * a + b, 0, 255)
    if rng.random() < 0.5:             # low-light gamma
        g = rng.uniform(1.2, 2.4)
        out = 255.0 * np.power(out / 255.0, g)
    if rng.random() < 0.5:             # sensor noise
        out += rng.uniform(2, 12) * np.random.randn(*out.shape).astype(np.float32)
    return np.clip(out, 0, 255).astype(np.uint8)


def occlude(patch: np.ndarray, rng: random.Random) -> tuple[np.ndarray, float]:
    """Black out random rectangles (paint-over / occlusion). Returns (patch, coverage)."""
    P = patch.shape[0]
    mask = np.ones((P, P), np.float32)
    n = rng.randint(0, 3)
    for _ in range(n):
        w = rng.randint(P // 8, P // 2)
        h = rng.randint(P // 8, P // 2)
        x = rng.randint(0, P - w)
        y = rng.randint(0, P - h)
        patch[y:y + h, x:x + w] = 0
        mask[y:y + h, x:x + w] = 0.0
    return patch, float(mask.mean())


# --------------------------------------------------------------------------- #
# Dataset                                                                      #
# --------------------------------------------------------------------------- #

class SyntheticHomographyDataset(Dataset):
    def __init__(self, image_dir: str | None, patch: int = 256, length: int = 20000,
                 neg_prob: float = 0.25, max_tilt: float = 70.0,
                 max_log2_scale: float = 2.0, jitter_frac: float = 0.08, seed: int = 0):
        self.P = patch
        self.length = length
        self.neg_prob = neg_prob
        self.max_tilt = max_tilt
        self.max_log2_scale = max_log2_scale
        self.jitter = jitter_frac * patch
        self.base_seed = seed

        self.paths: list[pathlib.Path] = []
        if image_dir:
            root = pathlib.Path(image_dir)
            self.paths = sorted(p for p in root.rglob("*") if p.suffix.lower() in _IMG_EXTS)
        if not self.paths:
            print("[data] no corpus found — using procedural-texture fallback.")

    def __len__(self) -> int:
        return self.length

    def _procedural(self, rng: random.Random) -> np.ndarray:
        """Random structured texture so the pipeline runs without a corpus."""
        P = self.P
        img = (np.random.rand(P // 8, P // 8) * 255).astype(np.uint8)
        img = cv2.resize(img, (P, P), interpolation=cv2.INTER_NEAREST)
        for _ in range(rng.randint(6, 20)):
            p1 = (rng.randint(0, P), rng.randint(0, P))
            p2 = (rng.randint(0, P), rng.randint(0, P))
            cv2.line(img, p1, p2, rng.randint(0, 255), rng.randint(1, 6))
        return cv2.GaussianBlur(img, (3, 3), 0)

    def _load_gray(self, path: pathlib.Path, rng: random.Random) -> np.ndarray:
        img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if img is None:
            return self._procedural(rng)
        h, w = img.shape
        if min(h, w) < self.P:
            img = cv2.resize(img, (max(self.P, w), max(self.P, h)))
            h, w = img.shape
        y = rng.randint(0, h - self.P)
        x = rng.randint(0, w - self.P)
        return img[y:y + self.P, x:x + self.P]

    def __getitem__(self, idx: int):
        rng = random.Random(self.base_seed * 1_000_003 + idx)
        np.random.seed((self.base_seed * 1_000_003 + idx) % (2**32))
        P = self.P

        def sample_patch() -> np.ndarray:
            if self.paths:
                return self._load_gray(rng.choice(self.paths), rng)
            return self._procedural(rng)

        canonical = sample_patch()

        is_positive = rng.random() > self.neg_prob
        tilt = rng.uniform(0, self.max_tilt)
        azimuth = rng.uniform(0, 360)
        roll = rng.uniform(-180, 180)
        log2s = rng.uniform(-self.max_log2_scale, self.max_log2_scale)
        tx = rng.uniform(-self.jitter, self.jitter)
        ty = rng.uniform(-self.jitter, self.jitter)

        if is_positive:
            H = build_homography(P, tilt, azimuth, roll, log2s, tx, ty)
            warped = cv2.warpPerspective(canonical, H, (P, P), flags=cv2.INTER_LINEAR)
            corners = corner_offsets(H, P)
            warped, coverage = occlude(warped, rng)
        else:
            # Negative: an unrelated patch (optionally also warped). Geometry labels masked.
            warped = sample_patch()
            big_H = build_homography(P, rng.uniform(40, 80), azimuth, roll,
                                     rng.uniform(-2.5, 2.5), tx, ty)
            warped = cv2.warpPerspective(warped, big_H, (P, P), flags=cv2.INTER_LINEAR)
            corners = np.zeros(8, np.float32)
            coverage = 0.0

        canonical = photometric(canonical, rng)
        warped = photometric(warped, rng)

        def to_tensor(g: np.ndarray) -> torch.Tensor:
            return torch.from_numpy(g.astype(np.float32) / 255.0).unsqueeze(0)

        pose = np.array([tilt, log2s, roll], np.float32)
        return {
            "cur": to_tensor(warped),          # [1,P,P]  the live view
            "fp": to_tensor(canonical),        # [1,P,P]  the canonical fingerprint patch
            "corners": torch.from_numpy(corners),
            "pose": torch.from_numpy(pose),
            "coverage": torch.tensor(coverage, dtype=torch.float32),
            "matchable": torch.tensor(1.0 if is_positive else 0.0, dtype=torch.float32),
        }


if __name__ == "__main__":
    ds = SyntheticHomographyDataset(image_dir=None, length=8)
    s = ds[0]
    for k, v in s.items():
        print(f"{k:12s} {tuple(v.shape)} {v.dtype}")
