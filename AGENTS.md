# AI Developer Guidelines & Mandatory Context

## 1. Context Requirement
All AI agents working on this project must read and understand the project documentation before generating code. Operating without this context is prohibited.

**Mandatory Reading List (`/docs/`):**
* `docs/architecture.md` - System rendering pipeline and component diagrams.
* `docs/auth.md` - Authentication specifications.
* `docs/AZNAVRAIL_COMPLETE_GUIDE.md` - Navigation rail component implementation details.
* `docs/BLUEPRINT.md` - High-level project roadmap.
* `docs/conduct.md` - Contributor code of conduct.
* `docs/contributing.md` - Contribution guidelines.
* `docs/data_layer.md` - Persistence, serialization, and state management strategies.
* `docs/DSL.md` - UI Domain Specific Language configurations.
* `docs/fauxpas.md` - Common development errors to avoid.
* `docs/file_descriptions.md` - Directory structure and file purpose registry.
* `docs/misc.md` - Miscellaneous implementation notes.
* `docs/performance.md` - Optimization targets and constraints.
* `docs/PRIVACY_POLICY.md` - Application privacy policy.
* `docs/screens.md` - Screen hierarchy and layout definitions.
* `docs/SLAM_SETUP.md` - SLAM engine mathematics and configuration.
* `docs/task_flow.md` - Detailed user task flows.
* `docs/testing.md` - Testing protocols (Unit, UI, Integration).
* `docs/TODO.md` - Current backlog and known issues.
* `docs/UI_UX.md` - Design system and UX specifications.
* `docs/workflow.md` - CI/CD and version control workflows.

## 2. Coding Standards
1.  **Complete Files Only:** Do not provide snippets or partial diffs. When modifying a file, output the full, valid file content.
2.  **No Assumptions:** If documentation is ambiguous, request clarification before proceeding.
3.  **Module Isolation:** Adhere strictly to the defined module boundaries. Do not introduce cross-module dependencies that violate the architecture (see `REFACTORING_STRATEGY.md`).
4.  **Atomic Functions:** Adhere to the Single Responsibility Principle. Refactor large functions into smaller, testable units.