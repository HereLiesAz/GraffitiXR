# GraffitiXR → azphalt: host requests

Feedback from GraffitiXR, the azphalt standard's first conforming host, gathered while implementing
the manifest model, the `.azp` installer, and the registry-API client. Ordered by how much each one
blocks or improves a real host integration. Not filed against the azphalt repo — this is our wish
list to relay.

## Blocking / correctness

1. **Pin the signature canonicalization.** `spec/package-format.md` says `signature.json` is an Ed25519
   signature over "the canonical bytes of `manifest.json`," but the canonical form is undefined
   (RFC 8785 JCS? sorted keys, specific whitespace/number formatting?). A host cannot implement
   verification interoperably without it. **Ask:** specify the exact canonicalization and publish a
   signed-package test vector (manifest bytes + key + expected signature). *Until this lands GraffitiXR
   verifies payloads by SHA-256 digest only and skips signature verification — a conformance gap forced
   by the spec, not a choice.*

2. **Make the digest string format normative.** The `files` map is "path → SHA-256 digest," but the
   digest string format isn't pinned. GraffitiXR uses `"sha256-" + lowercase hex`. **Ask:** state the
   exact form (SRI-style `sha256-<base64>`? `sha256-<hex>`? multihash?) so hosts don't diverge and
   silently reject each other's packages.

3. **Define the `compat` grammar.** Asset hosts MUST validate `compat` against their spec version, but
   the expression grammar isn't specified. GraffitiXR currently parses only a leading lower bound
   (`>=x.y` / `^x.y` / `~x.y` / bare `x.y`) and is lenient about the rest. **Ask:** pin a semver-range
   subset so host validation is interoperable.

## Product value

4. **Standardize a LUT `strength` parameter + its UI panel.** GraffitiXR applies `.cube` LUTs at full
   strength; the single most common user need is a 0–100% blend. **Ask:** define an optional, normative
   `strength` param for `lut` assets and a canonical `ui/…json` panel (a `slider`) for it, so every host
   renders the same control and the value round-trips the same way. More generally: publish a
   **per-asset-type `params` schema** (LUT, shader, transition, brush) — the asset-host guide names
   `params.format` for shaders/transitions but there's no schema to validate against.

5. **Add preview + popularity fields to the registry package summary.** `GET /packages` returns
   `id/name/author/version/types/priceStatus`. A marketplace card wants a thumbnail and social proof.
   **Ask:** add optional `iconUrl`, `previewUrl` (for LUTs, a before/after sample), `downloads`,
   `rating`, and `updatedAt` to the package object. GraffitiXR's catalog card already has `downloads`
   and `rating` slots sitting empty for registry results.

6. **Surface required capabilities in search results.** An asset-only host (no code sandbox — GraffitiXR
   today) needs to know *before downloading* whether a package is usable. `types[]` helps, but a
   package could need capabilities the host lacks. **Ask:** include the manifest's required
   `capabilities` (and a simple `assetOnly: true/false`) in the search summary so a host can filter out
   unusable packages up front instead of downloading then rejecting.

## Adoption / ecosystem

7. **Ship a public reference registry + conformance fixtures.** `spec/repository-api.md` is clear, but
   there's no public endpoint or sample responses to build against. **Ask:** publish a reference
   registry instance (or static sample JSON for each endpoint) and a fixture set of signed `.azp`
   packages, so hosts can integrate and pass `@azphalt/conformance` before a production registry exists.

8. **Clarify mixed-package asset independence.** For `kind: "mixed"`, the asset-host guide says ignore
   `entry`/`runtime` and use `manifest.assets`. **Ask:** state whether a mixed package's assets may
   depend on its code (e.g., a LUT generated at runtime). If they can, an asset-only host can't safely
   use them; a manifest flag marking each asset `standalone: true` would let asset hosts pick only the
   assets they can honor.
