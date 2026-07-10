# The Extension Standard — Repo Scaffolding & Reasoning

*A blueprint for the new, separate repository: the open, portable extension standard for creative apps, plus
the consignment marketplace built on top of it. Written to seed that repo — it is deliberately **not** inside
GraffitiXR (see "Why its own repo"). Move it to the new repo when you create it.*

> **Naming.** Pick a neutral, non-GraffitiXR name — the whole point is that it doesn't read as captive.
> Below, **the Standard** = the open spec + tooling, **the Market** = the storefront, and `.‹ext›` is the
> package extension, all placeholders. Neutral candidates to react to: *Bristle, Substrate, Pigment, Loom,
> Grain.* The format and the storefront can carry different names (e.g. the format neutral, the store yours).

---

## What lives here — two things, kept separable

1. **The Standard** *(open, MIT)* — the format spec, the SDK extensions are written against, the asset
   importers, the reference runtime, and the registry. This is the thing other apps adopt. It must be, and
   look, vendor-neutral.
2. **The Market** *(a business, on top)* — your consignment storefront. A commercial venue sitting on the
   open Standard, the way a store sits on Open VSX. Its code can be MIT or private — your call — but it is
   architecturally downstream of the Standard, never fused to it.

Keeping these separable is the core constraint: anyone can implement the Standard and even run their own
store; yours is just the flagship.

## Why its own repo (not a subfolder of GraffitiXR)

- **Credible neutrality.** A standard trapped in the app's repo reads as GraffitiXR's private thing, and no
  competitor adopts it. Separation is the first structural signal that it's shared infrastructure.
- **A physical moat boundary.** A separate MIT repo literally cannot see the PolyForm engine source. "The
  extension surface never exposes the relocalization engine" stops being a discipline you maintain and
  becomes a line you *can't cross by accident*.
- **Clean dependency direction.** GraffitiXR depends on the Standard as a published library and calls across
  the boundary. The Standard never depends on GraffitiXR.

## The stack, and why

**TypeScript everywhere** in this repo. It's the language every ecosystem you're recruiting from already
writes plugins in — VS Code, Figma, and Canva extensions are all TS — so authors get types and autocomplete
for free, and "the language you already know" is part of the let-them-run-rampant pitch.

**React is one surface, not the whole thing.** Split it by where a browser actually is:

| Component | Stack | React? |
|---|---|---|
| The Market storefront | Next.js + TS | **Yes** — textbook fit |
| The registry (host/version/search/serve packages) | Node/TS service + API | No — its browse UI can reuse the storefront's React |
| The SDK (what authors build against) | TS | No — it's a library, not a UI |
| Extension **UI** | **Declarative schema**, host-rendered | **No** — see below |
| Extension **execution** | **WASM substrate** (QuickJS-in-WASM for JS), on-device | No — decided, see below |

**Two decisions fall out, and neither is React — these are the real architecture:**

- **Execution substrate.** Extensions don't run in a browser; they run inside GraffitiXR, a native
  Kotlin/Compose Android app. **Decided: a WASM substrate** — not React Native (which drags a whole runtime
  and renderer into a Compose app to do what a lean embedded runtime does lighter), and not a bare JS engine.
  See "Execution engine — decided" below for the reasoning; in short, WASM is the substrate and JS runs on it
  via QuickJS-in-WASM, so the single TS SDK survives and behavior is deterministic across hosts.
- **Extension UI.** A declarative UI schema the host renders as native controls — *not* React-in-a-webview
  (the Figma model). This is what keeps the Standard genuinely cross-app: if a plugin's UI is a control
  schema, a Kotlin app renders it in Compose and a SwiftUI app renders it natively; if it's React/HTML,
  every adopting app is forced to ship a webview, which quietly breaks "any app can implement." Portability
  argues against React here.

The native host that embeds the engine and renders the schema is **each app's own conforming
implementation** (GraffitiXR writes the Kotlin one). The repo ships the language-neutral spec plus the
TS/JS reference so any host can be checked against it.

## Repo layout (monorepo — pnpm/turbo workspaces)

~~~
/spec/                      normative, language-neutral
  package-format.md         the .‹ext› package (assets + code, manifest, signing)
  extension-manifest.md     entry points, permissions, declared capabilities
  capability-model.md       what an extension may touch: layers, bitmaps, canvas — and nothing else
  ui-schema.md              the declarative UI controls hosts render natively
/packages/
  sdk/                      TS SDK authors build against (typed editor extension points)
  importers/               .abr, .brushset, .kpp/.gbr, .cube -> normalized into .‹ext›
  runtime-reference/       reference host that loads + runs an extension (proves the contract)
  registry/                Node/TS service: host, version, search, serve packages
/apps/
  storefront/              the Market — Next.js consignment store (the business layer)
/examples/                 sample extensions; doubles as the commodity reference tools as templates
/docs/
  adopting-the-standard.md how another app implements a conforming host
  GOVERNANCE.md            neutrality + how decisions get made
LICENSE                     MIT
README.md
~~~

The **importers normalize into `.‹ext›`**, which means they're the seeding mechanism: every `.abr` a user
imports becomes a portable package, so the catalog fills as a byproduct of people bringing brushes they
already own. Define the format first (even minimally) and point the importers at it.

## The Market — consignment model

**Two lanes, and the fee lives in only one.** The standard/storefront split, made concrete about money:

- **The registry** = free, open distribution. No payment rails, no fee. A creator who just wants reach
  publishes here for nothing and monetizes externally if they choose — the VS Code / Open VSX answer, where
  the platform touches no money. This lane is what keeps the standard adoptable.
- **The Market storefront** = the consignment lane. The *only* place the fee exists, because it's the only
  place you've taken on selling. A creator who'd rather you handle the money lists here — the Clip Studio
  answer.

Keeping the fee out of the registry is what lets the standard stay neutral while the storefront is a business.

Storefront-lane details:

- **Non-exclusive recruitment.** Creators are free agents; the incumbent platforms rent their work, they
  don't own it, so the Market can court the whole existing base at once. Caveat: some platforms offer
  *opt-in* exclusive tiers for a higher cut (Adobe Stock, historically Envato), so a slice lock in
  voluntarily for the rate — your terms have to beat what exclusivity pays them. Thin fee + portability
  across every adopting app is a strong hand for it.
- **Payment is outsourced** to a payment provider / merchant-of-record — the right call, and what dissolves
  most of the "running the rails" burden: tax/VAT, chargebacks, and compliance become the provider's problem.
  One sharpening: a *marketplace* is a different payment shape than single-vendor selling. You take money
  from a buyer, split it, and pay out a third-party creator — that's split-payment / marketplace-payout
  territory (Stripe Connect and the like, or a marketplace-capable MoR), **not** a plain single-vendor MoR
  built for solo SaaS. Provider choice hinges on split-payout + seller-onboarding support — verify against
  current docs before picking one.
- **What doesn't outsource:** curation / moderation of what's listed (malware, IP), and being the marketplace
  facilitator of record for creator tax forms. The provider does the mechanics; you own the policy.
- **"Very small" has a floor — and outsourcing raises it.** The fee must clear the processor (~3% + a fixed
  cost per transaction) *plus the provider's cut on top* *plus* overhead, or you lose money per sale, and it
  only nets at volume. Outsourcing trades margin for not running the rails — the right trade for a solo dev,
  but the fee has to absorb the stacked cuts. Build the importers and the free catalog first; the paid Market
  follows users.

## Licensing & boundary

- Everything in the Standard: **MIT** (`LICENSE` at repo root). The Market storefront can be MIT or private.
- The engine is **not here and cannot be referenced.** The SDK exposes only editor extension points —
  layers, bitmaps, canvas. GraffitiXR's PolyForm engine stays in GraffitiXR, behind the boundary.
- This is the same MIT/PolyForm line from GraffitiXR's `LICENSING.md`, now enforced as a repo boundary
  instead of a module boundary.

## Governance / neutrality

Cross-app adoption needs credible neutrality: an open spec, an open decision process, a neutral name. The
honest tension for a solo project — you seed it and benefit from it, but control caps adoption. Minimum
viable: public spec + open issue/RFC process now; a neutral-foundation path later if it gains real traction.

**Self-hostability is the adoption driver — and it is not in tension with consignment.** Adopting a standard
is a *control* decision, not a convenience one: no serious app, least of all a competitor, will build its
product on infrastructure a rival meters, controls, and can revoke. Requiring another app to route its users'
extensions and its ecosystem's money through your consignment store — where you take a cut and hold the rails
— asks to be its landlord and tax collector; that is an anti-adoption force, not a business model. This is
exactly the Open VSX story: Microsoft's marketplace is legally locked to Microsoft's own products, so every
VS Code fork (VSCodium, Cursor, Theia, Gitpod) *couldn't* use it and the community built a vendor-neutral,
self-hostable registry instead. Self-hostability is what made adoption possible.

The two-lane design already gives both:

- **The standard + registry stay open and self-hostable** — the layer other apps adopt, free and neutral,
  nobody forced through your store. This is what wins adoption.
- **The consignment marketplace is a hosted service on top** — one venue, not the toll booth. Never a
  precondition for adopting the standard.

This is the npm / Docker Hub / Maven Central pattern: the format is open and self-hostable, yet a dominant
hosted registry still captures most usage because it's the best default. Openness drives adoption; the hosted
default captures convenience.

Which answers the obvious worry — *"if it's self-hostable, can't anyone run a competing store and cost me the
revenue?"* In practice, no: open registries consolidate around a canonical hosted default, because running and
moderating a registry is real work and being the best-curated, best-discovery venue is a genuine moat of
convenience and network effect. **You win the marketplace by being the best host, not by locking the
standard** — and the openness is what created the audience the marketplace monetizes. The sequence runs one
way only: open standard → adoption → audience → your store monetizes that audience. Consignment-first
strangles the adoption that would make consignment worth anything.

So: keep the standard open and self-hostable, require nobody to touch the store to adopt it, and offer hosted
consignment as the turnkey default. Self-host for competitors, enterprises, and the privacy-conscious who need
independence; hosted for indies and small apps who'd rather not run infrastructure or handle payments. **Offer
both, require neither.**

## Sequencing

1. **Spec skeleton** — package format, extension manifest, capability model, UI schema. The seed.
2. **SDK + first importers** — `.abr` and `.cube` (highest ROI, cleanest fidelity), targeting `.‹ext›`.
3. **Reference runtime** — prove an extension actually runs against the contract.
4. **GraffitiXR native host** — embed the engine, render the UI schema in Compose. The first real consumer.
5. **Registry + Market** — Phase 2+, once there's an audience.

## Execution engine — decided

**A WASM substrate — one substrate, not three engines.** QuickJS and Hermes are JS *engines*; WASM is the
compilation *target* they'd sit inside. So rather than cloning engines, compile *to* WASM:

- **Default (JS/TS) on-ramp:** authors write TypeScript → JS → run on **QuickJS-compiled-to-WASM** (the Javy
  pattern, in production at Shopify). Keeps the TS audience and the single SDK; QuickJS is ~1/40th of V8's
  footprint.
- **Performance on-ramp:** hot filters ship as raw WASM (Rust / AssemblyScript), and JS can drop to WASM for
  tight pixel loops. One host API, one runtime, two on-ramps.
- **Behavior, not performance, is what's identical.** WASM's deterministic execution gives identical
  *results* across platforms for free — target behavioral conformance (a test suite), not the
  impossible-and-pointless goal of equal performance.

**Remaining sub-decision:** on Android, run WASM via **Chicory** (pure-JVM WASM runtime — no native binaries,
no JNI; simpler, slower) vs a **native runtime** (Wasmtime / WAMR via JNI — faster, but per-ABI binary cost).
Off the shelf, **QuickJs4j** already does QuickJS → WASM → Chicory → pure JVM bytecode. Likely path:
mainstream JS extensions on Chicory, performance-critical raw-WASM filters on a native runtime — or start
all-Chicory and optimize. Design the image-buffer ABI (shared linear memory) deliberately; it's what bites a
pixel app.

## Open questions

1. **UI schema expressiveness:** rich enough for real tool UIs, constrained enough to render natively on
   every host.
2. **Importer distribution:** shared TS/WASM modules any host runs, vs per-host native reimplementation.
3. **Registry:** adopt/fork Open VSX's implementation vs build from scratch.
4. **Governance model + name.**
