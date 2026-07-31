#!/usr/bin/env python3
"""Recompute PAPER.md 8.1's error table, and the 8.2 orientation table.

Run with no arguments; it prints both tables and asserts them against the
published figures, exiting non-zero on any mismatch.

    python3 docs/research/reproduce_8_1.py

WHY THIS FILE EXISTS. 8.1 opens by complaining that a figure was asserted from a
source comment for several development cycles with no measurement retained. The
correction then asserted a *reproduction* with no artifact retained either, which
is the same failure one level up. This is the artifact.

WHAT IT DOES AND DOES NOT ESTABLISH. It evaluates t = (n.p)/(n.d) — backProject's
own expression — for both the mismatched and the true frame, differing only in
which frame n is expressed in. A sign error, a swapped numerator, or the inverse
applied on the wrong side IN THE MODEL OF THE MISMATCH would reproduce the table
exactly. So this confirms the sampling and the statistics (the 40x40 grid, the
0.1-10 m trust filter, p95 = sorted[floor(0.95N)]) — which is where the earlier
3.3x error lived — and NOT the geometry.

For an independent check of the geometry, see PlaneMarksObliquityTest, which
constructs its truth forward: it places points on the wall in the wall's own
basis and projects them to pixels, so the truth path never evaluates the
ray-plane intersection under test.

THE TWO CONVENTIONS the table depends on and did not state:
  * "a wall at 2 m" is the PERPENDICULAR distance to the plane, not the distance
    along the optical axis. Reading it the other way scales every row by cos(t):
    251 mm rather than 267 mm at 20 degrees.
  * the wall is tilted about the camera's Y axis. At rotationNeeded=90 the tilt
    axis does not matter; at 180 it does (265 mm vs 486 mm), and at 60 degrees it
    decides whether the depth filter drops anything at all.
"""

import math
import sys

W, H = 1080.0, 1920.0          # display-oriented frame
FX = FY = 1400.0
CX, CY = W / 2.0, H / 2.0
DIST = 2.0                     # PERPENDICULAR distance to the wall, metres
NG = 40                        # 40x40 grid
FRAC = 0.8                     # central 80% of the frame
MIN_D, MAX_D = 0.1, 10.0       # backProject's own trust range
EPS = 1e-6

# PAPER.md 8.1, rotationNeeded=90, tilt about camera Y: mean mm, p95 mm, surviving.
PAPER_8_1 = {
    0:  (0,    0,     1600),
    10: (121,  282,   1600),
    20: (267,  630,   1600),
    30: (475,  1154,  1600),
    40: (834,  2213,  1600),
    50: (1650, 5260,  1600),
    60: (2107, 5635,  1280),
}
PAPER_8_1_UNFILTERED_60 = (6914, 37386)
PAPER_8_2 = {0: 0.0, 90: 267.0, 180: 265.0}      # 20 deg obliquity, tilt about Y
PAPER_8_2_180_X_TILT = 486.0


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def rz_transpose_apply(deg, v):
    """Rz(deg)^T applied to v. The view matrix is camera.pose.inverse(), i.e. the
    sensor frame, while the rays are built from display-oriented intrinsics. The
    normal backProject actually dots with is therefore Rz^T n_display."""
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    # Rz = [[c,-s,0],[s,c,0],[0,0,1]]; Rz^T v = (c*vx + s*vy, -s*vx + c*vy, vz)
    return (c * v[0] + s * v[1], -s * v[0] + c * v[1], v[2])


def grid():
    lo, hi = (1 - FRAC) / 2, 1 - (1 - FRAC) / 2
    step = lambda i: lo + (hi - lo) * i / (NG - 1)
    return [(W * step(i), H * step(j)) for i in range(NG) for j in range(NG)]


def errors_mm(obliquity_deg, rotation_deg, axis="y", apply_filter=True):
    th = math.radians(obliquity_deg)
    n_d = (math.sin(th), 0.0, -math.cos(th)) if axis == "y" else (0.0, math.sin(th), -math.cos(th))
    p_d = (0.0, 0.0, DIST / math.cos(th))          # perpendicular distance == DIST
    n_s = rz_transpose_apply(rotation_deg, n_d)     # what the code dots with
    num = dot(n_d, p_d)                             # rotation-invariant; same either way

    errs, dropped = [], 0
    for (u, v) in grid():
        d = ((u - CX) / FX, (v - CY) / FY, 1.0)
        den_buggy, den_true = dot(n_s, d), dot(n_d, d)
        if abs(den_buggy) < EPS or abs(den_true) < EPS:
            dropped += 1
            continue
        t_buggy = num / den_buggy
        if apply_filter and not (MIN_D <= t_buggy <= MAX_D):
            dropped += 1                            # exactly what backProject discards
            continue
        # Same ray, wrong depth: the error is along d.
        errs.append(abs(t_buggy - num / den_true) * math.sqrt(dot(d, d)))
    return errs, dropped


def p95(xs):
    return sorted(xs)[int(math.floor(0.95 * len(xs)))]


def main():
    failures = []

    def check(label, got, want, tol):
        ok = abs(got - want) <= tol
        if not ok:
            failures.append(f"{label}: got {got:.1f}, published {want}")
        return "ok" if ok else "MISMATCH"

    print("PAPER.md 8.1 — rotationNeeded=90, wall tilted about camera Y, 2 m perpendicular")
    print(f"{'obliq':>6} {'mean mm':>9} {'p95 mm':>9} {'surviving':>12}  {'vs paper':>9}")
    for deg, (pm, pp, ps) in PAPER_8_1.items():
        e, dropped = errors_mm(deg, 90)
        n = len(e)
        mean = sum(e) / n
        v = p95(e)
        verdict = check(f"8.1 {deg}deg mean", mean * 1000, pm, 1.0)
        check(f"8.1 {deg}deg p95", v * 1000, pp, 1.0)
        if n != ps:
            failures.append(f"8.1 {deg}deg surviving: got {n}, published {ps}")
        print(f"{deg:>5}° {mean*1000:>9.0f} {v*1000:>9.0f} {n:>6}/{n+dropped}  {verdict:>9}")

    e, _ = errors_mm(60, 90, apply_filter=False)
    um, up = sum(e) / len(e) * 1000, p95(e) * 1000
    print(f"\nunfiltered 60°: mean {um:.0f} mm, p95 {up:.0f} mm "
          f"(published {PAPER_8_1_UNFILTERED_60[0]} / {PAPER_8_1_UNFILTERED_60[1]}) — "
          f"{check('8.1 unfiltered 60 mean', um, PAPER_8_1_UNFILTERED_60[0], 1.0)}")
    check("8.1 unfiltered 60 p95", up, PAPER_8_1_UNFILTERED_60[1], 1.0)

    print("\nPAPER.md 8.2 — 20° obliquity, tilt about camera Y")
    for rot, want in PAPER_8_2.items():
        e, _ = errors_mm(20, rot)
        mean = sum(e) / len(e) * 1000
        print(f"  rotationNeeded={rot:>3}: {mean:>7.1f} mm (published {want}) — "
              f"{check(f'8.2 rot={rot}', mean, want, 1.0)}")

    e, _ = errors_mm(20, 180, axis="x")
    mean_x = sum(e) / len(e) * 1000
    print(f"\n  ...and the same 180° case tilted about X: {mean_x:.1f} mm "
          f"(published {PAPER_8_2_180_X_TILT}) — "
          f"{check('8.2 rot=180 X-tilt', mean_x, PAPER_8_2_180_X_TILT, 1.0)}")
    print("  The tilt axis is load-bearing at 180° and irrelevant at 90°.")

    e_x, drop_x = errors_mm(60, 90, axis="x")
    print(f"  At 60° about X the filter drops {drop_x} points, not 320 — "
          f"mean {sum(e_x)/len(e_x)*1000:.0f} mm. EVALUATION.md's E0b "
          f"'1280 of 1600' prediction assumes the Y tilt.")
    if drop_x != 0:
        failures.append(f"60deg X-tilt expected 0 dropped, got {drop_x}")

    if failures:
        print("\nFAILED:")
        for f in failures:
            print("  -", f)
        return 1
    print("\nAll published figures reproduced.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
