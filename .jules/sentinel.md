## 2024-05-22 - Path Traversal in Project Manager
**Vulnerability:** User-supplied project names were used directly in `File` paths without validation, allowing directory traversal (e.g., `../evil`).
**Learning:** File operations using user input must always validate or sanitize the input to prevent escaping the intended directory.
**Prevention:** Implement strict input validation (allowlist or blocklist) and verify canonical paths stay within the root directory.

## 2024-12-20 - Promiscuous Broadcast Receiver
**Vulnerability:** `ApkInstallReceiver` blindly trusted any `DOWNLOAD_COMPLETE` broadcast, allowing potential triggering of installation prompts for arbitrary files.
**Learning:** BroadcastReceivers for system events like `DOWNLOAD_COMPLETE` receive events for *all* apps/downloads unless filtered.
**Prevention:** Verify the `downloadId` or other identifiers against state managed by the app (e.g., SharedPreferences) before acting on the broadcast.

## 2025-05-23 - DoS in Object Deserialization
**Vulnerability:** Deserialization of OpenCV `Mat` objects lacked bounds checking on dimensions (`rows`, `cols`), allowing malicious payloads to trigger Integer Overflow and Out-Of-Memory (OOM) crashes.
**Learning:** Native wrappers like OpenCV often rely on caller validation. Arithmetic operations on dimensions (`rows * cols`) can silently overflow `Int`, bypassing size checks.
**Prevention:** Always validate dimensions against reasonable limits and check for integer overflow (using `Long` math) before allocating large buffers or native objects.
