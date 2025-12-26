## 2024-05-24 - Number Selector Accessibility
**Learning:** Standard IconButtons do not visually disable by default in custom composables when just guarding the `onClick`. Passing `enabled` state and adjusting icon alpha/tint is crucial for both visual feedback and proper screen reader announcements.
**Action:** Always calculate `canIncrease`/`canDecrease` booleans and pass them to `enabled` parameter of `IconButton`, and conditionally tint the Icon.
