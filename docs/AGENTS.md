# AI Developer Guidelines & Mandatory Context

## 1. Context Requirement
All AI agents working on this project must read and understand the project documentation before generating code. Operating without this context is prohibited.

**Mandatory Reading List (`/docs/`):**
* `docs/ARCHITECTURE.md` - System rendering pipeline and component diagrams.
* `docs/auth.md` - Authentication specifications.
* `docs/BLUEPRINT.md` - High-level project roadmap.
* `docs/en/conduct.md` - Contributor code of conduct.
* `docs/contributing.md` - Contribution guidelines.
* `docs/data_layer.md` - Persistence, serialization, and state management strategies.
* `docs/DSL.md` - UI Domain Specific Language configurations.
* `docs/file_descriptions.md` - Directory structure and file purpose registry.
* `docs/misc.md` - Miscellaneous implementation notes.
* `docs/performance.md` - Optimization targets and constraints.
* `docs/en/PRIVACY_POLICY.md` - Application privacy policy.
* `docs/en/screens.md` - Screen hierarchy and layout definitions.
* `docs/SLAM_SETUP.md` - SLAM engine mathematics and configuration.
* `docs/task_flow.md` - Detailed user task flows.
* `docs/testing.md` - Testing protocols (Unit, UI, Integration).
* `BACKLOG.md` - Current backlog and known issues (repo root).
* `docs/UI_UX.md` - Design system and UX specifications.
* `docs/workflow.md` - CI/CD and version control workflows.

## 2. Coding Standards
1.  **Complete Files Only:** Do not provide snippets or partial diffs. When modifying a file, output the full, valid file content.
2.  **No Assumptions:** If documentation is ambiguous, request clarification before proceeding.
3.  **Module Isolation:** Adhere strictly to the defined module boundaries. Do not introduce cross-module dependencies that violate the architecture (see `docs/ARCHITECTURE.md`).
4.  **Atomic Functions:** Adhere to the Single Responsibility Principle. Refactor large functions into smaller, testable units.

---
*Documentation updated on 2026-03-17 during website redesign and Stencil generation integration phase.*

*Documentation updated on 2026-09-22: corrected the Mandatory Reading List, which linked to several
files that don't exist — `docs/architecture.md` (case mismatch; real file is `docs/ARCHITECTURE.md`),
`docs/AZNAVRAIL_COMPLETE_GUIDE.md` (doesn't exist anywhere in the repo; link removed), `docs/conduct.md`
(only per-locale copies exist, e.g. `docs/en/conduct.md`), `docs/PRIVACY_POLICY.md` (only per-locale
copies exist), and `docs/screens.md` (only `docs/en`, `docs/de`, `docs/hu` copies exist). Also removed
the `REFACTORING_STRATEGY.md` reference in the Coding Standards section (no such file exists) in favor
of `docs/ARCHITECTURE.md`.*
