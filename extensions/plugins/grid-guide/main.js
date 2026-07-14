import { defineFilter } from "@azphalt/sdk";

/**
 * Grid Guide — overlay a proportional grid on the layer (the "grid method" GraffitiXR is built on),
 * so a sketch scales up onto a wall square by square. Draws cols×rows major cells with optional
 * subdivisions, alpha-blended in a chosen colour over the underlying art.
 */
export const grid = defineFilter((ctx) => {
  const cols = Math.max(1, Math.round(ctx.params.number("cols")));
  const rows = Math.max(1, Math.round(ctx.params.number("rows")));
  const sub = Math.max(0, Math.round(ctx.params.number("subdivisions")));
  const thick = Math.max(1, Math.round(ctx.params.number("thickness")));
  const line = ctx.params.color("color");
  const opacity = Math.min(1, Math.max(0, ctx.params.number("opacity")));
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
