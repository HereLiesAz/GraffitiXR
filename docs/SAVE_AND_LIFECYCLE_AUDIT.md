# Save and application lifecycle audit — 2026-09-06

Baseline: `1b2072ce8b013e5b42a49427e14e0b49abbbf8ee`; follow-up to PR #1885.

## Confirmed findings and repairs

| Severity | Finding | Repair |
|---|---|---|
| High | Settings is an in-content overlay below the active navigation rail. New/Save can open over it and reveal it again on completion. | Render Settings in an Android Compose Dialog, suppress it while project dialogs are visible, clear it on project creation/navigation. |
| High | Save dismissed before persistence and exported a Downloads archive implicitly. | Await completion, retain the dialog on failure, prevent repeat submission, keep Export and Share Wall separate. |
| High | New-project navigation ran before the project existed; a failed create dismissed the dialog. | Navigate on successful creation only, preserve retry and surface storage errors. |
| High | `saveMapBlocking` launched an unawaited wall-map write. | Explicit Save awaits `saveProjectWallMap`, guarded by project id. |
| High | Repository transforms published unsaved state before a write, and create/load/delete bypassed the write mutex. | Serialize mutations and project changes under one mutex; publish after successful persistence. |
| High | Delayed design loads, replacements, effects and background imports could write into the next project's canvas. | Cancel project-scoped jobs on switch/clear and check project/image identity before applying results. |
| High | Editor saves read their project and design after dispatching to IO, allowing mismatched snapshots and reordered edits. | Capture at invocation and serialize editor saves; reject mismatched project ids. |
| High | Importing an archive with an existing id overwrote that project's files without confirmation. | Import a separate copy under a fresh id; delete incomplete imports on failure. |
| High | Imported/received manifests retained sender-device absolute asset paths. | Relocate asset references to receiver files and persist the rewritten manifest. |
| Medium | Undo of image replacement reused the replacement bitmap with the original URI. | Decode the restored image and resend it to co-op guests. |
| Medium | A project without a wall retained the previous project's wall bitmap; Clear could be undone by a pending load. | Reset project-specific UI fields on switch and cancel pending background work on Clear. |
| Medium | Library/open/create/delete failures could crash a coroutine or leave loading UI stuck without feedback. | Catch failures, reset loading, show an actionable error, synchronize the displayed name with currentProject. |
| Medium | Atomic-write fallbacks deleted the last good file before replacement succeeded; fixed temp names also collided. | Use unique sibling temp files and preserve the old file if rename fails. |
| Medium | Debounced rail preferences could be applied to a different project. | Cancel pending preference work on switch and guard the target id. |

## Review scope

Reviewed application navigation and modal composition, save/new/open/delete/import/share flows,
editor image/effect/undo/background lifetimes, repository persistence, archive installation,
permission request/resume handling, manifest exports, AR wall-map persistence, and AzNavRail 11.45
callback/touch dispatch. The Save and Export rail callbacks use distinct ids; no direct Save-to-
Settings callback was found. The Settings layering defect provides a source-confirmed path to
Settings reappearing; reproducing the user's exact touch sequence still requires a device.

This is a source and automated-test audit, not certification of every native tracking algorithm or
camera/device combination. Camera HAL recovery, physical-wall relocalization, wearable pairing,
and the precise Settings touch sequence require Android hardware. No native algorithm changes
were made in this pass.

## Verification

Regression tests cover save completion without export, create failure/retry, stale image decode,
background clear/switch, replacement undo, persistence failure, delete/save ordering, archive id
collision and sender-path relocation. CI status is recorded in the PR. Local Gradle execution is
blocked by access to services.gradle.org; the previous save-fix commit's unit-test job passed in
GitHub Actions. `git diff --check` is required before each update.
