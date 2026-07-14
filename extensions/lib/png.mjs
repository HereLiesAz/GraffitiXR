// A tiny, dependency-free 8-bit **grayscale** PNG encoder — enough to emit azphalt `brush` tips
// (spec/package-format.md: brush tips are PNG, 8-bit gray or RGBA). Uses Node's built-in zlib for
// DEFLATE; CRC-32 is table-based here so the encoder runs on any Node (zlib.crc32 is only ≥ 22.2).
// The gray value IS the tip's coverage/alpha mask.
import { deflateSync } from "node:zlib";

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, "ascii");
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])) >>> 0, 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

/** Encode a `width×height` 8-bit grayscale image (`pixels`: Uint8Array/Array, length `width*height`). */
export function grayPng(width, height, pixels) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 0; // color type 0 = grayscale
  // ihdr[10..12] = compression/filter/interlace = 0
  const raw = Buffer.alloc((width + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width + 1)] = 0; // per-row filter: none
    for (let x = 0; x < width; x++) raw[y * (width + 1) + 1 + x] = pixels[y * width + x] & 0xff;
  }
  const idat = deflateSync(raw, { level: 9 });
  return Buffer.concat([sig, chunk("IHDR", ihdr), chunk("IDAT", idat), chunk("IEND", Buffer.alloc(0))]);
}

/** A small deterministic PRNG (mulberry32) so grain/scatter reproduce byte-for-byte across builds. */
export function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const clamp8 = (v) => (v < 0 ? 0 : v > 255 ? 255 : Math.round(v));
const smoothstep = (e0, e1, x) => {
  const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
  return t * t * (3 - 2 * t);
};

/**
 * Procedurally render a grayscale brush-tip mask. `shape` selects the family; `opts` tunes it.
 * Returned coverage is 0 (transparent) … 255 (full paint).
 */
export function brushTip(size, shape, opts = {}) {
  const { hardness = 0.5, grain = 0, seed = 1, roundness = 1, angle = 0 } = opts;
  const px = new Uint8Array(size * size);
  const rnd = mulberry32(seed);
  const cx = (size - 1) / 2;
  const cy = (size - 1) / 2;
  const rad = size / 2 - 1;
  const ca = Math.cos((angle * Math.PI) / 180);
  const sa = Math.sin((angle * Math.PI) / 180);
  // Pre-roll a static grain field so texture is stable per pixel.
  const grainField = new Float32Array(size * size);
  for (let i = 0; i < grainField.length; i++) grainField[i] = rnd();

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      // Rotate + squash into the tip's local frame for chisel/oval shapes.
      const dx0 = x - cx;
      const dy0 = y - cy;
      const dx = (dx0 * ca + dy0 * sa) / rad;
      const dy = ((-dx0 * sa + dy0 * ca) / rad) / Math.max(0.05, roundness);
      const r = Math.sqrt(dx * dx + dy * dy); // 0 at centre, ~1 at edge
      let v;
      switch (shape) {
        case "soft": // spray / airbrush — gaussian-ish falloff
          v = 255 * Math.exp(-(r * r) / (2 * (0.35 + hardness * 0.25) ** 2));
          break;
        case "hard": // marker / pen — crisp disc with a thin feathered edge
          v = 255 * smoothstep(1.0, 0.92 - hardness * 0.3, r);
          break;
        case "chalk": // dry media — disc eaten away by texture
          v = 255 * smoothstep(1.0, 0.6, r);
          break;
        case "fill": // roller — broad, nearly flat with a soft edge
          v = 255 * smoothstep(1.02, 0.7, r);
          break;
        default:
          v = 255 * smoothstep(1.0, 0.85, r);
      }
      if (grain > 0) {
        const g = grainField[y * size + x];
        // Multiplicative speckle: knock out coverage where the grain field is low.
        v *= 1 - grain * (1 - g) * (0.6 + 0.4 * r);
      }
      px[y * size + x] = clamp8(v);
    }
  }
  return px;
}

/** Scatter small dots/drips across a transparent field — splatter & stencil-edge tips. */
export function splatterTip(size, opts = {}) {
  const { seed = 1, dots = 40, minR = 1, maxR = 6, drips = 3 } = opts;
  const px = new Uint8Array(size * size);
  const rnd = mulberry32(seed);
  const stamp = (cx, cy, r, peak) => {
    // cx/cy are integers; keep the bounds (and thus the loop vars / index `i`) integers too, or a
    // fractional `i` silently no-ops every write into the typed array (the dots would vanish).
    const r0 = Math.ceil(r);
    for (let y = Math.max(0, cy - r0); y <= Math.min(size - 1, cy + r0); y++) {
      for (let x = Math.max(0, cx - r0); x <= Math.min(size - 1, cx + r0); x++) {
        const d = Math.hypot(x - cx, y - cy) / r;
        const v = peak * Math.max(0, 1 - d * d);
        const i = y * size + x;
        if (v > px[i]) px[i] = clamp8(v);
      }
    }
  };
  for (let i = 0; i < dots; i++) {
    const r = minR + rnd() * (maxR - minR);
    stamp((rnd() * size) | 0, (rnd() * size) | 0, r, 160 + rnd() * 95);
  }
  // A few vertical "drips" for that spray-paint run.
  for (let i = 0; i < drips; i++) {
    const x = (rnd() * size) | 0;
    const y0 = (rnd() * size * 0.4) | 0;
    const len = size * (0.2 + rnd() * 0.4);
    const w = (1 + rnd() * 2) | 0; // integer half-width → clean integer column iteration below
    for (let y = y0; y < Math.min(size, y0 + len); y++) {
      for (let dxi = -w; dxi <= w; dxi++) {
        const x2 = (x + dxi) | 0;
        if (x2 < 0 || x2 >= size) continue;
        const v = 180 * (1 - Math.abs(dxi) / (w + 1)) * (1 - (y - y0) / len);
        const idx = y * size + x2;
        if (v > px[idx]) px[idx] = clamp8(v);
      }
    }
  }
  return px;
}
