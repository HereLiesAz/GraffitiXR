## 2024-05-22 - Path Traversal in Project Manager
**Vulnerability:** User-supplied project names were used directly in `File` paths without validation, allowing directory traversal (e.g., `../evil`).
**Learning:** File operations using user input must always validate or sanitize the input to prevent escaping the intended directory.
**Prevention:** Implement strict input validation (allowlist or blocklist) and verify canonical paths stay within the root directory.
