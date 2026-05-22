# Backlog

## Security alerts

### Done

- **CodeQL #6 — Multiplication result converted to larger type** (`core/nativebridge/src/main/cpp/SurfaceUnroller.cpp:100`). `mCount * mCount` was computed as `int` and then widened to `size_t` for the `std::vector` ctor; overflows for `mCount ≥ 46341`. Fixed by casting both operands to `std::size_t` before multiplying.
- **Dependabot #27 — Netty HTTP header injection in HttpProxyHandler** (`io.netty:netty-handler-proxy`). Added `io.netty:netty-handler-proxy:4.1.133.Final` to `commonForcedDependencies` in `build.gradle.kts` so the proxy module is pinned alongside the existing netty-codec / netty-codec-http2 / netty-handler forces. **Follow-up:** confirm `4.1.133.Final` is past the "incomplete fix" referenced in CVE-2025-67735; if a `4.1.134+` is required, bump all four netty force lines together.

### Todo

- **CodeQL #3/#4/#5 — Inclusion of functionality from untrusted source** (`docs/index.html:8–10`). Three `<script src="https://cdnjs.cloudflare.com/...">` tags (rellax 1.12.0, gsap 3.12.2, ScrollTrigger 3.12.2) load without integrity checks.
  - **Action A (preferred):** add `integrity="sha384-…"` + `crossorigin="anonymous"` to each tag. Hashes are published on the cdnjs page for each library version.
  - **Action B:** vendor the three files under `docs/vendor/` and reference local paths. Eliminates the alert and removes a runtime CDN dependency.
  - **Note:** line 7 (`https://cdn.tailwindcss.com`) is the tailwind play-CDN, which intentionally has no stable SRI hash. If it shows up as an alert, prefer Action B (vendor a built Tailwind CSS) since SRI is impractical here.

- **Dependabot #25 — Bouncy Castle covert timing channel (High)** (`org.bouncycastle:bcprov-jdk18on`).
- **Dependabot #24 — Bouncy Castle LDAP injection (Moderate)** (`org.bouncycastle:bcprov-jdk18on`).
- **Dependabot #23 — Bouncy Castle bcpkix risky crypto algorithm (Moderate)** (`org.bouncycastle:bcpkix-jdk18on`).
  - Bouncy Castle is **transitive** — no direct pin exists in `gradle/libs.versions.toml` or any `build.gradle.kts`. Pulled in by a dependency (likely Firebase / Play Services / something doing PKI).
  - **Action:** run `./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency org.bouncycastle:bcprov-jdk18on` to identify the source, then either (a) bump that source dep if a newer version pulls patched BC, or (b) add `bcprov-jdk18on` and `bcpkix-jdk18on` to `commonForcedDependencies` at versions that resolve all three CVEs together (check GHSA advisories for the minimum safe version of each — the highest of the three is the floor).
  - **Caveat:** Bouncy Castle has had breaking API changes between minor versions. After forcing, run the full test suite and verify any signing / cert / OCSP paths still work.
