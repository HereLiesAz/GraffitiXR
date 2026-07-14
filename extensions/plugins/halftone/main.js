import { defineFilter } from "@azphalt/sdk";

/**
 * Halftone — resample the layer into ink dots on a rotated screen, each dot sized by local darkness.
 * Monochrome (single ink over paper) — the classic print / stencil stipple. Straight-alpha RGBA.
 */
export const halftone = defineFilter((ctx) => {
  const cell = Math.max(2, ctx.params.number("cellSize"));
  const angle = (ctx.params.number("angle") * Math.PI) / 180;
  const square = ctx.params.string("shape") === "square";
  const ink = ctx.params.color("ink");
  const paper = ctx.params.color("paper");
  const bmp = ctx.bitmap.read(ctx.target);
  const { width: w, height: h } = bmp;
  const src = bmp.data;
  const lum = (x, y) => {
    x = Math.min(w - 1, Math.max(0, x | 0));
    y = Math.min(h - 1, Math.max(0, y | 0));
    const i = (y * w + x) * 4;
    return (0.2126 * src[i] + 0.7152 * src[i + 1] + 0.0722 * src[i + 2]) / 255;
  };
  const out = new Uint8ClampedArray(src.length);
  for (let i = 0; i < out.length; i += 4) {
    out[i] = paper.r;
    out[i + 1] = paper.g;
    out[i + 2] = paper.b;
    out[i + 3] = src[i + 3];
  }
  const ca = Math.cos(angle);
  const sa = Math.sin(angle);
  const diag = Math.ceil(Math.hypot(w, h) / cell) + 2;
  for (let gy = -diag; gy < diag; gy++) {
    for (let gx = -diag; gx < diag; gx++) {
      const lx = gx * cell;
      const ly = gy * cell;
      const cxp = lx * ca - ly * sa + w / 2;
      const cyp = lx * sa + ly * ca + h / 2;
      if (cxp < -cell || cxp > w + cell || cyp < -cell || cyp > h + cell) continue;
      const dark = 1 - lum(cxp, cyp);
      const r = (square ? cell : cell * 0.62) * Math.sqrt(dark);
      if (r < 0.4) continue;
      const r0 = Math.ceil(r);
      for (let dy = -r0; dy <= r0; dy++) {
        for (let dx = -r0; dx <= r0; dx++) {
          const inside = square ? Math.max(Math.abs(dx), Math.abs(dy)) <= r : dx * dx + dy * dy <= r * r;
          if (!inside) continue;
          const x = (cxp + dx) | 0;
          const y = (cyp + dy) | 0;
          if (x < 0 || x >= w || y < 0 || y >= h) continue;
          const i = (y * w + x) * 4;
          out[i] = ink.r;
          out[i + 1] = ink.g;
          out[i + 2] = ink.b;
        }
      }
    }
  }
  bmp.data.set(out);
  ctx.bitmap.write(ctx.target, bmp);
  ctx.canvas.requestRedraw();
});
