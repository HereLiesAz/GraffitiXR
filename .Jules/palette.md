# Palette's Journal

## 2024-05-21 - Accessible Custom Controls
**Learning:** Custom gesture-based controls (like `Knob`) are invisible to screen readers unless they explicitly implement `semantics` with `progressBarRangeInfo` and `setProgress`.
**Action:** Always wrap custom gesture components in a `semantics` modifier that exposes their state and allows programmatic adjustment.
