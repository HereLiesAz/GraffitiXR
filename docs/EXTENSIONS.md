# GraffitiXR — Extensions & Marketplace (Plan of Record)

*Recast around the actual mechanism: consuming the extensions that already exist, and building the portable
marketplace nobody else would. Business-model comparisons live in `RESEARCH.md`; this is what we implement.*

## The principle everything follows from

**Asset files are portable. Code extensions and marketplaces are not.** A brush or LUT is data and runs
anywhere; a code plugin is written against one host's private API and runs only there; a marketplace like
Clip Studio Assets or Canva is a closed silo locked to its own accounts and licenses. So there are two jobs:
*consume* the portable things that already exist, and *build* the portable thing that doesn't — an open,
cross-app extension standard any app can implement.

## Layer 1 — Consume what already exists (near-term, cheap)

The mechanism is **file import**, not marketplace APIs. Users buy brushes/LUTs as files (Creative Market,
Gumroad, Etsy, open repos) and import them; supporting the format *is* the integration. A day-one catalog
with no ecosystem required.

| Source format | Consume? | What transfers | What's lost | Where files come from |
|---|---|---|---|---|
| `.abr` — Photoshop brushes | **Yes (priority)** | Tip shape, grain/texture, many params | Exact stroke dynamics (engine-specific) | Creative Market, Gumroad, Envato, Adobe — the universal format |
| `.brushset` / `.brush` — Procreate | **Yes (with catch)** | Shape + grain PNGs, PLIST settings | Pressure/tilt/scatter dynamics (proprietary; no 1:1) | Creative Market, Gumroad, Etsy — unlocks Android buyers |
| `.kpp` / `.bundle` — Krita | **Yes (clean)** | Full brush preset (open format) | Little (open spec) | share.krita.org (free) |
| `.gbr` / `.gih` — GIMP | **Yes (clean)** | Brush bitmap / pipe (open) | Little | GIMP repositories (free) |
| `.cube` — LUTs | **Yes (faithful)** | Full color transform (1:1) | Nothing — the data is exact | Large free + paid LUT libraries |
| G'MIC filters | **Yes (embed engine)** | The whole filter library runs on the embedded engine | — (validate Android embed) | G'MIC community filter repo |
| `.8bf` — Photoshop code filters | **No** | — | — | native / desktop / proprietary / insecure |
| Canva · Figma · Clip Studio · VS Code extensions | **No** | — | — | host-bound to each app's runtime |

**The caveat that governs all brush import:** you consume the portable half — shape, grain, mappable
parameters — not the stroke engine. A clean 1:1 is impossible because the rendering math differs per app;
imported brushes land on *our* engine as a faithful-shape approximation, not a pixel-match. Framed as
"import your Photoshop and Procreate brushes," that's a feature; pretended perfect, it's a bug report.

**The Android wedge:** `.brushset` files are useless outside an iPad — Android buyers are left with data
their OS can't open. Importing `.brushset` unlocks brushes they already own, and nobody else serves them.

**Not consumable, and it's not a minor exclusion:** every code extension is bound to a host runtime —
Photoshop `.8bf`, Canva apps, Figma plugins, Clip Studio materials, VS Code extensions — and runs only
inside it. The closed marketplaces (Clip Studio Assets, Canva) have no third-party API and license-lock
assets to their own platform: shut technically and legally.

## Layer 2 — Build the marketplace nobody made (the ambition)

No one built an open, portable, monetizable extension marketplace for creative/mobile apps, because every
incumbent wanted its own walled garden. That's the opening. The goal is **not** a GraffitiXR store — it's an
open standard **any app can implement, so an extension written once runs everywhere it's adopted.** The
proven template is Open VSX: a vendor-neutral, open-source, self-hostable registry hosting one portable
format across many editors.

**Two artifact types, one registry:**
- **Portable asset packages** — normalized brushes / LUTs / patterns. Sell once, works in every adopting
  app. The direct answer to the fragmentation that makes creators re-sell the same brush five times.
- **Portable code extensions** — JS/WASM against the MIT extension API, sandboxed, cross-platform, safe.

**The insight that ties the two layers together:** the importers and the portable format are the same
project. Importing an `.abr` *is* normalizing it into GraffitiXR's brush representation — so if that
representation is the portable format, every import seeds the portable catalog. Define the format first
(even minimally), point the importers at it, and the marketplace fills as a byproduct of users bringing
brushes they already own.

**What makes it open and cross-app — and keeps the moat sealed:** the API exposes only *editor* extension
points, operations on layers, bitmaps, and the canvas. It exposes the commodity editing surface and never
the relocalization / teleological engine — which is precisely *why* it can be open and adoptable (there's no
secret in it) and why the engine stays PolyForm behind it. Everything in this layer is **MIT** (spec, SDK,
reference client, importers) so other apps adopt it without friction.

~~~
// SPDX-License-Identifier: MIT      (every file in the open extension layer)
~~~

**The hard part, stated honestly:** for *other* apps to adopt it, it has to be credibly neutral. A standard
seen as GraffitiXR-controlled won't be adopted by competitors — which is why Open VSX sits under a neutral
foundation, not a vendor. That's a real tension for a solo project: you seed it and benefit from it, but
control caps adoption. Design for neutrality — open spec, an open governance path — if cross-app reach is
actually the goal.

## Monetization

External payment + license keys at launch — the realistic path a solo operator ships, and (verified on
their own docs) the actual baseline in both Figma and Canva, whose SDKs don't do in-app purchases. A
platform-billed or usage-royalty-pool model is a later option only if scale ever justifies running the
rails. No walled payment garden.

## License layout (see `LICENSING.md`)

| Layer | License |
|---|---|
| App, core, AR / SLAM / teleological engine, engine-touching built-ins | **PolyForm Noncommercial** |
| Extension API / SDK / reference client / registry client | **MIT** |
| Asset importers (`.abr`, `.brushset`, Krita/GIMP, `.cube`) + the portable format | **MIT** |
| Commodity reference tools that don't touch the engine | **MIT** |

## Sequencing

- **Now (cheap):** define the minimal portable format; build importers that target it — `.abr` and `.cube`
  first (highest ROI, cleanest fidelity), `.brushset` next (the Android wedge), plus a G'MIC-embed spike for
  the effect side. This is the day-one catalog *and* the seed of the portable format.
- **Phase 2+ (follows an audience):** the registry, the code-extension sandbox, the cross-app SDK, and
  courting other apps' adoption. A marketplace with no users attracts no creators — build the importers now,
  let the marketplace follow the users from the B-then-A plan.

## Open build questions

- Sandbox runtime: JS engine vs WASM on Android; the capability / permission model for extensions.
- Registry: adopt or fork Open VSX's implementation vs build; hosting and moderation.
- Portable asset format: how much brush-dynamic metadata to normalize, given no 1:1 rendering across engines.
- Governance: the neutrality model required for genuine cross-app adoption.
- Monetization rails: external-payment-only vs an eventual platform-billed tier.
