# Palette's Journal

## 2024-05-21 - Accessible Custom Controls
**Learning:** Custom gesture-based controls (like `Knob`) are invisible to screen readers unless they explicitly implement `semantics` with `progressBarRangeInfo` and `setProgress`.
**Action:** Always wrap custom gesture components in a `semantics` modifier that exposes their state and allows programmatic adjustment.

## 2024-05-24 - Contextual Icons in AR
**Learning:** In AR/Camera overlays, static `contentDescription`s like "Direction" for dynamic indicators (arrows) are useless. They must describe the *action* required (e.g., "Move Left"), not just the visual symbol.
**Action:** Use helper functions to map state (like `CaptureStep`) to descriptive action strings for accessibility services.

## 2025-12-22 - Formatted State Descriptions
**Learning:** `stateDescription` in Compose `semantics` should be formatted for human consumption (e.g., "50%" not "0.50"). Generic components like `Knob` should expose a formatter lambda.
**Action:** Add `valueFormatter: (T) -> String` parameters to custom control components to decouple internal values from accessible labels.
