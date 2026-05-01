# Co-op Real-time Spectator + Hardening

**Status:** Spec 2 of 3 (sequential). Predecessor: AzNavRail 8.9 migration. Successor: wearables glasses-as-camera.

**Date:** 2026-04-30
**Owner:** Az
**Module:** `collab/` (rewritten); touches `app/`, `feature/ar`, `core/common`.

---

## 1. Goal

Replace the existing one-shot fingerprint+file co-op handoff with a real-time read-only spectator session: one host paints, one guest watches live. Add the hardening pieces the existing module is missing — pairing via QR + session token, exception handling, streaming for large payloads, timeouts, reconnect window, and tests.

## 2. Non-goals

- Co-paint (guest editing). Guest is read-only by user choice.
- More than two peers in a session.
- Cloud relay so peers can be on different networks. LAN-only by user choice.
- Voice / text chat.
- Persistent session resumption across app restarts.
- Live AR-anchor re-sync. The fingerprint handoff at session start is one-shot; if the guest's anchor drifts they exit and rejoin.
- Discovery via NSD or Wi-Fi P2P. QR replaces it.
- Migration of existing project file format. The bulk transfer reuses today's `.gxr` payload.

## 3. Background

### 3.1 What exists today

`collab/` contains `CollaborationManager.kt` (TCP socket on a random port + a `ServerSocket.accept()` loop), `DiscoveryManager.kt` (NSD + Wi-Fi P2P broadcast and discovery), and a JNI bridge `CollaborationBridge.cpp` exposing `nativeExportFingerprint()` and `nativeAlignToPeer()` for visual relocalization.

The session model is one-shot: host transmits a SLAM fingerprint and a `.gxr` project file to a guest, the guest aligns to the fingerprint and imports the project, and the connection closes. Status is surfaced through `ArUiState.coopStatus: String?` and `coopRole: NONE | HOST | GUEST`. The `coop_main` rail item triggers `arViewModel.startCollaborationDiscovery()`.

Known gaps surveyed during brainstorming:
- `try { serverSocket?.accept() } catch (Exception) { null }` (`CollaborationManager.kt:37-40`) and silently-caught handshake failures (`:96-127`).
- 10MB / 100MB hardcoded payload caps; no streaming.
- No timeout on the server `accept()` loop; `stopServer()` must be called manually.
- No authentication: any device on the LAN that finds the NSD broadcast can connect.
- No tests.

### 3.2 What changes

The session model becomes phased: handshake → bulk → live deltas → graceful or reconnect-able close. Discovery is removed in favor of a QR pairing flow; the host generates a per-session token, the guest scans, and the host validates the token before accepting. Live deltas (a small set of layer-mutation Ops) stream from host to guest as the user edits. Guest is read-only — its rail editing items are hidden during a session. A 30-second reconnect window with an in-memory delta buffer covers transient Wi-Fi drops.

The hardening items are not a separate workstream; they fall out of the rewrite.

## 4. Architecture

### 4.1 Topology

Strict 1 host + 1 guest. Same Wi-Fi LAN. The host's address and a 128-bit session token are encoded in a QR code shown on the host. The guest scans, opens a single TCP connection, presents the token, gets accepted, receives bulk state, then streams live deltas.

### 4.2 Three states

| State | Triggered by | UI |
|---|---|---|
| `Idle` | Default; `leaveSession()`; remote close beyond reconnect window | `coop.main` rail item normal |
| `Hosting` | Host taps `coop.main` | QR overlay visible; `coop.main` shows session state |
| `Spectating` | Guest scans QR + handshake succeeds | Editing rail items hidden; "Spectating <host>" banner; `coop.main` becomes "Leave session" |

`Hosting` has internal sub-states the UI also shows: `WaitingForGuest`, `Connected`, `Reconnecting`. `Spectating` mirrors `Connected` and `Reconnecting`.

### 4.3 Wire protocol

Length-prefixed framing on raw TCP. Each frame:

```
[4 bytes big-endian length][1 byte type][payload]
```

No HTTP, no WebSocket, no gRPC. The current code is already a raw socket and the protocol is a 2-peer LAN protocol; standardization gives nothing back here.

**Phase 1 — handshake:**
```
GUEST → HOST:  HELLO         { token, clientVersion, deviceName }
HOST  → GUEST: HELLO_OK      { sessionId, protocolVersion }
             | HELLO_REJECTED { reason: BadToken | VersionMismatch | AlreadyHosting }
```

**Phase 2 — bulk state transfer:**
```
HOST  → GUEST: BULK_BEGIN        { projectId, layerCount,
                                   fingerprintBytes: Int, projectBytes: Int }
HOST  → GUEST: BULK_FINGERPRINT  { bytes }    (chunked, 64KB per frame)
HOST  → GUEST: BULK_PROJECT      { bytes }    (chunked, 64KB per frame)
HOST  → GUEST: BULK_END
GUEST → HOST:  BULK_ACK          { lastSeq: 0 }
```

**Phase 3 — live deltas:**
```
HOST  → GUEST: DELTA      { seq: Long, op: Op }
GUEST → HOST:  DELTA_ACK  { lastSeq: Long }     (batched, ≤1Hz)
HOST  ↔ GUEST: PING/PONG  { ts }                (every 5s; 2 missed pongs = dead)
```

**Phase 4 — termination:**
```
Either: BYE { reason: UserLeft | NetworkLost | HostClosed }
```

### 4.4 Op set (final for this spec)

```kotlin
sealed class Op {
    data class LayerAdd(val layer: Layer) : Op()
    data class LayerRemove(val layerId: String) : Op()
    data class LayerReorder(val newOrder: List<String>) : Op()
    data class LayerTransform(val layerId: String, val matrix: Matrix4) : Op()
    data class LayerPropsChange(val layerId: String, val props: LayerProps) : Op()
    data class StrokeComplete(val layerId: String, val stroke: BrushStroke) : Op()
    data class TextContentChange(val layerId: String, val text: String) : Op()
}
```

Editor mutations not mapping to one of these are not synced. New mutation types in the future require adding an Op variant.

### 4.5 Reconnect (30s window)

- **Host:** on unexpected socket close, enters `Reconnecting`. During the window, host buffers DELTA frames in a `DeltaBuffer` capped at 5MB or 1000 ops. New connection presenting the same `token` + `sessionId` + `lastAppliedSeq` is accepted; host replays buffered ops from `lastAppliedSeq + 1`. Cap exceeded → reconnect window closes early; further reconnection forces full re-bulk (which is currently not supported, so practically: session ends).
- **Guest:** on socket close, app shows "Reconnecting…" overlay; reconnects every 2s for 30s. On success, resumes silently. On 30s timeout, shows "Session ended" and returns to `Idle`.

### 4.6 QR payload

```
gxr://coop?h=<host-ip>&p=<port>&t=<token>&v=<protocolVersion>
```

`token`: 128 bits, base64url, generated per session. `protocolVersion`: integer; mismatch → `HELLO_REJECTED { VersionMismatch }`.

## 5. Components

| File | Change |
|---|---|
| `collab/AndroidManifest.xml` | Remove `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`. Keep `INTERNET`. |
| `collab/.../DiscoveryManager.kt` | **Delete.** |
| `collab/.../CollaborationManager.kt` | **Rewrite** as a state-machine session orchestrator. `StateFlow<CoopState>`. Methods: `startHosting(project)`, `stopHosting()`, `joinFromQr(payload)`, `leaveSession()`. |
| `collab/.../Session.kt` (new) | Owns the live socket and phase state. Subclasses `HostSession` and `GuestSession`. |
| `collab/.../Frame.kt` (new) | `[4-byte length][1-byte type][payload]` codec; chunked-streaming for >1MB payloads. |
| `collab/.../OpCodec.kt` (new) | Serializes/deserializes `Op` and handshake/bulk frames. Decision (kotlinx-serialization Cbor vs ProtoBuf) deferred to plan. |
| `collab/.../DeltaBuffer.kt` (new) | Ring buffer of pending DELTAs during reconnect window. 5MB / 1000 ops cap. |
| `collab/.../QrPayload.kt` (new) | Encode/decode the QR string with validation. |
| `collab/build.gradle.kts` | Drop duplicate coroutine dep; add chosen serialization lib; add ZXing or equivalent for QR generation/scanning. |
| `collab/src/main/cpp/CollaborationBridge.cpp` | Audit. Keep fingerprint export + peer alignment. Delete anything tied to old discovery. |
| `core/common/.../OpEmitter.kt` (new interface) | `interface OpEmitter { fun emit(op: Op) }` — single method, no-op when not hosting. |
| `core/common/.../model/Op.kt` (new) | The `Op` sealed class above. |
| `feature/ar/.../ArViewModel.kt` (around line 99 — `startCollaborationDiscovery`) | Replace with `startHosting()` + `joinFromQr(payload)` + `leaveSession()`. The 5s discovery timeout is gone. |
| `feature/ar/.../ArUiState.kt` (around line 148 — `CoopRole`) | Keep `CoopRole`. Add typed `CoopSessionState = Idle \| WaitingForGuest \| Connected \| Reconnecting \| Ended(reason)`. `coopStatus: String?` retired in favor of typed state + UI formatting helper. |
| `app/.../MainActivity.kt:1050-1058` (`coop.main` rail item) | Callback expands. Idle → `arViewModel.startHosting()`. Hosting → toggles QR overlay. Spectating → `arViewModel.leaveSession()`. Label/color reflect typed state. |
| `app/.../CoopHostQrOverlay.kt` (new) | Compose overlay rendering the QR for the host. "Stop sharing" button. Auto-dismisses on guest connect. |
| `app/.../CoopJoinQrScannerOverlay.kt` (new) | Compose overlay reusing the AR camera infra to scan a QR; calls `joinFromQr`. |
| `app/.../CoopSpectatorBanner.kt` (new) | Persistent banner during spectating: host name + Leave button. |
| `app/.../MainActivity.kt` (rail-item visibility) | When `coopRole == GUEST`, hide editing rail items. Keep `mode.host` (read-only mode switch) and `coop.main` (leave). Hide-list encoded once. |
| `app/.../EditorViewModel.kt` (or wherever layer mutations happen) | Each layer-mutation method emits the corresponding `Op` via the injected `OpEmitter`. No-op when not hosting. |
| `collab/src/test/...` | Tests per §8. |

Module dependency direction: `app` → `feature/*` → `collab` → `core/common`. The editor and AR features depend only on the `OpEmitter` abstraction and the `CoopState` flow. They never see sockets, frames, or QR payloads.

## 6. Data flow

### 6.1 Host edit emission

```
User edits a layer
        ↓
EditorViewModel.applyLayerOp(op)
        ↓ 1. mutate local state (existing)
        ↓ 2. opEmitter.emit(op)
        ↓
OpEmitter (impl in collab/, Hilt-injected)
        ↓ if no active session: drop
        ↓ if active session: enqueue to session.outQueue
        ↓
HostSession.coroutineScope:
  for op in outQueue:
    seq = nextSeq()
    deltaBuffer.append(seq, op)        (in case of reconnect within 30s)
    frame = Frame.encode(DELTA, OpCodec.encode(seq, op))
    socket.outputStream.write(frame); flush()
```

### 6.2 Guest edit application

```
GuestSession.coroutineScope:
  loop:
    type, payload = Frame.decode(socket.inputStream)
    when type:
      DELTA:
        seq, op = OpCodec.decode(payload)
        if seq <= lastAppliedSeq: skip       (idempotent on reconnect replay)
        editorViewModel.applySpectatorOp(op)
        lastAppliedSeq = seq
        if (now - lastAckSent > 1s): sendAck(lastAppliedSeq)
      PING:    send PONG
      BYE:    tear down + return to Idle
```

`applySpectatorOp(op)` uses the same code path as local edits but skips Op emission.

### 6.3 Bulk phase

```
On HELLO_OK:
  fingerprint = slamManager.exportFingerprint()
  projectBytes = projectStore.serialize(currentProject)
  send BULK_BEGIN { ... }
  stream BULK_FINGERPRINT in 64KB chunks
  stream BULK_PROJECT in 64KB chunks
  send BULK_END
  enter Phase 3

On guest:
  receive BULK_BEGIN → preallocate buffers
  stream-receive BULK_FINGERPRINT → slamManager.alignToPeer(bytes)
  stream-receive BULK_PROJECT → projectStore.deserialize(bytes)
                              → editorViewModel.loadAsSpectator(project)
  send BULK_ACK
  enter Phase 3
```

### 6.4 Reconnect

```
Guest: socket close → state Reconnecting → loop every 2s for 30s:
  open new socket → send HELLO { token, sessionId, lastAppliedSeq }
  if HOST replies HELLO_OK:
    receive replayed DELTAs (host replays from DeltaBuffer)
    discard ops <= lastAppliedSeq
    Phase 3 resumes
  else: retry until 30s expires

Host: socket close → state Reconnecting → 30s window:
  continue enqueueing emitted ops to deltaBuffer
  on new accept with matching token+sessionId+lastSeq:
    replay buffered ops in order; resume Phase 3
  on 30s expiry without reconnect:
    transition to Ended(NetworkLost); drop deltaBuffer
```

## 7. Error handling

### 7.1 Eliminated

| Failure | How it dies |
|---|---|
| Silent `accept()` exception (`CollaborationManager.kt:37-40`) | Removed. New session orchestrator never holds an open `accept()` once a guest is connected. All catches log via `Log.w`/`Log.e` and transition to a typed state. |
| Silent handshake failure (`:96-127`) | Replaced by typed `HELLO_REJECTED` reasons + a UI surface. |
| Hardcoded 10MB / 100MB payload caps | Replaced by chunked streaming. Bulk payloads have no hard cap (memory cap is the receiver's, enforced by the framing reader). |
| Server `accept()` running indefinitely | Host's `accept()` runs only while in `WaitingForGuest`; closes immediately on a successful HELLO. |
| No authentication | Per-session token validated on every accepted connection (including reconnect). |

### 7.2 Tolerated, made graceful

| Failure | Behavior |
|---|---|
| Token mismatch on reconnect | `HELLO_REJECTED { BadToken }`. Reconnect attempt is consumed; guest may retry until window expires; expiry → Ended(NetworkLost). |
| Bulk transfer fails midway | Both sides abort session; UI shows "Session ended". Host may re-host; guest must rescan QR. |
| `OpCodec.decode` throws on a single Op | Log + send `BYE { reason: ProtocolError }`; session ends. (Frame validity is enforced at framing layer; this is for genuinely malformed payloads.) |
| Heartbeat misses 2 PONGs (10s) | Treat as socket close. Enter `Reconnecting`. |
| Wi-Fi switches mid-session | Same as socket close. Enter `Reconnecting`. |
| `applySpectatorOp` references a layer the guest doesn't have (drift) | Log warning. Drop Op. Session continues but may diverge. (Should not happen; defense in depth.) |

### 7.3 Out of scope

- Adversarial input. Trust on LAN. Frame size is bounded (max 16MB per frame); malformed frames terminate the session.
- Replay attacks. Single token per session; not designed for hostile networks.
- Encryption. LAN-only; not adding TLS.

## 8. Testing

### 8.1 Unit tests

| Test | Asserts |
|---|---|
| `FrameCodecTest` | Round-trip every frame type; length prefix correctness; bounded read; truncated input throws cleanly. |
| `QrPayloadTest` | Encode/decode round-trip; reject invalid scheme, missing fields, oversized token, malformed port. |
| `DeltaBufferTest` | Ordering preserved; cap enforced; overflow behavior (drops oldest, returns false from `append`). |
| `OpCodecTest` | Round-trip every `Op` variant; version-tag bytes; forward-compat for unknown variants (skip + log). |
| `SessionStateMachineTest` | `Idle → Hosting/WaitingForGuest → Connected → Reconnecting → Connected | Ended` transitions; illegal transitions throw. |
| `ReconnectReplayTest` | Replay emits ops in order; idempotency when guest's `lastSeq` is ahead of buffer's start. |

### 8.2 Integration tests

| Test | Asserts |
|---|---|
| `LocalLoopTest` | In-process host + guest using piped streams (no real socket). Full bulk + delta + reconnect cycle. Host emits 50 ops; guest receives all 50 in order with content equality. |
| `InterruptedBulkTest` | Kill connection mid-`BULK_PROJECT`. Guest cleans up and returns to Idle. Host enters Reconnecting then Ended. No leaked file handles. |

### 8.3 Manual

1. Two devices on same Wi-Fi: pair via QR. Host paints; guest watches in real time.
2. Toggle airplane mode on guest mid-session: verify reconnect succeeds within 30s.
3. Toggle airplane mode on guest for >30s: verify session ends with appropriate UI.
4. Host adds 50+ layers and does a long stroke. Verify guest stays in sync.
5. Verify guest's editing rail items are hidden; verify mode-switch and Leave still work.
6. Verify QR overlay disappears when guest connects; verify it returns if guest leaves.

### 8.4 Deliberately not tested

- Throughput / latency benchmarks. Acceptance is "feels real-time on LAN" — qualitative.
- Adversarial malformed input. Out of scope per §7.3.
- Multi-network conditions (cellular, mesh). LAN-only.

## 9. Definition of done

- All unit tests in §8.1 pass.
- Both integration tests in §8.2 pass.
- All six manual scenarios in §8.3 pass.
- `CollaborationManager`'s public surface is clean (no `try {} catch (Exception) { null }`).
- `collab/` no longer references NSD or Wi-Fi P2P APIs.
- The `coop.main` rail item ID and overlay components compile clean against the AzNavRail 8.9 surface from Spec 1.
- Hosting + spectating works through a five-minute exploratory session without unexpected state.

## 10. Rollback

The `collab/` rewrite lands in a single PR. Rollback is `git revert` of that PR. The wire protocol is new (no on-disk format change), so no migration. The `.gxr` project format is unchanged; existing saved projects are unaffected. The `coop.main` rail item ID rename in Spec 1 is the only cross-spec coupling — Spec 2's PR depends on Spec 1 having merged but does not change rail IDs further.

## 11. Open questions

These resolve during plan execution:

1. Serialization library choice: kotlinx-serialization Cbor vs ProtoBuf vs FlatBuffers. The `Op` sealed class is small and stable; any of the three works. Decision factor: existing project file format. If `.gxr` is already serialized via one of these, reuse it. If not, kotlinx-serialization Cbor is the default for minimum dep weight.
2. QR library: ZXing core + a thin scanner adapter, or a dedicated lib like `journeyapps/zxing-android-embedded`. Decision in plan.
3. Whether to reuse the existing AR camera surface for QR scanning or open a separate scanner activity. AR camera reuse saves a permission round-trip.
4. The exact Wi-Fi-state surface to display when not on the same LAN as a desired peer. (The QR payload says `host-ip:port`; if the guest is on a different network, the connect simply fails with a typed error. UX for "you're on different Wi-Fi" is plan-time work.)
