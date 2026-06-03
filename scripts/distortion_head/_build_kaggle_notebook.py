#!/usr/bin/env python3
"""
_build_kaggle_notebook.py — emit train_kaggle.ipynb (a self-contained Kaggle
notebook for training + exporting the distortion head).

The notebook mirrors the modules in this directory (superpoint_backbone.py,
data.py, model.py, train.py, export_onnx.py) but inlines them so it runs on
Kaggle with no repo clone. Regenerate with:  python3 _build_kaggle_notebook.py
"""

import json
import pathlib

CELLS: list[tuple[str, str]] = []


def md(src: str) -> None:
    CELLS.append(("markdown", src.strip("\n")))


def code(src: str) -> None:
    CELLS.append(("code", src.strip("\n")))


# --------------------------------------------------------------------------- #
md(r"""
# GraffitiXR — Distortion Head training (Kaggle)

Trains the viewpoint/partial-aware **distortion head** that sits on top of the frozen
SuperPoint backbone, then exports it to ONNX. Self-supervised on synthetic homographies —
**no labelled data required** (a corpus of wall/texture images helps; a procedural fallback
runs without one).

Outputs (in `/kaggle/working`): `head_best.pth`, `head_last.pth`, `distortion_head.onnx`.

**Before running — in the Kaggle sidebar:**
1. **Settings → Accelerator → GPU** (T4/P100).
2. **Settings → Internet → On** (needed once, to fetch SuperPoint weights).
3. *(Optional, recommended)* **Add Input** → attach an image dataset (e.g. *Describable
   Textures Dataset (DTD)*, or any wall/graffiti photos). The notebook auto-discovers any
   images under `/kaggle/input`. Without one it falls back to procedural textures (lower
   quality, fine for a smoke test).

Then **Run All**. Mirrors `scripts/distortion_head/` in the repo.
""")

# --------------------------------------------------------------------------- #
md("## 1 · Environment")
code(r"""
import sys, subprocess
import torch
print("python", sys.version.split()[0], "| torch", torch.__version__,
      "| cuda", torch.cuda.is_available(),
      "|", (torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU"))
for pkg in ("onnx", "onnxruntime"):
    try:
        __import__(pkg)
    except Exception:
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", pkg], check=False)
import cv2, numpy as np
print("cv2", cv2.__version__, "| numpy", np.__version__)
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
if DEVICE == "cpu":
    print("WARNING: no GPU — enable Accelerator → GPU for a usable training speed.")
""")

# --------------------------------------------------------------------------- #
md("## 2 · Config")
code(r"""
import os

def find_corpus():
    base = "/kaggle/input"
    if not os.path.isdir(base):
        return None
    exts = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
    for _root, _dirs, files in os.walk(base):
        if any(f.lower().endswith(exts) for f in files):
            return base  # dataset.rglob below will find every image under here
    return None

CONFIG = dict(
    patch=256,          # input patch size (multiple of 8)
    grid=32,            # desc grid = patch // 8
    batch=32,
    epochs=20,          # bump for a real run; lower for a quick look
    lr=1e-3,
    train_len=20000,    # synthetic samples per epoch
    val_len=2000,
    neg_prob=0.25,
    max_tilt=70.0,      # degrees
    max_log2_scale=2.0,  # scale range 0.25x .. 4x
    jitter_frac=0.08,
    workers=2,
    images=find_corpus(),
    out="/kaggle/working",
)
print("corpus:", CONFIG["images"] or "(none — procedural fallback)")
print(CONFIG)
""")

# --------------------------------------------------------------------------- #
md("## 3 · Frozen SuperPoint backbone\nExact Magic Leap architecture; weights fetched once. Never trained — we only read its dense descriptor map.")
code(r"""
import pathlib, urllib.request
import torch.nn as nn
import torch.nn.functional as F

WEIGHTS_URL = ("https://github.com/magicleap/SuperPointPretrainedNetwork"
               "/raw/master/superpoint_v1.pth")
WEIGHTS = pathlib.Path("superpoint_v1.pth")


class SuperPointNet(nn.Module):
    # Outputs raw semi [B,65,H/8,W/8] and L2-normalised desc [B,256,H/8,W/8].
    def __init__(self):
        super().__init__()
        c1, c2, c3, c4, d1 = 64, 64, 128, 128, 256
        self.relu = nn.ReLU(inplace=True)
        self.pool = nn.MaxPool2d(2, 2)
        self.conv1a = nn.Conv2d(1, c1, 3, padding=1); self.conv1b = nn.Conv2d(c1, c1, 3, padding=1)
        self.conv2a = nn.Conv2d(c1, c2, 3, padding=1); self.conv2b = nn.Conv2d(c2, c2, 3, padding=1)
        self.conv3a = nn.Conv2d(c2, c3, 3, padding=1); self.conv3b = nn.Conv2d(c3, c3, 3, padding=1)
        self.conv4a = nn.Conv2d(c3, c4, 3, padding=1); self.conv4b = nn.Conv2d(c4, c4, 3, padding=1)
        self.convPa = nn.Conv2d(c4, 256, 3, padding=1); self.convPb = nn.Conv2d(256, 65, 1)
        self.convDa = nn.Conv2d(c4, 256, 3, padding=1); self.convDb = nn.Conv2d(256, d1, 1)

    def forward(self, x):
        x = self.relu(self.conv1a(x)); x = self.relu(self.conv1b(x)); x = self.pool(x)
        x = self.relu(self.conv2a(x)); x = self.relu(self.conv2b(x)); x = self.pool(x)
        x = self.relu(self.conv3a(x)); x = self.relu(self.conv3b(x)); x = self.pool(x)
        x = self.relu(self.conv4a(x)); x = self.relu(self.conv4b(x))
        semi = self.convPb(self.relu(self.convPa(x)))
        desc = self.convDb(self.relu(self.convDa(x)))
        desc = desc / torch.norm(desc, p=2, dim=1, keepdim=True).clamp(min=1e-8)
        return semi, desc


def _get_weights():
    if not WEIGHTS.exists():
        print("downloading SuperPoint weights ...")
        urllib.request.urlretrieve(WEIGHTS_URL, WEIGHTS)
    return str(WEIGHTS)


class FrozenSuperPoint(nn.Module):
    DESC_DIM = 256

    def __init__(self):
        super().__init__()
        self.net = SuperPointNet()
        self.net.load_state_dict(torch.load(_get_weights(), map_location="cpu"))
        self.net.eval()
        for p in self.net.parameters():
            p.requires_grad_(False)

    @torch.no_grad()
    def forward(self, gray):
        _, desc = self.net(gray)
        return desc

    @torch.no_grad()
    def features_at(self, gray, out_hw):
        d = self.forward(gray)
        d = F.interpolate(d, size=(out_hw, out_hw), mode="bilinear", align_corners=False)
        return F.normalize(d, p=2, dim=1)


_sp = FrozenSuperPoint().to(DEVICE).eval()
print("backbone ready | frozen params:", sum(p.numel() for p in _sp.parameters()))
""")

# --------------------------------------------------------------------------- #
md("## 4 · Synthetic-homography dataset\nPlane-induced `H` (tilt/scale/roll) + occlusion + photometric aug. All labels derived exactly from the synthetic warp.")
code(r"""
import math, random
from torch.utils.data import Dataset, DataLoader

_IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def _rot_xyz(yaw, pitch, roll):
    cy, sy = math.cos(yaw), math.sin(yaw)
    cx, sx = math.cos(pitch), math.sin(pitch)
    cz, sz = math.cos(roll), math.sin(roll)
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]], np.float64)
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], np.float64)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]], np.float64)
    return Rz @ Ry @ Rx


def build_homography(P, tilt_deg, azimuth_deg, roll_deg, log2_scale, tx, ty):
    f = float(P); c = P / 2.0
    K = np.array([[f, 0, c], [0, f, c], [0, 0, 1]], np.float64)
    Kinv = np.linalg.inv(K)
    az = math.radians(azimuth_deg); t = math.radians(tilt_deg)
    R = _rot_xyz(t * math.sin(az), t * math.cos(az), math.radians(roll_deg))
    H_rot = K @ R @ Kinv
    s = 2.0 ** log2_scale
    T_neg = np.array([[1, 0, -c], [0, 1, -c], [0, 0, 1]], np.float64)
    S = np.array([[s, 0, 0], [0, s, 0], [0, 0, 1]], np.float64)
    T_pos = np.array([[1, 0, c + tx], [0, 1, c + ty], [0, 0, 1]], np.float64)
    H = T_pos @ S @ T_neg @ H_rot
    return H / H[2, 2]


def corner_offsets(H, P):
    corners = np.array([[0, 0], [P, 0], [P, P], [0, P]], np.float64)
    warped = cv2.perspectiveTransform(corners.reshape(1, 4, 2), H).reshape(4, 2)
    return ((warped - corners) / float(P)).astype(np.float32).reshape(-1)


def photometric(img, rng):
    out = np.clip(img.astype(np.float32) * rng.uniform(0.6, 1.4) + rng.uniform(-40, 40), 0, 255)
    if rng.random() < 0.5:
        out = 255.0 * np.power(out / 255.0, rng.uniform(1.2, 2.4))
    if rng.random() < 0.5:
        out += rng.uniform(2, 12) * np.random.randn(*out.shape).astype(np.float32)
    return np.clip(out, 0, 255).astype(np.uint8)


def occlude(patch, rng):
    P = patch.shape[0]
    mask = np.ones((P, P), np.float32)
    for _ in range(rng.randint(0, 3)):
        w = rng.randint(P // 8, P // 2); h = rng.randint(P // 8, P // 2)
        x = rng.randint(0, P - w); y = rng.randint(0, P - h)
        patch[y:y + h, x:x + w] = 0; mask[y:y + h, x:x + w] = 0.0
    return patch, float(mask.mean())


class SyntheticHomographyDataset(Dataset):
    def __init__(self, image_dir, patch=256, length=20000, neg_prob=0.25,
                 max_tilt=70.0, max_log2_scale=2.0, jitter_frac=0.08, seed=0):
        self.P = patch; self.length = length; self.neg_prob = neg_prob
        self.max_tilt = max_tilt; self.max_log2_scale = max_log2_scale
        self.jitter = jitter_frac * patch; self.base_seed = seed
        self.paths = []
        if image_dir:
            self.paths = sorted(p for p in pathlib.Path(image_dir).rglob("*")
                                if p.suffix.lower() in _IMG_EXTS)
        if not self.paths:
            print("[data] no corpus — procedural-texture fallback.")

    def __len__(self):
        return self.length

    def _procedural(self, rng):
        P = self.P
        img = (np.random.rand(P // 8, P // 8) * 255).astype(np.uint8)
        img = cv2.resize(img, (P, P), interpolation=cv2.INTER_NEAREST)
        for _ in range(rng.randint(6, 20)):
            cv2.line(img, (rng.randint(0, P), rng.randint(0, P)),
                     (rng.randint(0, P), rng.randint(0, P)),
                     rng.randint(0, 255), rng.randint(1, 6))
        return cv2.GaussianBlur(img, (3, 3), 0)

    def _load_gray(self, path, rng):
        img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if img is None:
            return self._procedural(rng)
        h, w = img.shape
        if min(h, w) < self.P:
            img = cv2.resize(img, (max(self.P, w), max(self.P, h))); h, w = img.shape
        y = rng.randint(0, h - self.P); x = rng.randint(0, w - self.P)
        return img[y:y + self.P, x:x + self.P]

    def __getitem__(self, idx):
        rng = random.Random(self.base_seed * 1_000_003 + idx)
        np.random.seed((self.base_seed * 1_000_003 + idx) % (2**32))
        P = self.P

        def sample():
            return self._load_gray(rng.choice(self.paths), rng) if self.paths else self._procedural(rng)

        canonical = sample()
        is_pos = rng.random() > self.neg_prob
        tilt = rng.uniform(0, self.max_tilt); azimuth = rng.uniform(0, 360)
        roll = rng.uniform(-180, 180); log2s = rng.uniform(-self.max_log2_scale, self.max_log2_scale)
        tx = rng.uniform(-self.jitter, self.jitter); ty = rng.uniform(-self.jitter, self.jitter)

        if is_pos:
            H = build_homography(P, tilt, azimuth, roll, log2s, tx, ty)
            warped = cv2.warpPerspective(canonical, H, (P, P), flags=cv2.INTER_LINEAR)
            corners = corner_offsets(H, P)
            warped, coverage = occlude(warped, rng)
        else:
            warped = sample()
            big = build_homography(P, rng.uniform(40, 80), azimuth, roll,
                                   rng.uniform(-2.5, 2.5), tx, ty)
            warped = cv2.warpPerspective(warped, big, (P, P), flags=cv2.INTER_LINEAR)
            corners = np.zeros(8, np.float32); coverage = 0.0

        canonical = photometric(canonical, rng); warped = photometric(warped, rng)
        tt = lambda g: torch.from_numpy(g.astype(np.float32) / 255.0).unsqueeze(0)
        return {
            "cur": tt(warped), "fp": tt(canonical),
            "corners": torch.from_numpy(corners),
            "pose": torch.from_numpy(np.array([tilt, log2s, roll], np.float32)),
            "coverage": torch.tensor(coverage, dtype=torch.float32),
            "matchable": torch.tensor(1.0 if is_pos else 0.0, dtype=torch.float32),
        }


_probe = SyntheticHomographyDataset(CONFIG["images"], CONFIG["patch"], length=4)[0]
print({k: tuple(v.shape) for k, v in _probe.items()})
""")

# --------------------------------------------------------------------------- #
md("## 5 · Distortion head\nHomographyNet-style head on stacked desc maps. Plain conv/relu/maxpool/gemm → opset-12 ONNX (OpenCV-DNN / ONNX-Runtime compatible).")
code(r"""
class DistortionHead(nn.Module):
    DESC_DIM = 256

    def __init__(self, grid=32, drop=0.2):
        super().__init__()
        c = self.DESC_DIM
        self.features = nn.Sequential(
            nn.Conv2d(2 * c, 256, 3, padding=1), nn.ReLU(inplace=True), nn.MaxPool2d(2),
            nn.Conv2d(256, 256, 3, padding=1), nn.ReLU(inplace=True), nn.MaxPool2d(2),
            nn.Conv2d(256, 128, 3, padding=1), nn.ReLU(inplace=True), nn.MaxPool2d(2),
        )
        reduced = grid // 8
        self.trunk = nn.Sequential(
            nn.Flatten(), nn.Linear(128 * reduced * reduced, 512),
            nn.ReLU(inplace=True), nn.Dropout(drop),
        )
        self.corners = nn.Linear(512, 8)
        self.pose = nn.Linear(512, 3)
        self.matchability = nn.Linear(512, 1)
        self.coverage = nn.Linear(512, 1)

    def forward(self, desc_cur, desc_fp):
        z = self.trunk(self.features(torch.cat([desc_cur, desc_fp], dim=1)))
        return {
            "corners": self.corners(z),
            "pose": self.pose(z),
            "matchability": self.matchability(z).squeeze(-1),
            "coverage": self.coverage(z).squeeze(-1),
        }


class ExportWrapper(nn.Module):
    # image_cur, image_fp -> distortion[13] = [corners(8), pose(3), match, coverage].
    def __init__(self, backbone, head, grid=32):
        super().__init__()
        self.backbone = backbone; self.head = head; self.grid = grid

    def forward(self, image_cur, image_fp):
        out = self.head(self.backbone.features_at(image_cur, self.grid),
                        self.backbone.features_at(image_fp, self.grid))
        match = torch.sigmoid(out["matchability"]).unsqueeze(-1)
        cov = torch.sigmoid(out["coverage"]).unsqueeze(-1)
        return torch.cat([out["corners"], out["pose"], match, cov], dim=-1)


_head_probe = DistortionHead(grid=CONFIG["grid"])
print("head params:", sum(p.numel() for p in _head_probe.parameters()))
""")

# --------------------------------------------------------------------------- #
md("## 6 · Train\nMasked multi-task loss (corners/pose/coverage on positives; matchability on all). Validates each epoch: corner error (px), tilt/log2-scale MAE, matchability AUC.")
code(r"""
POSE_NORM = torch.tensor([90.0, 2.0, 180.0])


def masked_smooth_l1(pred, tgt, mask):
    per = F.smooth_l1_loss(pred, tgt, reduction="none")
    per = per.mean(dim=tuple(range(1, per.dim()))) if per.dim() > 1 else per
    return (per * mask).sum() / mask.sum().clamp(min=1.0)


def step_loss(out, b, w):
    pn = POSE_NORM.to(out["pose"].device); m = b["matchable"]
    losses = {
        "corners": masked_smooth_l1(out["corners"], b["corners"], m),
        "pose": masked_smooth_l1(out["pose"] / pn, b["pose"] / pn, m),
        "coverage": masked_smooth_l1(out["coverage"], b["coverage"], m),
        "match": F.binary_cross_entropy_with_logits(out["matchability"], m),
    }
    return sum(w[k] * v for k, v in losses.items()), losses


def roc_auc(scores, labels):
    if (labels > 0.5).sum() == 0 or (labels <= 0.5).sum() == 0:
        return float("nan")
    order = torch.argsort(scores)
    ranks = torch.empty_like(order, dtype=torch.float)
    ranks[order] = torch.arange(1, len(scores) + 1, dtype=torch.float)
    n_p = int((labels > 0.5).sum()); n_n = len(scores) - n_p
    r_pos = ranks[labels > 0.5].sum()
    return float((r_pos - n_p * (n_p + 1) / 2) / (n_p * n_n))


@torch.no_grad()
def evaluate(head, loader, grid, max_batches=20):
    head.eval()
    cpx = tm = sm = npos = 0.0; probs = []; labs = []
    P = loader.dataset.P
    for i, b in enumerate(loader):
        if i >= max_batches:
            break
        out = head(_sp.features_at(b["cur"].to(DEVICE), grid),
                   _sp.features_at(b["fp"].to(DEVICE), grid))
        m = b["matchable"].bool()
        probs.append(torch.sigmoid(out["matchability"]).cpu()); labs.append(b["matchable"])
        if m.any():
            cpx += (out["corners"][m].cpu() - b["corners"][m]).abs().mean().item() * P * m.sum().item()
            pe = (out["pose"][m].cpu() - b["pose"][m]).abs()
            tm += pe[:, 0].sum().item(); sm += pe[:, 1].sum().item(); npos += m.sum().item()
    head.train()
    n = max(npos, 1)
    return {"corner_px": cpx / n, "tilt_mae": tm / n, "log2scale_mae": sm / n,
            "match_auc": roc_auc(torch.cat(probs), torch.cat(labs))}


def train():
    out_dir = pathlib.Path(CONFIG["out"]); out_dir.mkdir(parents=True, exist_ok=True)
    head = DistortionHead(grid=CONFIG["grid"]).to(DEVICE)
    opt = torch.optim.AdamW(head.parameters(), lr=CONFIG["lr"], weight_decay=1e-4)
    tr = DataLoader(SyntheticHomographyDataset(CONFIG["images"], CONFIG["patch"],
                    CONFIG["train_len"], CONFIG["neg_prob"], CONFIG["max_tilt"],
                    CONFIG["max_log2_scale"], CONFIG["jitter_frac"], seed=1),
                    CONFIG["batch"], shuffle=True, num_workers=CONFIG["workers"], drop_last=True)
    va = DataLoader(SyntheticHomographyDataset(CONFIG["images"], CONFIG["patch"],
                    CONFIG["val_len"], CONFIG["neg_prob"], CONFIG["max_tilt"],
                    CONFIG["max_log2_scale"], CONFIG["jitter_frac"], seed=999),
                    CONFIG["batch"], shuffle=False, num_workers=CONFIG["workers"])
    w = {"corners": 1.0, "pose": 1.0, "coverage": 0.5, "match": 0.5}
    best = float("inf")
    for ep in range(CONFIG["epochs"]):
        head.train()
        for it, b in enumerate(tr):
            out = head(_sp.features_at(b["cur"].to(DEVICE), CONFIG["grid"]),
                       _sp.features_at(b["fp"].to(DEVICE), CONFIG["grid"]))
            b = {k: (v.to(DEVICE) if torch.is_tensor(v) else v) for k, v in b.items()}
            total, parts = step_loss(out, b, w)
            opt.zero_grad(); total.backward(); opt.step()
            if it % 100 == 0:
                msg = " ".join(f"{k}={v.item():.3f}" for k, v in parts.items())
                print(f"  ep{ep} it{it} loss={total.item():.3f} | {msg}")
        mtr = evaluate(head, va, CONFIG["grid"])
        print(f"[val] ep{ep} " + " ".join(f"{k}={v:.3f}" for k, v in mtr.items()))
        torch.save(head.state_dict(), out_dir / "head_last.pth")
        if mtr["corner_px"] < best:
            best = mtr["corner_px"]
            torch.save(head.state_dict(), out_dir / "head_best.pth")
            print(f"  [*] new best corner_px={best:.2f} -> head_best.pth")
    print(f"[done] best corner_px={best:.2f}")
    return head


trained_head = train()
""")

# --------------------------------------------------------------------------- #
md("## 7 · Export ONNX\nSelf-contained graph (frozen SuperPoint + head): two gray images → `distortion[13]`. Verified with ONNX Runtime (and OpenCV DNN if it accepts the graph).")
code(r"""
P, grid = CONFIG["patch"], CONFIG["grid"]
best_path = pathlib.Path(CONFIG["out"]) / "head_best.pth"
head = DistortionHead(grid=grid).eval()
head.load_state_dict(torch.load(best_path, map_location="cpu"))
wrapper = ExportWrapper(FrozenSuperPoint(), head, grid=grid).eval()

onnx_path = str(pathlib.Path(CONFIG["out"]) / "distortion_head.onnx")
torch.onnx.export(
    wrapper, (torch.zeros(1, 1, P, P), torch.zeros(1, 1, P, P)), onnx_path,
    opset_version=12, input_names=["image_cur", "image_fp"], output_names=["distortion"],
    dynamic_axes={"image_cur": {0: "B"}, "image_fp": {0: "B"}, "distortion": {0: "B"}},
)
print("exported", onnx_path, "|", os.path.getsize(onnx_path) // 1024, "KB")
print("distortion[13] = [corners(8), pose(3), matchability(1), coverage(1)]")

import onnxruntime as ort
sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
y = sess.run(["distortion"], {"image_cur": np.zeros((1, 1, P, P), np.float32),
                              "image_fp": np.zeros((1, 1, P, P), np.float32)})[0]
print("ONNX Runtime OK | output", y.shape)
try:
    net = cv2.dnn.readNetFromONNX(onnx_path)
    net.setInput(np.zeros((1, 1, P, P), np.float32), "image_cur")
    net.setInput(np.zeros((1, 1, P, P), np.float32), "image_fp")
    print("OpenCV DNN OK | output", net.forward("distortion").shape)
except Exception as e:
    print("OpenCV DNN check skipped/failed (expected if graph uses unsupported ops):", e)
""")

# --------------------------------------------------------------------------- #
md("## 8 · Artifacts\nDownload these from the **Output** tab (or the `/kaggle/working` panel).")
code(r"""
for f in sorted(pathlib.Path(CONFIG["out"]).glob("*")):
    print(f.name, f.stat().st_size // 1024, "KB")
""")

# --------------------------------------------------------------------------- #

nb = {
    "cells": [
        {"cell_type": t, "metadata": {},
         **({"source": s.splitlines(keepends=True)} if t == "markdown"
            else {"source": s.splitlines(keepends=True), "outputs": [], "execution_count": None})}
        for (t, s) in CELLS
    ],
    "metadata": {
        "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
        "language_info": {"name": "python"},
        "accelerator": "GPU",
    },
    "nbformat": 4,
    "nbformat_minor": 5,
}

out = pathlib.Path(__file__).resolve().parent / "train_kaggle.ipynb"
out.write_text(json.dumps(nb, indent=1, ensure_ascii=False))
print("wrote", out, "|", len(CELLS), "cells")
