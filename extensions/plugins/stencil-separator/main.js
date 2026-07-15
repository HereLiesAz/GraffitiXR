import { defineFilter } from "@azphalt/sdk";

/**
 * Stencil Separator — reduce the active layer to N flat tones by luminance: the core of a cut
 * stencil (1–4 colours). Each output band is a flat grey a muralist can print, cut, and spray in
 * one pass. Straight-alpha RGBA, mutated in place; alpha is preserved so masks survive.
 */
export const separate = defineFilter((ctx) => {
  // Defensive reads: fall back to the panel default if the host omits a param, returns NaN, or throws.
  const num = (k, d) => { try { const v = ctx.params.number(k); return Number.isFinite(v) ? v : d; } catch { return d; } };
  const bool = (k, d) => { try { const v = ctx.params.bool(k); return typeof v === "boolean" ? v : d; } catch { return d; } };
  const colors = Math.max(2, Math.min(4, Math.round(num("colors", 2))));
  const contrast = num("contrast", 1); // 0 (flat) … 2 (punchy), pivots around mid-grey
  const invert = bool("invert", false);
  const bmp = ctx.bitmap.read(ctx.target);
  const d = bmp.data;
  const tone = (band) => Math.round((band / (colors - 1)) * 255);
  for (let i = 0; i < d.length; i += 4) {
    let y = (0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2]) / 255;
    y = Math.min(1, Math.max(0, (y - 0.5) * (0.5 + contrast) + 0.5));
    let band = Math.round(y * (colors - 1));
    if (invert) band = colors - 1 - band;
    const v = tone(band);
    d[i] = v;
    d[i + 1] = v;
    d[i + 2] = v;
  }
  ctx.bitmap.write(ctx.target, bmp);
  ctx.canvas.requestRedraw();
});
