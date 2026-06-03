#!/usr/bin/env python3
"""
train.py — train the DistortionHead on frozen SuperPoint features.

Multi-task masked loss:
    corners  smooth-L1   (positives only)
    pose     smooth-L1   (positives only; pose normalised: tilt/90, log2s/2, roll/180)
    coverage L1          (positives only)
    match    BCE         (all samples)

Backbone is frozen; only the head trains. Validates on held-out synthetic warps and reports
corner error (px), tilt/scale MAE, and matchability AUC.

Usage:
    pip install torch opencv-python numpy
    python3 scripts/distortion_head/train.py --images /path/to/corpus --epochs 20
    # no corpus? runs on procedural textures (smoke test):
    python3 scripts/distortion_head/train.py --steps 50 --batch 8
"""

import argparse
import pathlib

import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader

from data import SyntheticHomographyDataset
from model import DistortionHead
from superpoint_backbone import FrozenSuperPoint

POSE_NORM = torch.tensor([90.0, 2.0, 180.0])  # tilt_deg, log2_scale, roll_deg


def masked_smooth_l1(pred, tgt, mask):
    per = F.smooth_l1_loss(pred, tgt, reduction="none")
    per = per.mean(dim=tuple(range(1, per.dim()))) if per.dim() > 1 else per
    denom = mask.sum().clamp(min=1.0)
    return (per * mask).sum() / denom


def step_loss(out, batch, w, device):
    pose_norm = POSE_NORM.to(device)
    m = batch["matchable"]
    losses = {
        "corners": masked_smooth_l1(out["corners"], batch["corners"], m),
        "pose": masked_smooth_l1(out["pose"] / pose_norm, batch["pose"] / pose_norm, m),
        "coverage": masked_smooth_l1(out["coverage"], batch["coverage"], m),
        "match": F.binary_cross_entropy_with_logits(out["matchability"], m),
    }
    total = sum(w[k] * v for k, v in losses.items())
    return total, losses


@torch.no_grad()
def evaluate(backbone, head, loader, grid, device, max_batches=20):
    head.eval()
    corner_px, tilt_e, scale_e, n_pos = 0.0, 0.0, 0.0, 0
    probs, labels = [], []
    P = loader.dataset.P
    for i, b in enumerate(loader):
        if i >= max_batches:
            break
        cur, fp = b["cur"].to(device), b["fp"].to(device)
        out = head(backbone.features_at(cur, grid), backbone.features_at(fp, grid))
        m = b["matchable"].bool()
        probs.append(torch.sigmoid(out["matchability"]).cpu())
        labels.append(b["matchable"])
        if m.any():
            ce = (out["corners"][m].cpu() - b["corners"][m]).abs().mean().item() * P
            pe = (out["pose"][m].cpu() - b["pose"][m]).abs()
            corner_px += ce * m.sum().item()
            tilt_e += pe[:, 0].sum().item()
            scale_e += pe[:, 1].sum().item()
            n_pos += m.sum().item()
    probs, labels = torch.cat(probs), torch.cat(labels)
    auc = roc_auc(probs, labels)
    head.train()
    n = max(n_pos, 1)
    return {"corner_px": corner_px / n, "tilt_mae": tilt_e / n,
            "log2scale_mae": scale_e / n, "match_auc": auc}


def roc_auc(scores: torch.Tensor, labels: torch.Tensor) -> float:
    pos = scores[labels > 0.5]
    neg = scores[labels <= 0.5]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    # rank-based AUC = P(score_pos > score_neg)
    order = torch.argsort(scores)
    ranks = torch.empty_like(order, dtype=torch.float)
    ranks[order] = torch.arange(1, len(scores) + 1, dtype=torch.float)
    r_pos = ranks[labels > 0.5].sum()
    n_p, n_n = len(pos), len(neg)
    return float((r_pos - n_p * (n_p + 1) / 2) / (n_p * n_n))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", type=str, default=None, help="image corpus dir (optional)")
    ap.add_argument("--patch", type=int, default=256)
    ap.add_argument("--grid", type=int, default=32, help="desc grid (patch/8)")
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--steps", type=int, default=0, help="if >0, cap steps/epoch (smoke test)")
    ap.add_argument("--train-len", type=int, default=20000)
    ap.add_argument("--val-len", type=int, default=2000)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--out", type=str, default="scripts/distortion_head/checkpoints")
    ap.add_argument("--weights", type=str, default=None, help="SuperPoint .pth (optional)")
    args = ap.parse_args()

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[train] device={device}")

    backbone = FrozenSuperPoint(args.weights).to(device).eval()
    head = DistortionHead(grid=args.grid).to(device)
    opt = torch.optim.AdamW(head.parameters(), lr=args.lr, weight_decay=1e-4)

    train_ds = SyntheticHomographyDataset(args.images, args.patch, args.train_len, seed=1)
    val_ds = SyntheticHomographyDataset(args.images, args.patch, args.val_len, seed=999)
    train_dl = DataLoader(train_ds, args.batch, shuffle=True, num_workers=args.workers,
                          drop_last=True)
    val_dl = DataLoader(val_ds, args.batch, shuffle=False, num_workers=args.workers)

    w = {"corners": 1.0, "pose": 1.0, "coverage": 0.5, "match": 0.5}
    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    best = float("inf")

    for ep in range(args.epochs):
        head.train()
        running = 0.0
        for it, b in enumerate(train_dl):
            if args.steps and it >= args.steps:
                break
            cur, fp = b["cur"].to(device), b["fp"].to(device)
            b = {k: (v.to(device) if torch.is_tensor(v) else v) for k, v in b.items()}
            out = head(backbone.features_at(cur, args.grid),
                       backbone.features_at(fp, args.grid))
            total, parts = step_loss(out, b, w, device)
            opt.zero_grad()
            total.backward()
            opt.step()
            running += total.item()
            if it % 50 == 0:
                msg = " ".join(f"{k}={v.item():.3f}" for k, v in parts.items())
                print(f"  ep{ep} it{it} loss={total.item():.3f} | {msg}")

        metrics = evaluate(backbone, head, val_dl, args.grid, device)
        print(f"[val] ep{ep} " + " ".join(f"{k}={v:.3f}" for k, v in metrics.items()))

        torch.save(head.state_dict(), out_dir / "head_last.pth")
        if metrics["corner_px"] < best:
            best = metrics["corner_px"]
            torch.save(head.state_dict(), out_dir / "head_best.pth")
            print(f"  [✓] new best corner_px={best:.2f} → head_best.pth")

    print(f"[done] best corner_px={best:.2f}. Export with export_onnx.py")


if __name__ == "__main__":
    main()
