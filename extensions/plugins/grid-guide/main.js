import { defineFilter } from "@azphalt/sdk";

/**
 * Grid Guide — overlay a proportional grid on the layer (the "grid method" GraffitiXR is built on),
 * so a sketch scales up onto a wall square by square. Draws cols×rows major cells with optional
 * subdivisions, alpha-blended in a chosen colour over the underlying art.
 */
export const grid = defineFilter((ctx) => {
  // Defensive reads: fall back to the panel default if the host omits a param, returns NaN, or throws.
  const num = (k, d) => { try { const v = ctx.params.number(k); return Number.isFinite(v) ? v : d; } catch { return d; } };
  const col = (k, d) => { try { const v = ctx.params.color(k); return v && Number.isFinite(v.r) ? v : d; } catch { return d; } };
  const cols = Math.max(1, Math.round(num("cols", 8)));
  const rows = Math.max(1, Math.round(num("rows", 8)));
  const sub = Math.max(0, Math.round(num("subdivisions", 0)));
  const thick = Math.max(1, Math.round(num("thickness", 1)));
  const line = col("color", { r: 0, g: 229, b: 255, a: 255 });
  const opacity = Math.min(1, Math.max(0, num("opacity", 0.8)));
  const bmp = ctx.bitmap.read(ctx.target);
  const { width: w, height: h } = bmp;
  const d = bmp.data;
  const paint = (x, y, strong) => {
    if (x < 0 || x >= w || y < 0 || y >= h) return;
    const i = (y * w + x) * 4;
    const a = opacity * (strong ? 1 : 0.5);
    d[i] = Math.round(d[i] * (1 - a) + line.r * a);
    d[i + 1] = Math.round(d[i + 1] * (1 - a) + line.g * a);
    d[i + 2] = Math.round(d[i + 2] * (1 - a) + line.b * a);
  };
  const vline = (x, strong) => {
    for (let t = 0; t < thick; t++) for (let y = 0; y < h; y++) paint(x + t, y, strong);
  };
  const hline = (y, strong) => {
    for (let t = 0; t < thick; t++) for (let x = 0; x < w; x++) paint(x, y + t, strong);
  };
  for (let c = 0; c <= cols; c++) {
    const x = Math.round((c * (w - thick)) / cols);
    vline(x, true);
    if (sub && c < cols) for (let s = 1; s <= sub; s++) vline(Math.round(x + (s * (w / cols)) / (sub + 1)), false);
  }
  for (let r = 0; r <= rows; r++) {
    const y = Math.round((r * (h - thick)) / rows);
    hline(y, true);
    if (sub && r < rows) for (let s = 1; s <= sub; s++) hline(Math.round(y + (s * (h / rows)) / (sub + 1)), false);
  }
  ctx.bitmap.write(ctx.target, bmp);
  ctx.canvas.requestRedraw();
});
