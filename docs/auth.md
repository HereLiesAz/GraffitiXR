# Authentication & Security

## 1. No User Accounts
GraffitiXR is a **local-only** utility.
* There is no Login screen.
* There is no Sign Up.
* There are no User IDs.

## 2. No Remote Servers
The application does not communicate with any backend for authentication.

## 3. Local Encryption
* **Current State:** Project files (`.gxr`) are standard ZIP archives. They are not encrypted.
* **Future Plan:** If user demand exists, we may implement AES-256 encryption for the `.gxr` files to protect proprietary artwork sketches on shared devices.