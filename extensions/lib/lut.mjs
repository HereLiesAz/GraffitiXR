// Generate `.cube` 3D LUTs (the normalized form azphalt ships colour grades as, applied by
// GraffitiXR's CubeLut engine). Each "look" is a pure RGB→RGB transform in normalized [0,1] space.

const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);
const mix = (a, b, t) => a + (b - a) * t;
const luma = (r, g, b) => 0.2126 * r + 0.7152 * g + 0.0722 * b;

/** Shared colour ops so the looks read like grading, not arithmetic. */
const ops = {
  /** Lift/gamma/gain per channel (shadows / midtones / highlights). */
  lgg: (c, lift, gamma, gain) => clamp01(Math.pow(clamp01(c * gain + lift * (1 - c)), 1 / gamma)),
  saturate: (r, g, b, s) => {
    const y = luma(r, g, b);
    return [clamp01(mix(y, r, s)), clamp01(mix(y, g, s)), clamp01(mix(y, b, s))];
  },
  contrast: (c, k, pivot = 0.5) => clamp01((c - pivot) * k + pivot),
  /** Split-tone: push shadows toward `sh` and highlights toward `hi` by luma. */
  splitTone: (r, g, b, sh, hi, amt) => {
    const y = luma(r, g, b);
    const t = y; // 0 shadow … 1 highlight
    const target = [mix(sh[0], hi[0], t), mix(sh[1], hi[1], t), mix(sh[2], hi[2], t)];
    return [clamp01(mix(r, r * (0.5 + target[0]), amt)), clamp01(mix(g, g * (0.5 + target[1]), amt)), clamp01(mix(b, b * (0.5 + target[2]), amt))];
  },
};

/** The five street-art looks. Each takes/returns rgb in [0,1]. */
export const LOOKS = {
  "concrete-cool": (r, g, b) => {
    // Cool, slightly desaturated grade for previewing on grey concrete: lift shadows, cool tint.
    let [rr, gg, bb] = ops.saturate(r, g, b, 0.82);
    rr = ops.lgg(rr, 0.02, 1.02, 0.96);
    gg = ops.lgg(gg, 0.03, 1.0, 0.98);
    bb = ops.lgg(bb, 0.06, 0.98, 1.04);
    return [rr, gg, bb];
  },
  "brick-warm": (r, g, b) => {
    // Warm grade that flatters red/orange brick; gentle contrast, boosted warmth.
    let [rr, gg, bb] = ops.saturate(r, g, b, 1.08);
    rr = ops.contrast(ops.lgg(rr, 0.03, 0.97, 1.06), 1.06);
    gg = ops.contrast(ops.lgg(gg, 0.02, 1.0, 1.0), 1.04);
    bb = ops.contrast(ops.lgg(bb, 0.0, 1.03, 0.93), 1.02);
    return [rr, gg, bb];
  },
  "neon-night": (r, g, b) => {
    // High-contrast night look: crushed blacks, punchy saturation, cyan/magenta split.
    let [rr, gg, bb] = ops.splitTone(r, g, b, [0.1, 0.2, 0.5], [0.6, 0.2, 0.5], 0.35);
    [rr, gg, bb] = ops.saturate(rr, gg, bb, 1.35);
    rr = ops.contrast(rr, 1.22, 0.42);
    gg = ops.contrast(gg, 1.22, 0.42);
    bb = ops.contrast(bb, 1.22, 0.42);
    return [rr, gg, bb];
  },
  "sun-bleached": (r, g, b) => {
    // Faded daylight: lifted blacks, reduced contrast, warm highlights, low saturation.
    let [rr, gg, bb] = ops.saturate(r, g, b, 0.78);
    rr = ops.contrast(ops.lgg(rr, 0.08, 1.05, 0.95), 0.9);
    gg = ops.contrast(ops.lgg(gg, 0.07, 1.05, 0.95), 0.9);
    bb = ops.contrast(ops.lgg(bb, 0.06, 1.08, 0.9), 0.9);
    return [rr, gg, bb];
  },
  "teal-orange-street": (r, g, b) => {
    // Cinematic street grade: teal shadows, orange highlights, moderate contrast + saturation.
    let [rr, gg, bb] = ops.splitTone(r, g, b, [0.0, 0.35, 0.45], [0.75, 0.4, 0.05], 0.3);
    [rr, gg, bb] = ops.saturate(rr, gg, bb, 1.15);
    rr = ops.contrast(rr, 1.1);
    gg = ops.contrast(gg, 1.08);
    bb = ops.contrast(bb, 1.1);
    return [rr, gg, bb];
  },
};

/** Render a look to `.cube` text (LUT_3D_SIZE `size`, red varying fastest per the .cube spec). */
export function cubeLut(name, look, size = 17) {
  const lines = [`TITLE "${name}"`, `LUT_3D_SIZE ${size}`, "DOMAIN_MIN 0.0 0.0 0.0", "DOMAIN_MAX 1.0 1.0 1.0", ""];
  const n = size - 1;
  for (let b = 0; b < size; b++) {
    for (let g = 0; g < size; g++) {
      for (let r = 0; r < size; r++) {
        const [or, og, ob] = look(r / n, g / n, b / n);
        lines.push(`${or.toFixed(6)} ${og.toFixed(6)} ${ob.toFixed(6)}`);
      }
    }
  }
  return lines.join("\n") + "\n";
}
