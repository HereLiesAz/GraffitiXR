## 2024-05-22 - Path Traversal in Project Manager
**Vulnerability:** User-supplied project names were used directly in `File` paths without validation, allowing directory traversal (e.g., `../evil`).
**Learning:** File operations using user input must always validate or sanitize the input to prevent escaping the intended directory.
**Prevention:** Implement strict input validation (allowlist or blocklist) and verify canonical paths stay within the root directory.

## 2024-12-20 - Promiscuous Broadcast Receiver
**Vulnerability:** `ApkInstallReceiver` blindly trusted any `DOWNLOAD_COMPLETE` broadcast, allowing potential triggering of installation prompts for arbitrary files.
**Learning:** BroadcastReceivers for system events like `DOWNLOAD_COMPLETE` receive events for *all* apps/downloads unless filtered.
**Prevention:** Verify the `downloadId` or other identifiers against state managed by the app (e.g., SharedPreferences) before acting on the broadcast.
