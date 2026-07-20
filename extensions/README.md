# GraffitiXR extensions & packs

Thirteen [azphalt](https://github.com/HereLiesAz/azphalt) packages built with the azphalt SDK
(`@azphalt/azp`), scoped to GraffitiXR (`targetApps: ["com.hereliesaz.graffitixr"]`):

- **3 code plugins** — `defineFilter` extensions for real graffiti workflows.
- **5 smart brush sets** — procedural/dynamic brush tips (PNG) with normalized params.
- **5 filter/effect LUTs** — `.cube` colour grades tuned for previewing murals on walls.

Every package is emitted by [`build.mjs`](build.mjs), which computes each `.azp`'s integrity digests
with the SDK's `writeAzp` and asserts `verifyAzp(...)` before writing it to [`dist/`](dist). See
[`dist/index.json`](dist/index.json) for the manifest.

## Build

```bash
cd extensions
npm install     # installs @azphalt/azp
npm run build   # → dist/*.azp
```

> **Note on the install override.** `@azphalt/azp@0.1.0` pins `@azphalt/sdk@0.1.0`, but only
> `@azphalt/sdk@0.1.1` is published — so `package.json` carries an `overrides` entry forcing
> `@azphalt/sdk` to `0.1.1`. `@azphalt/azp` uses the SDK only for types (erased at runtime), so the
> packager is unaffected. (Worth fixing upstream: azp should pin `^0.1.0` or be republished.)

## What runs in GraffitiXR today

GraffitiXR is currently an **asset-only host** (`AzpInstaller` applies `.cube` LUTs and **rejects
`kind:"code"`**):

| Pack | Kind | In GraffitiXR today |
|---|---|---|
| Filter/effect LUTs | `asset` / `lut` | ✅ **Apply now** via the `CubeLut` engine |
| Smart brush sets | `asset` / `brush` | ⚠️ Installs & parses; the asset-apply path is LUT-only today, so tips aren't rendered until the design engine consumes `brush` assets |
| Code plugins | `code` | ⛔ Rejected by the asset-only installer — need the azphalt **code-host profile** (QuickJS-in-WASM sandbox) to run |

The code plugins are authored as fully conforming azphalt `code` extensions; adopting the code-host
profile (`@azphalt/runtime-wasm` + the capability grant model, per `docs/ADOPTION.md` in azphalt) is
what lights them up. They're built now so that work has concrete, tested payloads to target.

---

## Code plugins (MIT)

| id | Filter | Params |
|---|---|---|
| `…graffitixr.stencil-separator` | **Stencil Separator** — reduce a layer to 1–4 flat tones by luminance (cut-stencil prep) | `colors` 2–4, `contrast`, `invert` |
| `…graffitixr.halftone` | **Halftone** — ink dots on a rotated screen, sized by darkness | `cellSize`, `angle`, `shape`, `ink`, `paper` |
| `…graffitixr.grid-guide` | **Grid Guide** — overlay a proportional grid (the grid method) | `cols`, `rows`, `subdivisions`, `thickness`, `opacity`, `color` |

Each declares only `["bitmap", "params", "canvas"]`, carries a UI panel (`spec/ui-schema.md`), and
operates purely on the editor-surface bitmap — nothing on the never-list.

## Smart brush sets (CC0-1.0)

Procedural 256×256 grayscale tips (the value is the coverage mask), generated deterministically so
rebuilds reproduce byte-for-byte. Each tip declares a **normalized param block** — a *proposed* brush
schema (there's no normative one yet; see azphalt#40):

```
spacing 0..1 · flow 0..1 · hardness 0..1 · roundness 0..1 · angle° ·
sizeJitter/angleJitter/scatter 0..1 · dynamics { size|flow|angle : "pressure"|"tilt"|"velocity" }
```

The `dynamics` map is what makes them "smart" — a property driven by pressure, tilt, or stroke
velocity.

| Set | Tips |
|---|---|
| **Spray Can** | fine cap, fat cap (grain), spatter — pressure→size, velocity→flow |
| **Marker & Paint Pen** | bullet, chisel (tilt→angle) |
| **Chalk & Pastel** | soft, block — pressure→flow, heavy tooth |
| **Roller & Fill** | flat, textured — broad fills |
| **Splatter & Drips** | dense spatter, drips — velocity→size |

## Filter / effect LUTs (CC0-1.0)

17³ `.cube` 3D LUTs + a canonical `strength` slider panel (the 0–100% blend proposed in azphalt#40).

| id | Look |
|---|---|
| `…lut.concrete-cool` | Cool, desaturated — grey concrete walls |
| `…lut.brick-warm` | Warm — red/orange brick |
| `…lut.neon-night` | Crushed blacks, cyan/magenta split, punchy |
| `…lut.sun-bleached` | Faded daylight, warm highlights |
| `…lut.teal-orange-street` | Cinematic teal shadows / orange highlights |

## Licensing

Code plugins are **MIT**; all assets (brushes, LUTs) are **CC0-1.0** (public domain) — open source and
free for any use, commercial included. Each `.azp` carries its own SPDX `license` and `LICENSE` file.
