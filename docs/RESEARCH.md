# GraffitiXR — Research Dossier

*Prepared July 2026. This is the evidence base — the raw findings behind the extension-marketplace,
licensing, and later competitive decisions. It complements the two other docs rather than repeating them:*

- *`STRATEGY.md` — market position, gaps, financial paths, the B-then-A plan.*
- *`FEATURE_REFERENCE.md` — what every feature and option in the app does.*
- *`EXTENSIONS.md` — the extension/marketplace plan of record (decisions distilled from Part 1 below).*
- *`LICENSING.md` — the license layout (decisions distilled from Part 2 below).*

Everything here is directional and drawn from public sources; treat download/revenue figures as
order-of-magnitude. Sources are listed at the end.

---

## Part 1 — Extension & marketplace ecosystems

### 1.1 There are two economies, not one

"Extension marketplace" in the paint/photo world splits cleanly, and the two halves have had opposite
fortunes:

| | **Assets** (brushes, materials, LUTs, stamps, 3D) | **Code plugins** (filters, effects, tools) |
|---|---|---|
| Nature | Data files | Executable code |
| Portability | Runs anywhere (it's data) | Native/host-bound |
| Market health | Thriving, mobile-inclusive, monetized | Fragmented, desktop-bound, siloed |
| Exemplar | Clip Studio Assets | Photoshop .8bf / Adobe Exchange |

The practical upshot: the asset half is where the creators, catalogs, and money already are, and it's the
half GraffitiXR can plug into cheaply. The code half is where the genuinely novel opportunity sits, but it
has to be built, not adopted.

### 1.2 The asset economy — the model to copy

- **Clip Studio Assets (CELSYS)** is almost exactly the target: Clip Studio Paint runs on Windows, macOS,
  iPad, iPhone, and Android, and its marketplace lets creators publish and sell brushes, pens, stamps,
  patterns, and 3D models, paid in an in-app currency (CLIPPY / GOLD) with payouts to a wallet, plus a
  tiered contributor/MVP community. A large secondary market resells the same assets on Etsy, ArtStation,
  and Gumroad. This is the closest existing proof that a mobile-inclusive paint app can sustain a monetized
  creator marketplace.
- **Procreate** deliberately has no code-plugin system, yet its `.brush` / `.brushset` format anchors a
  huge brush economy on Creative Market, Gumroad, and Etsy.
- **Formats are siloed per app.** Procreate ≠ Clip Studio ≠ Photoshop. A real marketplace review captures
  the cost: a buyer bought Clip Studio brushes "by mistake" wanting Procreate ones. Creators re-make and
  re-sell the same brush across formats; buyers get burned. **This fragmentation is the unmet need a
  portable format addresses.**
- **Adoptable asset formats** (all data, so cross-platform-safe):
  - **ABR** — Adobe/Photoshop brushes; the most widely imported brush format (Krita, Clip Studio, etc.
    read it). Largest brush library in existence.
  - **`.brushset`** — Procreate; the mobile-artist brush economy.
  - **`.cube`** — LUTs; the standard color-grade/filter interchange, enormous free and paid libraries.

### 1.3 The code-plugin economy — mostly a cautionary tale

- **Photoshop `.8bf` is a dead end for mobile, and it's worth being explicit about why:**
  - Native compiled binaries (Windows x86/x64 DLLs, some Mac). An x86 `.8bf` crashes on ARM; it will not
    run on Android.
  - The API is **proprietary**; Adobe restricted the SDK license after Photoshop 6.0 (2002) specifically
    to stop third-party hosts from using newer APIs.
  - Building a compliant host means reimplementing dozens of Photoshop "suite" callbacks — "an enormous,
    ongoing compatibility maintenance burden. No open-source project has succeeded" (GIMP's own writeup).
    The shims that exist (PSPI, 8bf2Script) are partial or defunct.
  - **Security disaster:** PSPI runs unsigned native code — documented heap overflows, and "a maliciously
    crafted `.8bf` can execute arbitrary shell commands," plus older plugins exfiltrating EXIF/GPS. The
    antithesis of an offline/no-tracking product.
  - Despite this, `.8bf` is supported across dozens of desktop hosts (GIMP, Paint.NET, Affinity, Corel,
    IrfanView…), so the *library* is huge — just unreachable from Android.
- **G'MIC** is the one genuinely adoptable idea from this world: a portable, open-source **effect engine**
  (500+ filters) that plugs into GIMP, Krita, Paint.NET, and Photoshop through thin host adapters — one
  filter library, many hosts. It's C++ (CeCILL license, GPL-family), so a mobile port is conceivable. Model
  to note: *bring an engine, not a binary.*
- **Adobe's modern path** (UXP, replacing CEP) + the **Adobe Exchange** marketplace exist but are Adobe-
  ecosystem-bound and not something to adopt.
- **GIMP / Krita / Paint.NET** all have open plugin systems (Python / C / .NET DLL) — desktop-only and
  app-specific. Good prior art for how a host exposes extension points, not for a shared format.

### 1.4 Registry & monetization blueprints (from outside paint apps)

- **Figma Community** — JS plugins, freemium as the growth default. The monetization reality is the lesson:
  Figma's built-in seller program is capacity-limited / not approving new sellers, so in practice creators
  sell via Gumroad / LemonSqueezy / a Merchant of Record, generate license keys, and validate them inside
  the plugin. **Takeaway: don't build a walled payment garden — keep discovery free and let creators bring
  their own payment + license keys.**
- **Open VSX (Eclipse Foundation)** — the blueprint for the code side. It exists because community
  extensions under open licenses shouldn't be locked to one vendor's restrictive marketplace terms, so
  Eclipse built a **vendor-neutral, open-source, self-hostable** registry that hosts the *same extension
  format as VS Code* — so extensions work across editors (VSCodium, Theia, Cursor) without changes.
  Self-hosting also fits an offline stance. This is the literal template for "MIT the API and let other
  apps run the same extensions."

### 1.5 The white space, and what to adopt

**White space:** no mobile paint/photo app has an open, portable, monetizable *code*-extension marketplace.
Clip Studio proves the mobile *asset* marketplace; Procreate refuses code; the code worlds are all desktop
and siloed. The portable mobile extension marketplace is genuinely unbuilt.

**Adoption plan (detailed in `EXTENSIONS.md`):**
1. **Import existing asset formats now** — ABR, `.brushset`, `.cube`. A day-one catalog from libraries the
   whole internet already made, at the cost of an importer rather than an ecosystem.
2. **For the code API, copy Open VSX, not `.8bf`** — a portable JS/WASM sandbox (cross-platform + safe),
   and a vendor-neutral, self-hostable registry so the format can travel. Optionally integrate G'MIC as a
   ready-made portable effect engine.
3. **Monetization: the Figma de-facto model** — free discovery, creators bring external payment + license
   keys (MIT permits closed, sold extensions), or a Clip-Studio-style in-app currency later.

**Reality check:** Clip Studio Assets works because CSP had millions of users first; a marketplace with no
audience attracts no creators. The code-extension marketplace is a Phase-2+ effort that follows adoption.
Asset-format import is the cheap early win that helps even a tiny user base.

---

## Part 2 — Licensing research

### 2.1 No license = all rights reserved

A public repo with no `LICENSE` file is **not** open source and not "free for non-commercial use." Under
default copyright, the author retains all rights; GitHub's terms grant only viewing and forking, not use.
So an unlicensed repo is *more* restrictive than a permissive or non-commercial license, not less — and
"open source" wording on the site is inaccurate against it.

### 2.2 PolyForm Noncommercial 1.0.0 — the engine/app license

The standardized, plain-language **non-commercial license for source code** (Creative Commons is designed
for creative works, not software; PolyForm fills that gap). Grants everything for any *noncommercial
purpose* — personal study, hobby, research, education, charities, government — and forbids commercial use.

- **The wrinkle that aims at your own users:** "noncommercial" means no commercial advantage or
  compensation. A muralist using the app on a *paid commission* is commercial use. A blanket NC license
  would bar the working muralists GraffitiXR is built for. **Resolution:** license the *source* NC (blocks
  competitors lifting code) but keep the *compiled app* free for anyone, commissions included, under
  separate app-usage terms. Code license and app-usage grant are two documents.
- **It never restricts the author.** PolyForm binds others; you (the copyright holder) retain full rights —
  a commercial Pro tier and future dual-licensing are unaffected.

### 2.3 AGPL-3.0 — considered, then narrowed

Strong network-copyleft. Notable facts:
- **It is the standard vehicle for open-core dual-licensing** — MongoDB, RethinkDB, SugarCRM, OpenERP,
  WURFL. The copyright holder keeps the right to sell a commercial license; nobody who receives the code
  under AGPL inherits that right. So AGPL keeps *your* commercial door fully open while forcing any
  competitor-forker to open-source.
- **Its network clause (§13) is largely inert for an offline mobile app** — it triggers for server software
  users interact with over a network. GraffitiXR's co-op is device-to-device, not server-client, so for
  this product AGPL and GPL behave nearly identically.
- **Verdict:** AGPL is incompatible with an extensible ecosystem — anything built against an AGPL API
  inherits AGPL, which forbids proprietary/sold extensions and infects any host that runs them. Killed both
  ecosystem goals, so it was dropped in favor of PolyForm (core) + MIT (extensions). AGPL text is retained
  on ice (`docs/licenses/AGPL-3.0.txt`) in case copyleft is ever wanted for a specific isolated part.

### 2.4 MIT vs EPL for the extension layer

Every real plugin ecosystem uses permissive/weak-copyleft for its **API**, because strong copyleft there
forbids proprietary and cross-app extensions. VS Code's source and extension API are MIT, and that
permissiveness is *why* the ecosystem exploded and why other editors run the same extensions.

- **MIT (chosen):** anyone builds extensions, keeps them closed, sells them; any app can adopt the API and
  run them. Maximum ecosystem, zero friction, de-facto-standard potential. The VS Code model.
- **EPL-2.0:** also allows proprietary plugins and cross-app use, but adds file-level copyleft on the SDK
  itself (improve the framework, contribute that back). Choose only if guarding the framework from private
  forks matters more than frictionless adoption. For a young ecosystem, MIT wins.

### 2.5 Multi-license mechanics

- **Licensing is per-file, not per-repo.** As sole author you grant the rights, so different parts can carry
  different licenses. Precedence, most-specific first: per-file SPDX header > module `LICENSE` >
  `LICENSING.md` > root `LICENSE`.
- **The moat leaks across module lines.** The teleological engine is not contained in `:core:nativebridge`;
  significant algorithm code is Kotlin in `feature/ar/anchor/` (`MetricFingerprintBuilder`, `PoseFusion`)
  and `feature/ar/eval/` (`DriftCostProbe`), plus the `Fingerprint`/`WallFeatureMap` models and serializers
  in `core:common`, plus the method docs. The protective (NC) boundary must cover that whole set, not just
  the C++.
- **The one rule that keeps the open layer from leaking the moat:** the MIT extension API exposes only
  *editor* extension points — operations on layers, bitmaps, the canvas. It must never surface the
  relocalization engine. Plugins extend the editor, not the tracking core. That makes the API its own
  module, so the license boundary is a real module boundary.
- **Contributions / CLA.** Outside contributions under AGPL/PolyForm are owned by their authors; to keep
  dual-licensing/acquisition open you need a CLA (or don't accept outside contributions to parts you might
  commercialize).
- **Third-party dependencies keep their own licenses** and can't be relicensed: OpenCV (Apache-2.0), ML Kit
  segmentation — and note a **GPL-2.0** component (`ittnotify`) bundled inside OpenCV, which is a separate
  compliance item worth checking regardless of the first-party license plan.
- **PolyForm is not OSI-"open source."** Neither is the app as a whole once the NC engine is inside it.
  Describe it accurately as *source-available; core PolyForm Noncommercial, extension layer MIT* — not
  "open source."

---

## Part 3 — Supplementary competitor intelligence

*Gathered after `STRATEGY.md` was written; extends, doesn't replace, the competitive picture there.*

### 3.1 Da Vinci Eye — corrected and deepened

- **Cross-platform, not iOS-only.** The flagship AR Art Projector runs iOS + Android + visionOS; the Android
  build was rebuilt in 2026 (~190K downloads) and gained a social section.
- **Now subscription.** The rebuilt app is freemium + subscription (~$29.99/yr or $7.99/mo); the old
  $6.99-one-time was the abandoned legacy app.
- **Mural Maker does not actually solve the mural anchoring problem.** Per DVE's own docs and App Store
  listing: its anchor is a **printed image target** you tape to the wall — must be flat, high-detail, and
  fill ≥ ⅛ of the camera view, and it holds only "as long as the anchor stays in the view of your camera."
  It's **iOS-only** and the listing states it **requires two iOS devices**. A reviewer reports the AR
  tracking anchor "so buggy I could not get it to work at all." So on Android there is *no* DVE mural tool
  at all, and even on iOS the "solution" is an external prop that can't be painted over or leave frame —
  the opposite of markerless relocalization against the actual artwork.

### 3.2 VR / XR — two categories, only one a competitor

- **VR graffiti simulators are not competitors.** Kingspray Graffiti (Quest/PC, ~$15, 85% positive but
  "no longer supported… broken features") is a game — you paint *virtual* walls in virtual train yards.
  Quest 3 passthrough lets you tag your *real* living room with *virtual* paint, still nothing real gets
  painted. Closest use is practice/muscle-memory. Arguably a top-of-funnel, not a threat.
- **MR passthrough mural tools are real adjacent competitors.** Contour ($9.99, ~3.0★), StencilVR ($9.99),
  SketchAR's Quest app, and TraceARtist let you overlay a reference on a real wall via Quest 3 passthrough
  and paint hands-free. Working muralists are adopting them.
  - **Their genuine edge:** hands-free — both hands on the can, reference continuously in view. A phone
    can't match that; pocketed, you don't see a live overlay. This is a real gap, not nothing.
  - **Their friction (from the sources):** battery is the muralists' top complaint ("especially when you
    run out of power"); $300+ headset plus a Mac/PC to upload artwork; a computer on your face amid fumes,
    heat, and daylight; conspicuous and absurd for guerrilla/after-dark work; mediocre ratings.
  - **The moat survives the form factor:** these anchor to the headset's *room* spatial map (better
    hardware than a phone, still environment-based) — no purpose-built defense against the surface being
    painted over. Nobody ported artwork-anchored teleological relocalization to a headset.
  - **Strategic read:** this validates hands-free demand and exposes GraffitiXR's current gap. The right
    answer is not building for Quest (wrong hardware for the use case) but getting the **lightweight-glasses
    path (Meta Ray-Bans, Xreal)** working — the currently-broken WIP — which delivers hands-free on
    field-practical hardware while carrying the moat the Quest tools lack.

### 3.3 The AR Drawing clone tier + Cupixel — the download volume

- **The clones are what most people actually use.** "AR Drawing: Sketch & Paint" (Digital Solutions) has
  10M+ installs (32M lifetime, ~40K/day); "AR Easy Draw" claims 50M. Mostly prop-the-phone-and-hold-still
  overlay tracing, subscription + heavy ads, aggressive/undismissable subscription pop-ups, AI photo→sketch
  templates. iOS + Android (+ Vision Pro for some).
- **Cupixel** (iOS + Android) uses "Smart Trace" — recognizes the drawing surface's edges (surface-locked,
  stand-based; they ship a phone stand), ~$13/mo subscription plus physical art kits, JOANN retail
  partnership, artist-led classes and community. A surface/stand tracer, not a mural or pocket tool.

---

## Sources

**Extensions / marketplace**
- Clip Studio Assets & Paint (cross-platform, marketplace, currency): assets.clip-studio.com,
  clipstudio.net, CLIP STUDIO ASK forum; secondary market on Etsy / ArtStation.
- Photoshop `.8bf` (format, proprietary SDK restriction, host list, incompatibility, security): Wikipedia
  "Photoshop plugin"; gimp.cc FAQ; PSFilterPdn (MIT) and PhotoDemon 8bf discussions; thepluginsite.com.
- G'MIC (portable effect engine, host list): gimp.cc; project references.
- Figma Community monetization (seller-program limits, external payment + license keys, MoR): Figma Forum;
  Figma Help Center; Dodo Payments guides.
- Open VSX (vendor-neutral, open-source, self-hostable, same VS Code format): Eclipse Foundation newsroom;
  XDA writeup.

**Licensing**
- PolyForm Noncommercial 1.0.0 (full text, definitions): polyformproject.org; SPDX.
- AGPL-3.0 (text, network clause, dual-licensing precedent): gnu.org / OSI / choosealicense; Wikipedia;
  FSF bulletin.
- MIT / EPL comparison and VS Code precedent: general licensing references; Open VSX/Eclipse.

**Competitors (supplementary)**
- Da Vinci Eye / Mural Maker (platforms, pricing, image-target mechanics, two-device requirement, bug
  reports): davincieyeapp.com help pages; App Store & Google Play listings; AppBrain.
- Kingspray Graffiti (simulator, price, support status, passthrough): Meta Store; Steam; SideQuest;
  UploadVR.
- MR mural tools (Contour, StencilVR, SketchAR-Quest, TraceARtist): Meta Store; BrandXR; maeckervr.com;
  a working muralist's Contour writeup.
- AR Drawing clones & Cupixel (download scale, model): AppBrain; App Store & Google Play; ARPost; Cupixel
  FAQ.

*Revenue and download figures are third-party estimates — order-of-magnitude, not audited.*
