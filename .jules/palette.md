# Palette's Journal

## 2025-05-18 - Accessibility on Clickable Rows
**Learning:** `clickable` rows in Jetpack Compose (like in Settings lists) often lack semantic roles (e.g., `Role.Button`) and action labels (e.g., "Open Settings"), making them ambiguous for screen reader users ("Double tap to activate").
**Action:** Always verify `clickable` modifiers on generic containers (Rows/Columns) and explicitly add `role = Role.Button` and `onClickLabel` to clarify the interaction. Use `stateDescription` to merge status text (like "Granted"/"Denied") into the row's semantics.
