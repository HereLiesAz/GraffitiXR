# GraffitiXR — Extensions & Marketplace (Plan of Record)

*Decisions distilled from `RESEARCH.md` Part 1. This licenses and specifies the extension architecture
ahead of building it — deliberately, so the module and license boundaries are right before code exists.*

---

## The shape

Two economies, handled separately:

1. **Assets** (brushes, LUTs, materials) — adopt existing formats now for a day-one catalog.
2. **Code extensions** (tools, effects, filters) — build a portable, MIT, cross-app API + open registry,
   after there's an audience.

## Decisions

### Assets — import existing formats (do this early; it's cheap)

Import, don't invent. These are data, so they run on any platform and tap libraries the internet already
made — a stocked shelf before a single GraffitiXR-native extension exists.

| Format | Source ecosystem | Gives you |
|---|---|---|
| **ABR** | Adobe/Photoshop brushes | The largest brush library in existence |
| **`.brushset`** | Procreate | The mobile-artist brush economy |
| **`.cube`** | LUTs | The color-grade / filter interchange, huge free + paid |

Fidelity caveat: a foreign brush won't render identically without matching the brush engine; LUTs are the
most faithful. Import is still far more tractable than any code-plugin adoption, and it's the fastest path
to catalog.

### Code extensions — copy Open VSX, not `.8bf`

- **Portable sandbox: JS / WASM.** Cross-platform and safe (preserves the offline/no-tracking stance).
  Never native binaries (`.8bf` is desktop-only, proprietary, and a remote-code-execution risk).
- **Registry: vendor-neutral, open-source, self-hostable** — the Open VSX pattern. Same extension format
  usable by other apps, self-hosting for offline/curated use, no proprietary walled store.
- **Optional: integrate G'MIC** as a ready-made portable effect engine (500+ open filters) to seed the
  effect side.

### Monetization — the Figma de-facto model

Free discovery; creators bring their own payment + license keys (Gumroad / LemonSqueezy / MoR). MIT permits
closed, sold extensions. A Clip-Studio-style in-app currency with payouts is a later option if we ever want
to run the rails ourselves. No walled payment garden.

## The hard boundary (non-negotiable)

**The MIT extension API exposes only *editor* extension points — operations on layers, bitmaps, and the
canvas. It must never surface the relocalization / teleological engine.** Plugins extend the *editor*, not
the *tracking core*.

Consequences:
- The API is its own module (`:extension-api`) so the license line is a real module line.
- Built-ins that reach into the native engine (e.g. GPU Liquify → `SlamManager`) stay **PolyForm** privileged
  built-ins, not MIT. Only tools that operate purely on pixels/layers become MIT reference implementations.
- A competitor adopting the MIT API/format is fine — that's standard-setting, the VS Code outcome. It makes
  extensions portable both ways and gives the format gravity; they still never get the engine.

## License layout (see `LICENSING.md`)

| Layer | License | Why |
|---|---|---|
| App, core, AR/SLAM/teleological engine, engine-touching built-ins | **PolyForm Noncommercial** | Product + moat; competitors can't ship it, you keep commercial rights |
| `:extension-api` / SDK / plugin loader | **MIT** | The contract extensions build against, and other apps can adopt |
| Asset importers (ABR / `.brushset` / `.cube`) | **MIT** | Interop layer; pure upside to keep open |
| Commodity reference tools (brush, eraser, blur, curves, color-balance — the ones that *don't* touch the engine) | **MIT** | Seed the ecosystem; double as extension templates |

## Sequencing

- **Now / cheap:** asset-format importers. Helps even a tiny user base.
- **Phase 2+ (follows adoption):** the code-extension API, sandbox, and registry. A marketplace with no
  audience attracts no creators (Clip Studio worked because it had millions of users first). Build the
  importers now; let the marketplace follow the users from the B-then-A plan.

## Open build questions (not yet decided)

- Sandbox runtime specifics (JS engine vs WASM runtime on Android; capability surface).
- Registry: self-host from scratch vs adopt/fork Open VSX's implementation.
- Whether to run our own payment rails (in-app currency) or stay external-payment-only at launch.
