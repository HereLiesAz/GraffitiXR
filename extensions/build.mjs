// Build every GraffitiXR extension / pack into a signed-shape `.azp` using the azphalt SDK
// (`@azphalt/azp` `writeAzp` computes the integrity digests; `verifyAzp` asserts each package is
// well-formed before it lands in dist/). Run: `npm install && npm run build`.
import { writeAzp, verifyAzp } from "@azphalt/azp";
import { readFileSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { brushTip, splatterTip, grayPng } from "./lib/png.mjs";
import { cubeLut, LOOKS } from "./lib/lut.mjs";

const ROOT = dirname(fileURLToPath(import.meta.url));
const DIST = join(ROOT, "dist");
const enc = (s) => new TextEncoder().encode(s);
const read = (p) => readFileSync(join(ROOT, p));

const APP = "com.hereliesaz.graffitixr";
const AUTHOR = "hereliesaz";
const HOMEPAGE = "https://github.com/HereLiesAz/GraffitiXR";

const MIT = `MIT License

Copyright (c) 2026 hereliesaz

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated files, to deal in the Software without
restriction, including the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies, subject to the following: the
above copyright notice and this permission notice shall be included in all
copies. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
`;

const CC0 = `Creative Commons CC0 1.0 Universal — Public Domain Dedication.

To the extent possible under law, hereliesaz has waived all copyright and
related rights to these assets. You may copy, modify, distribute, and use
them, even commercially, without asking permission.
Full text: https://creativecommons.org/publicdomain/zero/1.0/legalcode
`;

const reset = () => {
  rmSync(DIST, { recursive: true, force: true });
  mkdirSync(DIST, { recursive: true });
};

const index = [];
function pack(fileBase, { manifest, payload, license }) {
  const { azp, manifest: full } = writeAzp({ manifest, payload, license });
  const v = verifyAzp(azp);
  if (!v.ok) throw new Error(`verifyAzp failed for ${manifest.id}: ${v.errors.join("; ")}`);
  const file = `${fileBase}.azp`;
  writeFileSync(join(DIST, file), azp);
  index.push({ id: full.id, name: full.name, kind: full.kind, license: full.license, file, bytes: azp.length });
  console.log(`  ✓ ${file.padEnd(34)} ${full.kind.padEnd(6)} ${azp.length.toLocaleString()} B  ${full.id}`);
}

/* ─────────────────────────── code plugins (kind: "code") ─────────────────────────── */

const PLUGINS = [
  { dir: "stencil-separator", id: "stencil-separator", name: "Stencil Separator", entry: "separate",
    desc: "Reduce a layer to 1–4 flat stencil tones by luminance — print, cut, spray." },
  { dir: "halftone", id: "halftone", name: "Halftone", entry: "halftone",
    desc: "Ink-dot halftone on a rotated screen; the classic print / stencil stipple." },
  { dir: "grid-guide", id: "grid-guide", name: "Grid Guide", entry: "grid",
    desc: "Overlay a proportional grid (the grid method) to scale a sketch onto a wall." },
];

function buildPlugins() {
  console.log("code plugins:");
  for (const p of PLUGINS) {
    const main = read(`plugins/${p.dir}/main.js`);
    const ui = read(`plugins/${p.dir}/ui.json`);
    pack(`plugin-${p.id}`, {
      license: MIT,
      manifest: {
        azphalt: "0.1",
        id: `${APP}.${p.id}`,
        name: p.name,
        version: "1.0.0",
        kind: "code",
        license: "MIT",
        author: AUTHOR,
        description: p.desc,
        homepage: HOMEPAGE,
        compat: ">=0.1",
        targetApps: [APP],
        entry: "code/main.js",
        runtime: "js",
        capabilities: ["bitmap", "params", "canvas"],
        contributes: { filters: [{ id: p.id, name: p.name, entry: p.entry, ui: "ui/panel.json" }] },
      },
      payload: { "code/main.js": main, "ui/panel.json": ui },
    });
  }
}

/* ─────────────────────────── smart brush sets (kind: "asset", type: "brush") ─────────────────────────── */
// Params schema (proposed; no normative brush schema exists yet — see azphalt#40): all normalized.
//   spacing 0..1 · flow 0..1 · hardness 0..1 · roundness 0..1 · angle deg · sizeJitter/angleJitter/scatter 0..1
//   dynamics: { size|flow|angle : "pressure"|"tilt"|"velocity" }  ← the "smart"/dynamic mapping

const TIP = 256;
const brushUi = (extra = []) => enc(JSON.stringify({
  controls: [
    { type: "slider", key: "size", label: "Size", min: 1, max: 400, step: 1, default: 64 },
    { type: "slider", key: "spacing", label: "Spacing", min: 0.01, max: 1, step: 0.01, default: 0.1 },
    { type: "slider", key: "flow", label: "Flow", min: 0, max: 1, step: 0.01, default: 0.8 },
    { type: "slider", key: "scatter", label: "Scatter", min: 0, max: 1, step: 0.01, default: 0 },
    ...extra,
  ],
}));

const BRUSHES = [
  {
    id: "spray-can", name: "Spray Can", tags: ["spray", "graffiti", "aerosol"],
    desc: "Soft aerosol tips with grain and spatter — fine cap, fat cap, and a spatter burst.",
    tips: [
      { file: "fine-cap.png", px: () => brushTip(TIP, "soft", { hardness: 0.7, grain: 0.15, seed: 11 }),
        params: { spacing: 0.05, flow: 0.65, hardness: 0.7, roundness: 1, sizeJitter: 0.08, scatter: 0.1, dynamics: { size: "pressure", flow: "velocity" } } },
      { file: "fat-cap.png", px: () => brushTip(TIP, "soft", { hardness: 0.35, grain: 0.28, seed: 22 }),
        params: { spacing: 0.06, flow: 0.8, hardness: 0.35, roundness: 1, sizeJitter: 0.12, scatter: 0.18, dynamics: { size: "pressure", flow: "velocity" } } },
      { file: "spatter.png", px: () => splatterTip(TIP, { seed: 33, dots: 70, drips: 4 }),
        params: { spacing: 0.35, flow: 0.9, scatter: 0.5, sizeJitter: 0.3, dynamics: { flow: "velocity" } } },
    ],
  },
  {
    id: "marker", name: "Marker & Paint Pen", tags: ["marker", "ink", "handstyle"],
    desc: "Crisp opaque tips for handstyles and outlines — bullet and chisel.",
    tips: [
      { file: "bullet.png", px: () => brushTip(TIP, "hard", { hardness: 0.9, seed: 41 }),
        params: { spacing: 0.02, flow: 0.98, hardness: 0.9, roundness: 1, dynamics: { flow: "velocity" } } },
      { file: "chisel.png", px: () => brushTip(TIP, "hard", { hardness: 0.85, roundness: 0.35, angle: 45, seed: 42 }),
        params: { spacing: 0.02, flow: 1, hardness: 0.85, roundness: 0.35, angle: 45, dynamics: { angle: "tilt" } } },
    ],
  },
  {
    id: "chalk", name: "Chalk & Pastel", tags: ["chalk", "pastel", "drymedia"],
    desc: "Dry-media tips with heavy tooth for sketch and blocking.",
    tips: [
      { file: "chalk-soft.png", px: () => brushTip(TIP, "chalk", { hardness: 0.4, grain: 0.45, seed: 51 }),
        params: { spacing: 0.08, flow: 0.7, hardness: 0.4, roundness: 0.9, scatter: 0.05, dynamics: { flow: "pressure" } } },
      { file: "chalk-block.png", px: () => brushTip(TIP, "chalk", { hardness: 0.5, grain: 0.35, roundness: 0.6, angle: 20, seed: 52 }),
        params: { spacing: 0.1, flow: 0.85, hardness: 0.5, roundness: 0.6, angle: 20, dynamics: { flow: "pressure" } } },
    ],
  },
  {
    id: "roller", name: "Roller & Fill", tags: ["roller", "fill", "buff"],
    desc: "Broad, slightly textured tips for filling and buffing large areas fast.",
    tips: [
      { file: "roller-flat.png", px: () => brushTip(TIP, "fill", { hardness: 0.6, grain: 0.08, roundness: 0.4, angle: 0, seed: 61 }),
        params: { spacing: 0.12, flow: 1, hardness: 0.6, roundness: 0.4, dynamics: {} } },
      { file: "roller-tex.png", px: () => brushTip(TIP, "fill", { hardness: 0.5, grain: 0.22, roundness: 0.45, seed: 62 }),
        params: { spacing: 0.15, flow: 0.95, hardness: 0.5, roundness: 0.45, dynamics: {} } },
    ],
  },
  {
    id: "splatter", name: "Splatter & Drips", tags: ["splatter", "drips", "texture"],
    desc: "Randomized spatter and runs for grit, edges, and stencil overspray.",
    tips: [
      { file: "spatter-dense.png", px: () => splatterTip(TIP, { seed: 71, dots: 120, drips: 0, maxR: 5 }),
        params: { spacing: 0.4, flow: 0.9, scatter: 0.7, sizeJitter: 0.4, dynamics: { size: "velocity" } } },
      { file: "drips.png", px: () => splatterTip(TIP, { seed: 72, dots: 18, drips: 8, maxR: 4 }),
        params: { spacing: 0.6, flow: 1, scatter: 0.6, sizeJitter: 0.5, dynamics: { size: "velocity" } } },
    ],
  },
];

function buildBrushes() {
  console.log("brush sets:");
  for (const set of BRUSHES) {
    const payload = { "ui/brush.json": brushUi() };
    const assets = [];
    for (const tip of set.tips) {
      const path = `assets/${tip.file}`;
      payload[path] = grayPng(TIP, TIP, tip.px());
      assets.push({ type: "brush", path, ui: "ui/brush.json", tags: set.tags, params: tip.params });
    }
    pack(`brush-${set.id}`, {
      license: CC0,
      manifest: {
        azphalt: "0.1",
        id: `${APP}.brush.${set.id}`,
        name: set.name,
        version: "1.0.0",
        kind: "asset",
        license: "CC0-1.0",
        author: AUTHOR,
        description: set.desc,
        homepage: HOMEPAGE,
        compat: ">=0.1",
        targetApps: [APP],
        assets,
      },
      payload,
    });
  }
}

/* ─────────────────────────── filter / effect LUTs (kind: "asset", type: "lut") ─────────────────────────── */

const LUTS = {
  "concrete-cool": "Cool, gently desaturated grade for previewing on grey concrete.",
  "brick-warm": "Warm grade that flatters red / orange brick.",
  "neon-night": "High-contrast night look — crushed blacks, cyan/magenta split, punchy saturation.",
  "sun-bleached": "Faded daylight — lifted blacks, low contrast, warm highlights.",
  "teal-orange-street": "Cinematic street grade — teal shadows, orange highlights.",
};

const strengthUi = enc(JSON.stringify({
  controls: [{ type: "slider", key: "strength", label: "Strength", min: 0, max: 1, step: 0.01, default: 1 }],
}));

function buildLuts() {
  console.log("filter / effect LUTs:");
  for (const [id, desc] of Object.entries(LUTS)) {
    const name = id.split("-").map((w) => w[0].toUpperCase() + w.slice(1)).join(" ");
    pack(`lut-${id}`, {
      license: CC0,
      manifest: {
        azphalt: "0.1",
        id: `${APP}.lut.${id}`,
        name,
        version: "1.0.0",
        kind: "asset",
        license: "CC0-1.0",
        author: AUTHOR,
        description: desc,
        homepage: HOMEPAGE,
        compat: ">=0.1",
        targetApps: [APP],
        assets: [
          { type: "lut", path: "assets/grade.cube", ui: "ui/grade.json", tags: ["grade", "color"],
            params: { format: "cube", strength: 1.0 } },
        ],
      },
      payload: {
        "assets/grade.cube": enc(cubeLut(name, LOOKS[id], 17)),
        "ui/grade.json": strengthUi,
      },
    });
  }
}

reset();
buildPlugins();
buildBrushes();
buildLuts();
writeFileSync(join(DIST, "index.json"), JSON.stringify({ generated: "azphalt-sdk build", packages: index }, null, 2) + "\n");
console.log(`\nBuilt ${index.length} packages → extensions/dist/  (total ${index.reduce((n, p) => n + p.bytes, 0).toLocaleString()} B)`);
