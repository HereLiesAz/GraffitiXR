# Co-op Real-time Spectator + Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the existing one-shot fingerprint+file co-op handoff in `collab/` with a real-time read-only spectator session: one host paints, one guest watches live edits over a phased TCP protocol with QR pairing, chunked bulk transfer, live coarse-grained Op deltas, and a 30-second auto-reconnect window. Fold in hardening: typed errors, chunked streaming, timeouts, tests.

**Architecture:** Rewrite `collab/` around a single state-machine session orchestrator (`CollaborationManager`) that owns one of two `Session` subclasses (`HostSession` / `GuestSession`). A length-prefixed framed wire protocol carries handshake, bulk state, and live deltas over a single TCP socket. NSD/Wi-Fi P2P discovery is removed; QR carries `host:port + token`. Editor mutations on host are emitted as `Op` records via a new `OpEmitter` interface (no-op when not hosting). Guest receives Ops and applies them via a new `applySpectatorOp` path on `EditorViewModel`. UI gains three overlays (host QR, guest scanner, spectator banner) and gates editing rail items by role.

**Tech Stack:** Kotlin coroutines, kotlinx-serialization (Cbor), JUnit 4 + MockK, ZXing for QR, Hilt DI, Jetpack Compose for the overlays.

---

## Spec reference

Implements `docs/superpowers/specs/2026-04-30-coop-realtime-spectator-design.md` (commit `eb41bff1`). Read §4 (Architecture) and §5 (Components) before starting.

---

## File structure

| File | Disposition | Purpose |
|---|---|---|
| `core/common/.../model/Op.kt` | **create** | `sealed class Op` carrying layer mutations over the wire. |
| `core/common/.../model/CoopSessionState.kt` | **create** | Typed session state replacing the freeform `coopStatus: String?`. |
| `core/common/.../coop/OpEmitter.kt` | **create** | `interface OpEmitter { fun emit(op: Op) }` — single method, no-op default. |
| `core/common/.../coop/NoOpOpEmitter.kt` | **create** | Default impl. Used when not hosting. Hilt-bindable. |
| `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt` | **modify** | Add `coopSessionState: CoopSessionState` to `ArUiState`; mark `coopStatus: String?` and `isCoopSearching/isSyncing` for removal in Task 17. |
| `collab/build.gradle.kts` | **modify** | Add kotlinx-serialization (cbor), Hilt, ZXing core; align coroutine version with the catalog. |
| `collab/src/main/AndroidManifest.xml` | **modify** | Remove WIFI/LOCATION/NEARBY_WIFI_DEVICES perms; keep INTERNET. |
| `collab/.../CollaborationManager.kt` | **rewrite** | State-machine session orchestrator. Public API: `StateFlow<CoopSessionState>`, `startHosting(project)`, `stopHosting()`, `joinFromQr(payload)`, `leaveSession()`. |
| `collab/.../DiscoveryManager.kt` | **delete** | NSD/P2P discovery is gone. |
| `collab/.../wire/Frame.kt` | **create** | `[4 bytes big-endian length][1 byte type][payload]` codec; chunked-streaming for >1MB payloads. |
| `collab/.../wire/FrameType.kt` | **create** | Enum of frame types (HELLO, HELLO_OK, HELLO_REJECTED, BULK_BEGIN, BULK_FINGERPRINT, BULK_PROJECT, BULK_END, BULK_ACK, DELTA, DELTA_ACK, PING, PONG, BYE). |
| `collab/.../wire/OpCodec.kt` | **create** | kotlinx-serialization Cbor for `Op` and handshake/bulk payloads. |
| `collab/.../wire/QrPayload.kt` | **create** | Encode/decode `gxr://coop?h=<ip>&p=<port>&t=<token>&v=<protocolVersion>` with validation. |
| `collab/.../session/Session.kt` | **create** | Abstract base owning the live socket and phase state. |
| `collab/.../session/HostSession.kt` | **create** | Server-side state machine + bulk send + delta send + reconnect window. |
| `collab/.../session/GuestSession.kt` | **create** | Client-side state machine + bulk receive + delta receive + reconnect attempts. |
| `collab/.../session/DeltaBuffer.kt` | **create** | Ring buffer of pending DELTAs during reconnect window. 5MB / 1000 ops cap. |
| `collab/.../OpEmitterImpl.kt` | **create** | Hilt-bound concrete impl of `OpEmitter` that forwards to the active `HostSession.outQueue`. |
| `feature/ar/.../ArViewModel.kt` (around line 99 — `startCollaborationDiscovery`) | **modify** | Replace with `startHosting()` + `joinFromQr(payload)` + `leaveSession()`. Remove the 5s discovery timer. |
| `app/.../MainActivity.kt` (around line 991 — `coop.main` rail item) | **modify** | Replace the existing `arViewModel.startCollaborationDiscovery()` callback with state-driven branching: Idle → host, Hosting → toggle QR, Spectating → leave. |
| `app/.../ui/coop/CoopHostQrOverlay.kt` | **create** | Compose overlay rendering the QR for the host. "Stop sharing" button. Auto-dismisses on guest connect. |
| `app/.../ui/coop/CoopJoinQrScannerOverlay.kt` | **create** | Compose overlay using ZXing's `IntentIntegrator` (or `journeyapps/zxing-android-embedded` if added). Calls `joinFromQr` on result. |
| `app/.../ui/coop/CoopSpectatorBanner.kt` | **create** | Persistent banner during spectating: host name + Leave button. |
| `app/.../MainActivity.kt` (rail-item visibility logic, around lines 1042-1075) | **modify** | When `coopRole == GUEST`, hide editing rail items (everything except `mode.host` and `coop.main`). |
| `feature/editor/.../EditorViewModel.kt` (or wherever layer mutations live) | **modify** | Each layer-mutation method calls `opEmitter.emit(op)` after mutating local state. Add `applySpectatorOp(op)` and `loadAsSpectator(project)` paths. |
| `app/build.gradle.kts` | **modify** | Add `:collab` and the QR scanner library if not present. |
| Tests | **create** | `FrameCodecTest`, `OpCodecTest`, `QrPayloadTest`, `DeltaBufferTest`, `SessionStateMachineTest`, `ReconnectReplayTest`, `LocalLoopTest`, `InterruptedBulkTest`. |

Module dependency direction: `app` → `feature/*` → `collab` → `core/common`. The editor and AR features depend only on `OpEmitter` and `CoopSessionState`. They never see sockets, frames, or QR payloads.

---

## Decisions locked at plan time

These resolve spec §11 open questions:

1. **Serialization:** kotlinx-serialization Cbor. The `kotlinx.serialization` Gradle plugin is already applied to `app/build.gradle.kts:12`. Adding `kotlinx-serialization-cbor` to the catalog is one line. Choice rationale: minimum dep weight; Cbor is binary and compact.
2. **QR scanner library:** `com.journeyapps:zxing-android-embedded:4.3.0`. Self-contained scanner activity, smallest integration cost.
3. **QR generator library:** `com.google.zxing:core:3.5.3` (writer only, no Android dep).
4. **QR scanner UX:** Launch a dedicated `ScannerActivity` via `IntentIntegrator`. The AR camera surface is **not** reused — keeps the QR flow decoupled and avoids fighting ARCore for the camera.

---

## Wire protocol (locked)

```
Frame: [4 bytes big-endian length][1 byte FrameType][payload bytes]

Phase 1 — handshake:
  GUEST → HOST:  HELLO          { token, clientVersion, deviceName, lastAppliedSeq=0 }
  HOST  → GUEST: HELLO_OK       { sessionId, protocolVersion }
              | HELLO_REJECTED  { reason: BadToken | VersionMismatch | AlreadyHosting }

Phase 2 — bulk:
  HOST  → GUEST: BULK_BEGIN        { projectId, layerCount, fingerprintBytes: Int, projectBytes: Int }
  HOST  → GUEST: BULK_FINGERPRINT  { bytes }     (chunked, 64 KB per frame)
  HOST  → GUEST: BULK_PROJECT      { bytes }     (chunked, 64 KB per frame)
  HOST  → GUEST: BULK_END
  GUEST → HOST:  BULK_ACK          { lastSeq: 0 }

Phase 3 — deltas:
  HOST  → GUEST: DELTA      { seq: Long, op: Op }
  GUEST → HOST:  DELTA_ACK  { lastSeq: Long }     (batched, ≤1Hz)
  HOST  ↔ GUEST: PING/PONG  { ts }                (every 5s; 2 missed = dead)

Phase 4 — termination:
  Either: BYE { reason: UserLeft | NetworkLost | HostClosed | ProtocolError }
```

Frame size cap: 16 MB per frame (defense against malformed input).

---

## Op set (locked)

```kotlin
@Serializable
sealed class Op {
    @Serializable data class LayerAdd(val layer: Layer) : Op()
    @Serializable data class LayerRemove(val layerId: String) : Op()
    @Serializable data class LayerReorder(val newOrder: List<String>) : Op()
    @Serializable data class LayerTransform(val layerId: String, val matrix: List<Float>) : Op()
    @Serializable data class LayerPropsChange(val layerId: String, val props: LayerProps) : Op()
    @Serializable data class StrokeComplete(val layerId: String, val stroke: BrushStroke) : Op()
    @Serializable data class TextContentChange(val layerId: String, val text: String) : Op()
}
```

(`LayerProps` and `BrushStroke` are referenced data classes that must be `@Serializable`. If they aren't currently, Task 1 makes them so.)

---

## QR payload (locked)

```
gxr://coop?h=<host-ip>&p=<port>&t=<token>&v=<protocolVersion>
```

`token`: 128 bits, base64url, generated per session. `protocolVersion`: integer; mismatch → `HELLO_REJECTED { VersionMismatch }`.

---

## Task 1: Add Op sealed class + dependent serializable models

**Files:**
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/Op.kt`
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt` (add `@Serializable` to `Layer`, `LayerProps`, `BrushStroke` if missing)
- Create: `core/common/src/test/java/com/hereliesaz/graffitixr/common/model/OpTest.kt`

- [ ] **Step 1.1: Inspect existing models**

```bash
grep -n "data class Layer\|data class LayerProps\|data class BrushStroke\|@Serializable" core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt
```

Note which classes already carry `@Serializable` and which need it added. The kotlinx.serialization plugin should already be applied to the `core:common` module — if it isn't, add to `core/common/build.gradle.kts`:

```kotlin
plugins {
    // ... existing plugins
    alias(libs.plugins.kotlinx.serialization)
}
```

- [ ] **Step 1.2: Add @Serializable to Layer, LayerProps, BrushStroke**

For each of the three classes in `EditorModels.kt`, add `@Serializable` annotation. Add the import:

```kotlin
import kotlinx.serialization.Serializable
```

If any field type is not serializable (e.g., `android.graphics.Matrix`, `Color`), replace with serializable shapes: `Matrix` → `List<Float>` (16 floats); `Color` → `Long` (ARGB packed). Update call sites only as far as needed to compile; keep behavior unchanged.

- [ ] **Step 1.3: Write the failing test**

Create `core/common/src/test/java/com/hereliesaz/graffitixr/common/model/OpTest.kt`:

```kotlin
package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class OpTest {

    private val cbor = Cbor

    @Test
    fun `LayerAdd round-trips through Cbor`() {
        val original: Op = Op.LayerAdd(Layer(id = "L1", name = "one"))
        val bytes = cbor.encodeToByteArray(original)
        val decoded = cbor.decodeFromByteArray<Op>(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `LayerRemove round-trips`() {
        val original: Op = Op.LayerRemove(layerId = "L1")
        val bytes = cbor.encodeToByteArray(original)
        assertEquals(original, cbor.decodeFromByteArray<Op>(bytes))
    }

    @Test
    fun `LayerReorder preserves order`() {
        val original: Op = Op.LayerReorder(newOrder = listOf("L1", "L2", "L3"))
        val decoded = cbor.decodeFromByteArray<Op>(cbor.encodeToByteArray(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `LayerTransform round-trips matrix as float list`() {
        val original: Op = Op.LayerTransform(layerId = "L1", matrix = List(16) { it.toFloat() })
        val decoded = cbor.decodeFromByteArray<Op>(cbor.encodeToByteArray(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `TextContentChange round-trips`() {
        val original: Op = Op.TextContentChange(layerId = "L1", text = "hello world")
        val decoded = cbor.decodeFromByteArray<Op>(cbor.encodeToByteArray(original))
        assertEquals(original, decoded)
    }
}
```

- [ ] **Step 1.4: Run test, verify it fails**

```bash
./gradlew :core:common:testDebugUnitTest --tests com.hereliesaz.graffitixr.common.model.OpTest
```

Expected: FAIL with `unresolved reference: Op` (and possibly `Layer.Companion.serializer` if @Serializable wasn't added).

- [ ] **Step 1.5: Create Op.kt**

Create `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/Op.kt`:

```kotlin
package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

/**
 * The set of layer mutations that propagate over the co-op wire from host to guest.
 * Coarse-grained: brush strokes propagate only on completion, not per-sample.
 *
 * Editor mutations not mapping to one of these are not synced. New mutation types
 * require adding an Op variant.
 */
@Serializable
sealed class Op {
    @Serializable
    data class LayerAdd(val layer: Layer) : Op()

    @Serializable
    data class LayerRemove(val layerId: String) : Op()

    @Serializable
    data class LayerReorder(val newOrder: List<String>) : Op()

    @Serializable
    data class LayerTransform(val layerId: String, val matrix: List<Float>) : Op()

    @Serializable
    data class LayerPropsChange(val layerId: String, val props: LayerProps) : Op()

    @Serializable
    data class StrokeComplete(val layerId: String, val stroke: BrushStroke) : Op()

    @Serializable
    data class TextContentChange(val layerId: String, val text: String) : Op()
}
```

- [ ] **Step 1.6: Add Cbor dep to libs.versions.toml**

In `gradle/libs.versions.toml`, add to the `[libraries]` section if missing:

```toml
kotlinx-serialization-cbor = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-cbor", version.ref = "kotlinxSerialization" }
```

(`kotlinxSerialization` version ref likely already exists for `kotlinx-serialization-json`. If not, add it.)

In `core/common/build.gradle.kts`, add to dependencies:

```kotlin
implementation(libs.kotlinx.serialization.cbor)
```

- [ ] **Step 1.7: Run test to verify pass**

```bash
./gradlew :core:common:testDebugUnitTest --tests com.hereliesaz.graffitixr.common.model.OpTest
```

Expected: 5 tests pass.

- [ ] **Step 1.8: Commit**

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/model/Op.kt \
        core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt \
        core/common/src/test/java/com/hereliesaz/graffitixr/common/model/OpTest.kt \
        core/common/build.gradle.kts \
        gradle/libs.versions.toml
git commit -m "feat: add Op sealed class for co-op wire protocol"
```

---

## Task 2: Add CoopSessionState type

**Files:**
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/CoopSessionState.kt`

- [ ] **Step 2.1: Create the type**

```kotlin
package com.hereliesaz.graffitixr.common.model

sealed class CoopSessionState {
    object Idle : CoopSessionState()
    object WaitingForGuest : CoopSessionState()
    data class Connected(val peerName: String) : CoopSessionState()
    object Reconnecting : CoopSessionState()
    data class Ended(val reason: EndReason) : CoopSessionState()

    enum class EndReason {
        UserLeft,
        NetworkLost,
        HostClosed,
        ProtocolError,
        VersionMismatch,
        BadToken,
    }
}
```

- [ ] **Step 2.2: Compile**

```bash
./gradlew :core:common:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 2.3: Commit**

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/model/CoopSessionState.kt
git commit -m "feat: add CoopSessionState typed state replacing freeform coopStatus"
```

---

## Task 3: Add OpEmitter interface + NoOpOpEmitter default

**Files:**
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/coop/OpEmitter.kt`
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/coop/NoOpOpEmitter.kt`
- Create: `core/common/src/test/java/com/hereliesaz/graffitixr/common/coop/NoOpOpEmitterTest.kt`

- [ ] **Step 3.1: Create the interface**

```kotlin
// core/common/src/main/java/com/hereliesaz/graffitixr/common/coop/OpEmitter.kt
package com.hereliesaz.graffitixr.common.coop

import com.hereliesaz.graffitixr.common.model.Op

/**
 * Single point through which editor layer mutations flow into the co-op session.
 * Editor code calls emit(op) unconditionally; impls handle the active-vs-inactive
 * branching internally. NoOpOpEmitter is the default when not hosting.
 */
fun interface OpEmitter {
    fun emit(op: Op)
}
```

- [ ] **Step 3.2: Create the default impl**

```kotlin
// core/common/src/main/java/com/hereliesaz/graffitixr/common/coop/NoOpOpEmitter.kt
package com.hereliesaz.graffitixr.common.coop

import com.hereliesaz.graffitixr.common.model.Op
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoOpOpEmitter @Inject constructor() : OpEmitter {
    override fun emit(op: Op) { /* drop */ }
}
```

- [ ] **Step 3.3: Write the test**

```kotlin
// core/common/src/test/java/com/hereliesaz/graffitixr/common/coop/NoOpOpEmitterTest.kt
package com.hereliesaz.graffitixr.common.coop

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Test

class NoOpOpEmitterTest {

    @Test
    fun `emit does not throw and returns Unit`() {
        val emitter: OpEmitter = NoOpOpEmitter()
        emitter.emit(Op.LayerAdd(Layer(id = "L1", name = "one")))
    }
}
```

- [ ] **Step 3.4: Run + commit**

```bash
./gradlew :core:common:testDebugUnitTest --tests com.hereliesaz.graffitixr.common.coop.NoOpOpEmitterTest
```

Expected: PASS.

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/coop/OpEmitter.kt \
        core/common/src/main/java/com/hereliesaz/graffitixr/common/coop/NoOpOpEmitter.kt \
        core/common/src/test/java/com/hereliesaz/graffitixr/common/coop/NoOpOpEmitterTest.kt
git commit -m "feat: add OpEmitter interface with no-op default"
```

---

## Task 4: Frame codec + tests

**Files:**
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/FrameType.kt`
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/Frame.kt`
- Create: `collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/wire/FrameTest.kt`

- [ ] **Step 4.1: Create FrameType**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/FrameType.kt
package com.hereliesaz.graffitixr.core.collaboration.wire

internal enum class FrameType(val code: Byte) {
    HELLO(0x10),
    HELLO_OK(0x11),
    HELLO_REJECTED(0x12),
    BULK_BEGIN(0x20),
    BULK_FINGERPRINT(0x21),
    BULK_PROJECT(0x22),
    BULK_END(0x23),
    BULK_ACK(0x24),
    DELTA(0x30),
    DELTA_ACK(0x31),
    PING(0x40),
    PONG(0x41),
    BYE(0x50);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun ofCode(code: Byte): FrameType? = byCode[code]
    }
}
```

- [ ] **Step 4.2: Create Frame codec**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/Frame.kt
package com.hereliesaz.graffitixr.core.collaboration.wire

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed wire framing.
 * Wire format: [4 bytes big-endian length][1 byte FrameType code][payload bytes]
 * Maximum payload size: 16 MB (defense against malformed input).
 */
internal object Frame {

    const val MAX_PAYLOAD_BYTES: Int = 16 * 1024 * 1024
    private const val HEADER_TYPE_BYTES = 1

    /** Encode and write a frame to the stream. Caller must flush. */
    @Throws(IOException::class)
    fun write(out: OutputStream, type: FrameType, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "payload size ${payload.size} exceeds max $MAX_PAYLOAD_BYTES"
        }
        val data = DataOutputStream(out)
        data.writeInt(payload.size + HEADER_TYPE_BYTES) // length includes type byte
        data.writeByte(type.code.toInt())
        data.write(payload)
    }

    /**
     * Read one frame. Returns null on clean EOF (peer closed before the next frame).
     * Throws IOException on truncated frame or bad type code.
     */
    @Throws(IOException::class)
    fun read(input: InputStream): FrameRead? {
        val data = DataInputStream(input)
        val length = try {
            data.readInt()
        } catch (eof: EOFException) {
            return null
        }
        if (length < HEADER_TYPE_BYTES) {
            throw IOException("frame length $length below minimum $HEADER_TYPE_BYTES")
        }
        if (length - HEADER_TYPE_BYTES > MAX_PAYLOAD_BYTES) {
            throw IOException("frame payload ${length - HEADER_TYPE_BYTES} exceeds max $MAX_PAYLOAD_BYTES")
        }
        val typeCode = data.readByte()
        val type = FrameType.ofCode(typeCode)
            ?: throw IOException("unknown frame type code 0x${typeCode.toString(16)}")
        val payload = ByteArray(length - HEADER_TYPE_BYTES)
        data.readFully(payload)
        return FrameRead(type, payload)
    }

    data class FrameRead(val type: FrameType, val payload: ByteArray) {
        // Equality based on contents (ByteArray default uses identity).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameRead) return false
            return type == other.type && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
    }
}
```

- [ ] **Step 4.3: Write tests**

```kotlin
// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/wire/FrameTest.kt
package com.hereliesaz.graffitixr.core.collaboration.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class FrameTest {

    @Test
    fun `round-trip empty payload`() {
        val out = ByteArrayOutputStream()
        Frame.write(out, FrameType.PING, ByteArray(0))
        val read = Frame.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Frame.FrameRead(FrameType.PING, ByteArray(0)), read)
    }

    @Test
    fun `round-trip non-empty payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val out = ByteArrayOutputStream()
        Frame.write(out, FrameType.DELTA, payload)
        val read = Frame.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Frame.FrameRead(FrameType.DELTA, payload), read)
    }

    @Test
    fun `clean EOF returns null`() {
        val read = Frame.read(ByteArrayInputStream(ByteArray(0)))
        assertNull(read)
    }

    @Test
    fun `truncated frame throws IOException`() {
        val out = ByteArrayOutputStream()
        Frame.write(out, FrameType.DELTA, byteArrayOf(1, 2, 3, 4, 5))
        val truncated = out.toByteArray().copyOfRange(0, 6) // mid-payload
        assertThrows(IOException::class.java) {
            Frame.read(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `oversized payload throws on write`() {
        val out = ByteArrayOutputStream()
        val tooBig = ByteArray(Frame.MAX_PAYLOAD_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            Frame.write(out, FrameType.DELTA, tooBig)
        }
    }

    @Test
    fun `oversized declared length throws on read`() {
        val out = ByteArrayOutputStream()
        // Manually craft a header declaring more than MAX_PAYLOAD_BYTES + 1
        val dataOut = java.io.DataOutputStream(out)
        dataOut.writeInt(Frame.MAX_PAYLOAD_BYTES + 2)
        dataOut.writeByte(FrameType.DELTA.code.toInt())
        assertThrows(IOException::class.java) {
            Frame.read(ByteArrayInputStream(out.toByteArray()))
        }
    }

    @Test
    fun `unknown frame type code throws`() {
        val out = ByteArrayOutputStream()
        val dataOut = java.io.DataOutputStream(out)
        dataOut.writeInt(1)
        dataOut.writeByte(0x7F)
        assertThrows(IOException::class.java) {
            Frame.read(ByteArrayInputStream(out.toByteArray()))
        }
    }
}
```

- [ ] **Step 4.4: Run + commit**

```bash
./gradlew :collab:testDebugUnitTest --tests com.hereliesaz.graffitixr.core.collaboration.wire.FrameTest
```

Expected: 7 tests pass.

```bash
git add collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/Frame.kt \
        collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/FrameType.kt \
        collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/wire/FrameTest.kt
git commit -m "feat: add length-prefixed frame codec for co-op wire"
```

(If the `:collab:testDebugUnitTest` task doesn't exist because the module has no test source set, add a `testImplementation(libs.junit)` line to `collab/build.gradle.kts` first.)

---

## Task 5: OpCodec for serializing Op + handshake/bulk payloads

**Files:**
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/OpCodec.kt`
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/HandshakePayloads.kt`
- Create: `collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/wire/OpCodecTest.kt`

- [ ] **Step 5.1: Add Cbor dep to collab module**

In `collab/build.gradle.kts`, add the kotlinx.serialization plugin at the top:

```kotlin
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlinx.serialization)
}
```

In dependencies:

```kotlin
implementation(libs.kotlinx.serialization.cbor)
testImplementation(libs.junit)
```

- [ ] **Step 5.2: Define handshake/bulk payload types**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/HandshakePayloads.kt
package com.hereliesaz.graffitixr.core.collaboration.wire

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import kotlinx.serialization.Serializable

@Serializable
internal data class HelloPayload(
    val token: String,
    val clientVersion: Int,
    val deviceName: String,
    val lastAppliedSeq: Long = 0L,
)

@Serializable
internal data class HelloOkPayload(
    val sessionId: String,
    val protocolVersion: Int,
)

@Serializable
internal data class HelloRejectedPayload(val reason: RejectReason) {
    enum class RejectReason { BadToken, VersionMismatch, AlreadyHosting }
}

@Serializable
internal data class BulkBeginPayload(
    val projectId: String,
    val layerCount: Int,
    val fingerprintBytes: Int,
    val projectBytes: Int,
)

@Serializable
internal data class DeltaPayload(val seq: Long, val op: Op)

@Serializable
internal data class DeltaAckPayload(val lastSeq: Long)

@Serializable
internal data class BulkAckPayload(val lastSeq: Long)

@Serializable
internal data class PingPayload(val ts: Long)

@Serializable
internal data class ByePayload(val reason: CoopSessionState.EndReason)
```

- [ ] **Step 5.3: Create OpCodec**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/OpCodec.kt
package com.hereliesaz.graffitixr.core.collaboration.wire

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object OpCodec {

    private val cbor = Cbor

    inline fun <reified T> encode(value: T): ByteArray = cbor.encodeToByteArray(value)
    inline fun <reified T> decode(bytes: ByteArray): T = cbor.decodeFromByteArray(bytes)
}
```

- [ ] **Step 5.4: Write the test**

```kotlin
// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/wire/OpCodecTest.kt
package com.hereliesaz.graffitixr.core.collaboration.wire

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Assert.assertEquals
import org.junit.Test

class OpCodecTest {

    @Test
    fun `Hello round-trips`() {
        val original = HelloPayload(token = "abc", clientVersion = 1, deviceName = "Pixel")
        assertEquals(original, OpCodec.decode<HelloPayload>(OpCodec.encode(original)))
    }

    @Test
    fun `HelloRejected round-trips with each reason`() {
        HelloRejectedPayload.RejectReason.entries.forEach { reason ->
            val original = HelloRejectedPayload(reason)
            assertEquals(original, OpCodec.decode<HelloRejectedPayload>(OpCodec.encode(original)))
        }
    }

    @Test
    fun `BulkBegin round-trips`() {
        val original = BulkBeginPayload("p1", 5, 1024, 4096)
        assertEquals(original, OpCodec.decode<BulkBeginPayload>(OpCodec.encode(original)))
    }

    @Test
    fun `Delta round-trips with LayerAdd op`() {
        val original = DeltaPayload(seq = 42L, op = Op.LayerAdd(Layer(id = "L1", name = "one")))
        assertEquals(original, OpCodec.decode<DeltaPayload>(OpCodec.encode(original)))
    }

    @Test
    fun `Bye round-trips with each reason`() {
        CoopSessionState.EndReason.entries.forEach { reason ->
            val original = ByePayload(reason)
            assertEquals(original, OpCodec.decode<ByePayload>(OpCodec.encode(original)))
        }
    }
}
```

- [ ] **Step 5.5: Run + commit**

```bash
./gradlew :collab:testDebugUnitTest --tests com.hereliesaz.graffitixr.core.collaboration.wire.OpCodecTest
```

Expected: 5 tests pass.

```bash
git add collab/build.gradle.kts \
        collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/OpCodec.kt \
        collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/HandshakePayloads.kt \
        collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/wire/OpCodecTest.kt
git commit -m "feat: add OpCodec (Cbor) for handshake, bulk, and delta payloads"
```

---

## Task 6: QrPayload encode/decode + tests

**Files:**
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/QrPayload.kt`
- Create: `collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/wire/QrPayloadTest.kt`

- [ ] **Step 6.1: Create QrPayload**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/QrPayload.kt
package com.hereliesaz.graffitixr.core.collaboration.wire

import android.net.Uri
import java.security.SecureRandom
import java.util.Base64

/**
 * QR-pairing payload format:
 *   gxr://coop?h=<host-ip>&p=<port>&t=<token>&v=<protocolVersion>
 */
internal data class QrPayload(
    val host: String,
    val port: Int,
    val token: String,
    val protocolVersion: Int,
) {
    fun encode(): String {
        require(port in 1..65535) { "port out of range: $port" }
        require(token.isNotBlank()) { "token must not be blank" }
        require(protocolVersion >= 0) { "protocolVersion must be non-negative" }
        return "gxr://coop?h=$host&p=$port&t=$token&v=$protocolVersion"
    }

    companion object {
        const val SCHEME = "gxr"
        const val HOST_KEYWORD = "coop"
        private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

        fun parse(input: String): QrPayload {
            val uri = try {
                Uri.parse(input)
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid URI: $input", e)
            }
            require(uri.scheme == SCHEME) { "wrong scheme '${uri.scheme}', expected $SCHEME" }
            require(uri.host == HOST_KEYWORD) { "wrong host '${uri.host}', expected $HOST_KEYWORD" }
            val host = uri.getQueryParameter("h") ?: error("missing 'h'")
            val port = uri.getQueryParameter("p")?.toIntOrNull()
                ?: error("missing or invalid 'p'")
            require(port in 1..65535) { "port out of range: $port" }
            val token = uri.getQueryParameter("t") ?: error("missing 't'")
            require(token.isNotBlank()) { "blank token" }
            require(token.length <= 256) { "oversized token" }
            val v = uri.getQueryParameter("v")?.toIntOrNull()
                ?: error("missing or invalid 'v'")
            return QrPayload(host = host, port = port, token = token, protocolVersion = v)
        }

        /** Generate a 128-bit base64url token. */
        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return base64UrlEncoder.encodeToString(bytes)
        }
    }
}
```

- [ ] **Step 6.2: Write tests**

```kotlin
// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/wire/QrPayloadTest.kt
package com.hereliesaz.graffitixr.core.collaboration.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

// Uses Robolectric because android.net.Uri is involved.
@RunWith(RobolectricTestRunner::class)
class QrPayloadTest {

    @Test
    fun `encode produces expected string`() {
        val payload = QrPayload(host = "192.168.1.5", port = 12345, token = "abc", protocolVersion = 1)
        assertEquals("gxr://coop?h=192.168.1.5&p=12345&t=abc&v=1", payload.encode())
    }

    @Test
    fun `decode round-trips`() {
        val original = QrPayload(host = "10.0.0.1", port = 4096, token = "xyz", protocolVersion = 2)
        assertEquals(original, QrPayload.parse(original.encode()))
    }

    @Test
    fun `parse rejects wrong scheme`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrPayload.parse("http://coop?h=1.1.1.1&p=80&t=x&v=1")
        }
    }

    @Test
    fun `parse rejects wrong host keyword`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrPayload.parse("gxr://other?h=1.1.1.1&p=80&t=x&v=1")
        }
    }

    @Test
    fun `parse rejects missing fields`() {
        assertThrows(IllegalStateException::class.java) {
            QrPayload.parse("gxr://coop?h=1.1.1.1&p=80&t=x") // no v
        }
    }

    @Test
    fun `parse rejects out-of-range port`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrPayload.parse("gxr://coop?h=1.1.1.1&p=99999&t=x&v=1")
        }
    }

    @Test
    fun `parse rejects oversized token`() {
        val bigToken = "a".repeat(257)
        assertThrows(IllegalArgumentException::class.java) {
            QrPayload.parse("gxr://coop?h=1.1.1.1&p=80&t=$bigToken&v=1")
        }
    }

    @Test
    fun `newToken produces distinct values`() {
        assertNotEquals(QrPayload.newToken(), QrPayload.newToken())
    }
}
```

- [ ] **Step 6.3: Add Robolectric dep to collab**

In `collab/build.gradle.kts`:

```kotlin
testImplementation("org.robolectric:robolectric:4.13")
```

(Confirm version against catalog; if a `robolectric` entry exists in `libs.versions.toml`, use the alias.)

- [ ] **Step 6.4: Run + commit**

```bash
./gradlew :collab:testDebugUnitTest --tests com.hereliesaz.graffitixr.core.collaboration.wire.QrPayloadTest
```

Expected: 8 tests pass.

```bash
git add collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/wire/QrPayload.kt \
        collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/wire/QrPayloadTest.kt \
        collab/build.gradle.kts
git commit -m "feat: add QR pairing payload encoder/decoder + token generator"
```

---

## Task 7: DeltaBuffer + tests

**Files:**
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBuffer.kt`
- Create: `collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBufferTest.kt`

- [ ] **Step 7.1: Create DeltaBuffer**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBuffer.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.Op

/**
 * Bounded buffer of pending DELTAs during the host's reconnect window. When the
 * window expires or the cap is exceeded, the buffer is dropped and the session
 * ends; that is the host's signal that the guest cannot resume.
 */
internal class DeltaBuffer(
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val maxOps: Int = 1000,
) {
    private data class Entry(val seq: Long, val op: Op, val sizeBytes: Int)

    private val ring = ArrayDeque<Entry>()
    private var totalBytes: Long = 0

    /** Append. Returns false when capped (caller should treat as a fatal reconnect failure). */
    fun append(seq: Long, op: Op, sizeBytes: Int): Boolean {
        if (sizeBytes > maxBytes) return false
        if (ring.size >= maxOps || totalBytes + sizeBytes > maxBytes) return false
        ring.addLast(Entry(seq, op, sizeBytes))
        totalBytes += sizeBytes
        return true
    }

    /** Discard all entries with seq <= upTo. */
    fun trimUpTo(upTo: Long) {
        while (ring.isNotEmpty() && ring.first().seq <= upTo) {
            val removed = ring.removeFirst()
            totalBytes -= removed.sizeBytes
        }
    }

    fun opsAfter(lastSeq: Long): List<Pair<Long, Op>> =
        ring.filter { it.seq > lastSeq }.map { it.seq to it.op }

    fun isEmpty(): Boolean = ring.isEmpty()
    fun size(): Int = ring.size
    fun bytes(): Long = totalBytes

    fun clear() {
        ring.clear()
        totalBytes = 0
    }
}
```

- [ ] **Step 7.2: Write tests**

```kotlin
// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBufferTest.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaBufferTest {

    private fun op(id: String) = Op.LayerAdd(Layer(id = id, name = id))

    @Test
    fun `append + opsAfter returns ops with seq greater than threshold`() {
        val buf = DeltaBuffer()
        buf.append(1, op("a"), 10)
        buf.append(2, op("b"), 10)
        buf.append(3, op("c"), 10)
        val after1 = buf.opsAfter(1)
        assertEquals(listOf(2L to op("b"), 3L to op("c")), after1)
    }

    @Test
    fun `trimUpTo removes entries with seq at or below threshold`() {
        val buf = DeltaBuffer()
        buf.append(1, op("a"), 10)
        buf.append(2, op("b"), 10)
        buf.append(3, op("c"), 10)
        buf.trimUpTo(2)
        assertEquals(listOf(3L to op("c")), buf.opsAfter(0))
    }

    @Test
    fun `cap by ops returns false when full`() {
        val buf = DeltaBuffer(maxOps = 2)
        assertTrue(buf.append(1, op("a"), 10))
        assertTrue(buf.append(2, op("b"), 10))
        assertFalse(buf.append(3, op("c"), 10))
    }

    @Test
    fun `cap by bytes returns false when full`() {
        val buf = DeltaBuffer(maxBytes = 100)
        assertTrue(buf.append(1, op("a"), 60))
        assertFalse(buf.append(2, op("b"), 60))
    }

    @Test
    fun `single op larger than cap is rejected`() {
        val buf = DeltaBuffer(maxBytes = 100)
        assertFalse(buf.append(1, op("big"), 200))
    }

    @Test
    fun `clear empties buffer`() {
        val buf = DeltaBuffer()
        buf.append(1, op("a"), 10)
        buf.clear()
        assertEquals(0, buf.size())
        assertEquals(0L, buf.bytes())
    }
}
```

- [ ] **Step 7.3: Run + commit**

```bash
./gradlew :collab:testDebugUnitTest --tests com.hereliesaz.graffitixr.core.collaboration.session.DeltaBufferTest
```

Expected: 6 tests pass.

```bash
git add collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBuffer.kt \
        collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBufferTest.kt
git commit -m "feat: add DeltaBuffer ring buffer for reconnect-window deltas"
```

---

## Task 8: Session abstract base + Phase enum

**Files:**
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/Phase.kt`
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/Session.kt`

- [ ] **Step 8.1: Create Phase**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/Phase.kt
package com.hereliesaz.graffitixr.core.collaboration.session

internal enum class Phase {
    Handshake,
    Bulk,
    Live,
    Reconnecting,
    Ended,
}
```

- [ ] **Step 8.2: Create Session base**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/Session.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal abstract class Session {

    protected val _state: MutableStateFlow<CoopSessionState> =
        MutableStateFlow(CoopSessionState.Idle)
    val state: StateFlow<CoopSessionState> get() = _state

    protected var phase: Phase = Phase.Handshake

    abstract suspend fun close(reason: CoopSessionState.EndReason)
}
```

- [ ] **Step 8.3: Compile + commit**

```bash
./gradlew :collab:compileDebugKotlin
```

Expected: PASS.

```bash
git add collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/Phase.kt \
        collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/Session.kt
git commit -m "feat: add Session abstract base + Phase enum"
```

---

## Task 9: HostSession — server, bulk send, delta send, reconnect window

This task is large. The implementation interleaves networking and Kotlin coroutines; the implementer should follow the structure below precisely.

**Files:**
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/HostSession.kt`
- Create: `collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/session/HostSessionTest.kt`

- [ ] **Step 9.1: Create HostSession**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/HostSession.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.wire.BulkAckPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.BulkBeginPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.ByePayload
import com.hereliesaz.graffitixr.core.collaboration.wire.DeltaAckPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.DeltaPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.Frame
import com.hereliesaz.graffitixr.core.collaboration.wire.FrameType
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloOkPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloRejectedPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.OpCodec
import com.hereliesaz.graffitixr.core.collaboration.wire.PingPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class HostSession(
    private val token: String,
    private val protocolVersion: Int,
    private val localDeviceName: String,
    private val fingerprintBytes: ByteArray,
    private val projectBytes: ByteArray,
    private val projectId: String,
    private val layerCount: Int,
    private val opSizeEstimator: (Op) -> Int = { 256 }, // heuristic for DeltaBuffer accounting
) : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outQueue: Channel<Op> = Channel(capacity = Channel.UNLIMITED)
    private val deltaBuffer = DeltaBuffer()
    private val seqCounter = AtomicLong(0L)

    private val sessionId: String = UUID.randomUUID().toString()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var lastAppliedSeq: Long = 0L
    private var ioJob: Job? = null

    fun port(): Int = serverSocket?.localPort
        ?: error("server not started")

    /**
     * Bind the listen socket and return the port. The session enters
     * WaitingForGuest. Call enqueueOp(...) once a guest connects (handled
     * internally by the accept loop).
     */
    suspend fun startListening(): Int = withContext(Dispatchers.IO) {
        val ss = ServerSocket(0)
        serverSocket = ss
        _state.value = CoopSessionState.WaitingForGuest
        scope.launch { acceptLoop(ss) }
        ss.localPort
    }

    /** Enqueue an Op to be sent to the connected guest. No-op if no guest. */
    fun enqueueOp(op: Op) {
        outQueue.trySend(op)
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (scope.isActive) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (!scope.isActive) return
                _state.value = CoopSessionState.Ended(CoopSessionState.EndReason.NetworkLost)
                return
            }
            handleConnection(socket)
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val helloFrame = Frame.read(input) ?: return socket.close()
        if (helloFrame.type != FrameType.HELLO) {
            sendBye(output, CoopSessionState.EndReason.ProtocolError)
            socket.close(); return
        }
        val hello = OpCodec.decode<HelloPayload>(helloFrame.payload)

        if (hello.token != token) {
            Frame.write(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.BadToken)),
            )
            output.flush(); socket.close(); return
        }
        if (hello.clientVersion != protocolVersion) {
            Frame.write(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.VersionMismatch)),
            )
            output.flush(); socket.close(); return
        }

        // Accept.
        Frame.write(
            output,
            FrameType.HELLO_OK,
            OpCodec.encode(HelloOkPayload(sessionId = sessionId, protocolVersion = protocolVersion)),
        )
        output.flush()

        clientSocket = socket
        val isReconnect = hello.lastAppliedSeq > 0
        lastAppliedSeq = hello.lastAppliedSeq

        if (isReconnect) {
            // Replay buffered deltas after lastAppliedSeq.
            deltaBuffer.opsAfter(lastAppliedSeq).forEach { (seq, op) ->
                Frame.write(
                    output,
                    FrameType.DELTA,
                    OpCodec.encode(DeltaPayload(seq, op)),
                )
            }
            output.flush()
        } else {
            // Bulk transfer.
            sendBulk(output)
        }

        _state.value = CoopSessionState.Connected(peerName = hello.deviceName)
        phase = Phase.Live

        ioJob = scope.launch { livePhase(input, output) }
    }

    private suspend fun sendBulk(output: OutputStream) {
        Frame.write(
            output,
            FrameType.BULK_BEGIN,
            OpCodec.encode(
                BulkBeginPayload(
                    projectId = projectId,
                    layerCount = layerCount,
                    fingerprintBytes = fingerprintBytes.size,
                    projectBytes = projectBytes.size,
                )
            ),
        )
        // Chunk payload into 64KB frames.
        chunkAndWrite(output, FrameType.BULK_FINGERPRINT, fingerprintBytes)
        chunkAndWrite(output, FrameType.BULK_PROJECT, projectBytes)
        Frame.write(output, FrameType.BULK_END, ByteArray(0))
        output.flush()

        // Wait up to 30s for BULK_ACK; if missing, abort.
        // (Simpler: just trust that subsequent live-phase reads will get it.)
    }

    private fun chunkAndWrite(output: OutputStream, type: FrameType, bytes: ByteArray) {
        val chunkSize = 64 * 1024
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + chunkSize).coerceAtMost(bytes.size)
            Frame.write(output, type, bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private suspend fun livePhase(input: java.io.InputStream, output: OutputStream) {
        // Two concurrent loops: outbound (drain queue) and inbound (read from peer).
        scope.launch { outboundLoop(output) }
        scope.launch { inboundLoop(input, output) }
        scope.launch { heartbeatLoop(output) }
    }

    private suspend fun outboundLoop(output: OutputStream) {
        for (op in outQueue) {
            val seq = seqCounter.incrementAndGet()
            val payload = OpCodec.encode(DeltaPayload(seq, op))
            deltaBuffer.append(seq, op, opSizeEstimator(op))
            try {
                Frame.write(output, FrameType.DELTA, payload)
                output.flush()
            } catch (e: Exception) {
                // Connection broken; enter reconnecting.
                enterReconnecting()
                return
            }
        }
    }

    private suspend fun inboundLoop(input: java.io.InputStream, output: OutputStream) {
        while (scope.isActive) {
            val frame = try {
                Frame.read(input) ?: run { enterReconnecting(); return }
            } catch (e: Exception) {
                enterReconnecting(); return
            }
            when (frame.type) {
                FrameType.DELTA_ACK -> {
                    val ack = OpCodec.decode<DeltaAckPayload>(frame.payload)
                    deltaBuffer.trimUpTo(ack.lastSeq)
                }
                FrameType.PING -> {
                    val ping = OpCodec.decode<PingPayload>(frame.payload)
                    Frame.write(output, FrameType.PONG, OpCodec.encode(ping))
                    output.flush()
                }
                FrameType.BULK_ACK -> { /* bulk done; ignore */ }
                FrameType.BYE -> { close(CoopSessionState.EndReason.HostClosed); return }
                else -> {
                    // Unexpected frame in this direction; ignore but log.
                }
            }
        }
    }

    private suspend fun heartbeatLoop(output: OutputStream) {
        while (scope.isActive) {
            delay(5_000)
            try {
                Frame.write(output, FrameType.PING, OpCodec.encode(PingPayload(System.currentTimeMillis())))
                output.flush()
            } catch (e: Exception) {
                enterReconnecting(); return
            }
        }
    }

    private suspend fun enterReconnecting() {
        if (phase == Phase.Reconnecting || phase == Phase.Ended) return
        phase = Phase.Reconnecting
        _state.value = CoopSessionState.Reconnecting
        clientSocket?.close()
        clientSocket = null

        // Wait up to 30s for guest reconnect.
        val reconnected = withTimeoutOrNull(30_000L) {
            while (clientSocket == null) {
                delay(500)
            }
            true
        } ?: false
        if (!reconnected) {
            close(CoopSessionState.EndReason.NetworkLost)
        }
    }

    private fun sendBye(output: OutputStream, reason: CoopSessionState.EndReason) {
        try {
            Frame.write(output, FrameType.BYE, OpCodec.encode(ByePayload(reason)))
            output.flush()
        } catch (_: Exception) { /* socket may already be closed */ }
    }

    override suspend fun close(reason: CoopSessionState.EndReason) {
        phase = Phase.Ended
        _state.value = CoopSessionState.Ended(reason)
        clientSocket?.let { sendBye(it.getOutputStream(), reason); it.close() }
        serverSocket?.close()
        outQueue.close()
        deltaBuffer.clear()
        scope.cancel()
    }
}
```

- [ ] **Step 9.2: Compile**

```bash
./gradlew :collab:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 9.3: Commit**

```bash
git add collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/HostSession.kt
git commit -m "feat: add HostSession state machine + bulk + delta send + reconnect"
```

---

## Task 10: GuestSession — handshake, bulk receive, delta receive, reconnect

**Files:**
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/GuestSession.kt`

- [ ] **Step 10.1: Create GuestSession**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/GuestSession.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

internal class GuestSession(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val protocolVersion: Int,
    private val localDeviceName: String,
    private val onBulkReceived: suspend (fingerprint: ByteArray, project: ByteArray) -> Unit,
    private val onOp: suspend (Op) -> Unit,
) : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionId: String? = null
    @Volatile private var lastAppliedSeq: Long = 0L
    private var socket: Socket? = null

    suspend fun connect() {
        scope.launch { connectLoop(isReconnect = false) }
    }

    private suspend fun connectLoop(isReconnect: Boolean) {
        try {
            val s = Socket(host, port)
            socket = s
            val input = s.getInputStream()
            val output = s.getOutputStream()

            // Send HELLO.
            Frame.write(
                output,
                FrameType.HELLO,
                OpCodec.encode(
                    HelloPayload(
                        token = token,
                        clientVersion = protocolVersion,
                        deviceName = localDeviceName,
                        lastAppliedSeq = if (isReconnect) lastAppliedSeq else 0L,
                    )
                ),
            )
            output.flush()

            val response = Frame.read(input) ?: error("peer closed before HELLO_OK")
            when (response.type) {
                FrameType.HELLO_OK -> {
                    val helloOk = OpCodec.decode<HelloOkPayload>(response.payload)
                    sessionId = helloOk.sessionId
                    if (!isReconnect) {
                        receiveBulk(input, output)
                    }
                    _state.value = CoopSessionState.Connected(peerName = "host")
                    phase = Phase.Live
                    livePhase(input, output)
                }
                FrameType.HELLO_REJECTED -> {
                    val rej = OpCodec.decode<HelloRejectedPayload>(response.payload)
                    val reason = when (rej.reason) {
                        HelloRejectedPayload.RejectReason.BadToken -> CoopSessionState.EndReason.BadToken
                        HelloRejectedPayload.RejectReason.VersionMismatch -> CoopSessionState.EndReason.VersionMismatch
                        HelloRejectedPayload.RejectReason.AlreadyHosting -> CoopSessionState.EndReason.HostClosed
                    }
                    close(reason)
                }
                else -> close(CoopSessionState.EndReason.ProtocolError)
            }
        } catch (e: Exception) {
            if (isReconnect) {
                close(CoopSessionState.EndReason.NetworkLost)
            } else {
                close(CoopSessionState.EndReason.NetworkLost)
            }
        }
    }

    private suspend fun receiveBulk(input: InputStream, output: OutputStream) {
        val begin = Frame.read(input) ?: error("EOF in bulk")
        require(begin.type == FrameType.BULK_BEGIN)
        val beginPayload = OpCodec.decode<BulkBeginPayload>(begin.payload)

        val fingerprint = receiveChunked(input, FrameType.BULK_FINGERPRINT, beginPayload.fingerprintBytes)
        val project = receiveChunked(input, FrameType.BULK_PROJECT, beginPayload.projectBytes)

        val end = Frame.read(input) ?: error("EOF before BULK_END")
        require(end.type == FrameType.BULK_END)

        Frame.write(output, FrameType.BULK_ACK, OpCodec.encode(BulkAckPayload(0L)))
        output.flush()

        onBulkReceived(fingerprint, project)
    }

    private fun receiveChunked(input: InputStream, expectedType: FrameType, totalBytes: Int): ByteArray {
        val buffer = ByteArray(totalBytes)
        var offset = 0
        while (offset < totalBytes) {
            val frame = Frame.read(input) ?: error("EOF mid-bulk")
            require(frame.type == expectedType) { "expected $expectedType, got ${frame.type}" }
            System.arraycopy(frame.payload, 0, buffer, offset, frame.payload.size)
            offset += frame.payload.size
        }
        return buffer
    }

    private suspend fun livePhase(input: InputStream, output: OutputStream) {
        scope.launch {
            while (scope.isActive) {
                val frame = try {
                    Frame.read(input) ?: run {
                        attemptReconnect(); return@launch
                    }
                } catch (e: Exception) {
                    attemptReconnect(); return@launch
                }
                when (frame.type) {
                    FrameType.DELTA -> {
                        val delta = OpCodec.decode<DeltaPayload>(frame.payload)
                        if (delta.seq > lastAppliedSeq) {
                            onOp(delta.op)
                            lastAppliedSeq = delta.seq
                        }
                    }
                    FrameType.PING -> {
                        val ping = OpCodec.decode<PingPayload>(frame.payload)
                        Frame.write(output, FrameType.PONG, OpCodec.encode(ping))
                        output.flush()
                    }
                    FrameType.BYE -> {
                        val bye = OpCodec.decode<ByePayload>(frame.payload)
                        close(bye.reason); return@launch
                    }
                    else -> { /* ignore */ }
                }
            }
        }
        scope.launch {
            // Periodic DELTA_ACK.
            while (scope.isActive) {
                delay(1_000)
                try {
                    Frame.write(output, FrameType.DELTA_ACK, OpCodec.encode(DeltaAckPayload(lastAppliedSeq)))
                    output.flush()
                } catch (_: Exception) { /* let inbound loop handle */ }
            }
        }
    }

    private suspend fun attemptReconnect() {
        phase = Phase.Reconnecting
        _state.value = CoopSessionState.Reconnecting
        socket?.close()
        socket = null
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            try {
                connectLoop(isReconnect = true)
                return // succeeded, connectLoop transitioned state
            } catch (_: Exception) {
                // try again
            }
        }
        close(CoopSessionState.EndReason.NetworkLost)
    }

    override suspend fun close(reason: CoopSessionState.EndReason) {
        phase = Phase.Ended
        _state.value = CoopSessionState.Ended(reason)
        socket?.close()
        scope.cancel()
    }
}
```

- [ ] **Step 10.2: Compile + commit**

```bash
./gradlew :collab:compileDebugKotlin
```

Expected: PASS.

```bash
git add collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/GuestSession.kt
git commit -m "feat: add GuestSession state machine + bulk receive + reconnect"
```

---

## Task 11: Delete DiscoveryManager + clean manifest + build

**Files:**
- Delete: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/DiscoveryManager.kt`
- Modify: `collab/src/main/AndroidManifest.xml`
- Modify: `collab/src/main/cpp/CollaborationBridge.cpp` (audit only — leave unless tied to discovery)

- [ ] **Step 11.1: Delete DiscoveryManager**

```bash
git rm collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/DiscoveryManager.kt
```

- [ ] **Step 11.2: Strip unused permissions from manifest**

Replace `collab/src/main/AndroidManifest.xml` contents with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />

</manifest>
```

- [ ] **Step 11.3: Audit CollaborationBridge.cpp**

```bash
grep -n "discovery\|nsd\|p2p\|wifi" collab/src/main/cpp/CollaborationBridge.cpp
```

If matches reference removed JNI methods only used by `DiscoveryManager`, delete those native methods. If matches are only `nativeExportFingerprint` / `nativeAlignToPeer`, leave the file alone.

- [ ] **Step 11.4: Compile**

```bash
./gradlew :collab:compileDebugKotlin
```

Expected: PASS (the new `Session*.kt` files don't reference `DiscoveryManager`).

- [ ] **Step 11.5: Commit**

```bash
git add collab/src/main/AndroidManifest.xml
git commit -m "chore: remove DiscoveryManager and discovery-only permissions"
```

---

## Task 12: Rewrite CollaborationManager as state-machine orchestrator

**Files:**
- Modify (rewrite): `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/CollaborationManager.kt`
- Create: `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/OpEmitterImpl.kt`

- [ ] **Step 12.1: Replace CollaborationManager**

Open `collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/CollaborationManager.kt`. Replace the file contents with:

```kotlin
package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import com.hereliesaz.graffitixr.core.collaboration.wire.QrPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Public-API. Editor + AR features depend on this surface only. */
@Singleton
class CollaborationManager @Inject constructor() {

    private val _state: MutableStateFlow<CoopSessionState> = MutableStateFlow(CoopSessionState.Idle)
    val state: StateFlow<CoopSessionState> get() = _state

    @Volatile private var hostSession: HostSession? = null
    @Volatile private var guestSession: GuestSession? = null
    @Volatile private var lastQrPayload: QrPayload? = null

    /** Begin hosting. Returns the QR payload to display. */
    suspend fun startHosting(
        projectId: String,
        layerCount: Int,
        fingerprintBytes: ByteArray,
        projectBytes: ByteArray,
        localDeviceName: String,
        protocolVersion: Int = 1,
    ): String {
        check(hostSession == null && guestSession == null) { "already in a session" }
        val token = QrPayload.newToken()
        val session = HostSession(
            token = token,
            protocolVersion = protocolVersion,
            localDeviceName = localDeviceName,
            fingerprintBytes = fingerprintBytes,
            projectBytes = projectBytes,
            projectId = projectId,
            layerCount = layerCount,
        )
        hostSession = session
        observe(session.state)
        val port = session.startListening()
        val payload = QrPayload(
            host = LocalIp.discover() ?: "127.0.0.1",
            port = port,
            token = token,
            protocolVersion = protocolVersion,
        )
        lastQrPayload = payload
        return payload.encode()
    }

    suspend fun stopHosting() {
        hostSession?.close(CoopSessionState.EndReason.UserLeft)
        hostSession = null
    }

    suspend fun joinFromQr(
        qr: String,
        localDeviceName: String,
        onBulkReceived: suspend (fingerprint: ByteArray, project: ByteArray) -> Unit,
        onOp: suspend (Op) -> Unit,
    ) {
        check(hostSession == null && guestSession == null) { "already in a session" }
        val payload = QrPayload.parse(qr)
        val session = GuestSession(
            host = payload.host,
            port = payload.port,
            token = payload.token,
            protocolVersion = payload.protocolVersion,
            localDeviceName = localDeviceName,
            onBulkReceived = onBulkReceived,
            onOp = onOp,
        )
        guestSession = session
        observe(session.state)
        session.connect()
    }

    suspend fun leaveSession() {
        guestSession?.close(CoopSessionState.EndReason.UserLeft)
        guestSession = null
        hostSession?.close(CoopSessionState.EndReason.UserLeft)
        hostSession = null
    }

    /** Called by OpEmitterImpl on every editor mutation. */
    internal fun enqueueHostOp(op: Op) {
        hostSession?.enqueueOp(op)
    }

    private fun observe(stateFlow: StateFlow<CoopSessionState>) {
        // Cheap delegation: latest state wins.
        kotlinx.coroutines.MainScope().launch {
            stateFlow.collect { _state.value = it }
        }
    }
}
```

The `LocalIp.discover()` helper is needed:

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/LocalIp.kt
package com.hereliesaz.graffitixr.core.collaboration

import java.net.NetworkInterface

internal object LocalIp {
    fun discover(): String? {
        return NetworkInterface.getNetworkInterfaces().toList().firstNotNullOfOrNull { iface ->
            if (!iface.isUp || iface.isLoopback) return@firstNotNullOfOrNull null
            iface.inetAddresses.toList().firstOrNull {
                !it.isLoopbackAddress && it.address.size == 4 // IPv4
            }?.hostAddress
        }
    }
}
```

- [ ] **Step 12.2: Create OpEmitterImpl**

```kotlin
// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/OpEmitterImpl.kt
package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.model.Op
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpEmitterImpl @Inject constructor(
    private val collaborationManager: CollaborationManager,
) : OpEmitter {
    override fun emit(op: Op) {
        collaborationManager.enqueueHostOp(op)
    }
}
```

- [ ] **Step 12.3: Wire OpEmitter binding via Hilt**

The DI module that binds `OpEmitter` lives in the `app` module (Hilt root). Find an existing Hilt `@Module` (e.g., `app/src/main/.../di/AppModule.kt`) and add:

```kotlin
@Binds
@Singleton
abstract fun bindOpEmitter(impl: OpEmitterImpl): OpEmitter
```

If no such module exists yet, create one at `app/src/main/java/com/hereliesaz/graffitixr/di/CoopModule.kt`:

```kotlin
package com.hereliesaz.graffitixr.di

import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.core.collaboration.OpEmitterImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoopModule {
    @Binds
    @Singleton
    abstract fun bindOpEmitter(impl: OpEmitterImpl): OpEmitter
}
```

- [ ] **Step 12.4: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

```bash
git add collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/CollaborationManager.kt \
        collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/OpEmitterImpl.kt \
        collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/LocalIp.kt \
        app/src/main/java/com/hereliesaz/graffitixr/di/CoopModule.kt
git commit -m "feat: rewrite CollaborationManager as state-machine session orchestrator"
```

---

## Task 13: Update ArUiState — add typed CoopSessionState; deprecate freeform fields

**Files:**
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt:130-148`

- [ ] **Step 13.1: Replace the co-op fields**

In `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt`, around line 130-148, find the `ArUiState` data class. Replace the four co-op fields:

Old:
```kotlin
    val isSyncing: Boolean = false,
    val isCoopSearching: Boolean = false,
    val coopStatus: String? = null,
    val coopRole: CoopRole = CoopRole.NONE,
```

New:
```kotlin
    val coopRole: CoopRole = CoopRole.NONE,
    val coopSessionState: CoopSessionState = CoopSessionState.Idle,
```

(Keep `enum class CoopRole { NONE, HOST, GUEST }` unchanged.)

- [ ] **Step 13.2: Resolve compile errors at consumers**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | grep "error:" | head -20
```

For each error referencing `isSyncing`, `isCoopSearching`, `coopStatus`:
- In `MainActivity.kt:991` (the `coop.main` rail item), the old `text = arUiState.coopStatus ?: "Co-op"` and `color = if (arUiState.isCoopSearching || arUiState.isSyncing) Cyan else navItemColor` lines update in Task 18 (rail rewiring). For now, replace with typed-state branching:

```kotlin
text = when (val s = arUiState.coopSessionState) {
    is CoopSessionState.Idle -> "Co-op"
    is CoopSessionState.WaitingForGuest -> "Waiting…"
    is CoopSessionState.Connected -> "Connected"
    is CoopSessionState.Reconnecting -> "Reconnecting…"
    is CoopSessionState.Ended -> "Co-op"
},
color = when (arUiState.coopSessionState) {
    is CoopSessionState.WaitingForGuest,
    is CoopSessionState.Reconnecting,
    is CoopSessionState.Connected -> Cyan
    else -> navItemColor
},
```

- [ ] **Step 13.3: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt \
        app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "refactor: typed CoopSessionState replaces freeform coopStatus"
```

---

## Task 14: ArViewModel — startHosting / joinFromQr / leaveSession

**Files:**
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt:99` (the existing `startCollaborationDiscovery` method)

- [ ] **Step 14.1: Replace startCollaborationDiscovery**

Open `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt`. Find `startCollaborationDiscovery` (line ~99). Replace with three methods:

```kotlin
    fun startHosting() {
        viewModelScope.launch {
            try {
                val fingerprint = slamManager.exportFingerprint()
                val projectBytes = projectManager.serializeCurrentProject()
                val qrString = collaborationManager.startHosting(
                    projectId = projectManager.currentProjectId(),
                    layerCount = uiState.value.layers.size,
                    fingerprintBytes = fingerprint,
                    projectBytes = projectBytes,
                    localDeviceName = android.os.Build.MODEL,
                )
                _uiState.update {
                    it.copy(
                        coopRole = CoopRole.HOST,
                        coopSessionState = CoopSessionState.WaitingForGuest,
                    )
                }
                // Surface qrString to UI (separate stream)
                _hostQrPayload.value = qrString
            } catch (e: Exception) {
                _uiState.update { it.copy(coopSessionState = CoopSessionState.Ended(CoopSessionState.EndReason.NetworkLost)) }
            }
            // Observe collaborationManager.state and propagate.
            collaborationManager.state.collect { newState ->
                _uiState.update { it.copy(coopSessionState = newState) }
            }
        }
    }

    fun joinFromQr(qr: String) {
        viewModelScope.launch {
            try {
                collaborationManager.joinFromQr(
                    qr = qr,
                    localDeviceName = android.os.Build.MODEL,
                    onBulkReceived = { fingerprint, project ->
                        slamManager.alignToPeer(fingerprint)
                        projectManager.loadAsSpectator(project)
                    },
                    onOp = { op -> editorViewModel.applySpectatorOp(op) },
                )
                _uiState.update { it.copy(coopRole = CoopRole.GUEST) }
                collaborationManager.state.collect { newState ->
                    _uiState.update { it.copy(coopSessionState = newState) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(coopSessionState = CoopSessionState.Ended(CoopSessionState.EndReason.NetworkLost)) }
            }
        }
    }

    fun leaveSession() {
        viewModelScope.launch {
            collaborationManager.leaveSession()
            _uiState.update { it.copy(coopRole = CoopRole.NONE, coopSessionState = CoopSessionState.Idle) }
            _hostQrPayload.value = null
        }
    }
```

Add the QR exposure flow at the top of the class:

```kotlin
private val _hostQrPayload = MutableStateFlow<String?>(null)
val hostQrPayload: StateFlow<String?> = _hostQrPayload
```

- [ ] **Step 14.2: Inject CollaborationManager + EditorViewModel into ArViewModel**

Add to the `@HiltViewModel`-annotated constructor:

```kotlin
private val collaborationManager: CollaborationManager,
private val editorViewModel: EditorViewModel, // already injected? if not, this likely violates ViewModel-of-ViewModel — see step 14.3
```

If injecting another ViewModel into a ViewModel is not the project's pattern (it commonly isn't), the cleaner shape is: route Op application through a callback registered by the activity. Adjust accordingly: the activity holds both ViewModels and connects `arViewModel.onSpectatorOp = { editorViewModel.applySpectatorOp(it) }`.

- [ ] **Step 14.3: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS (after resolving any DI/ViewModel issues).

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt
git commit -m "feat: ArViewModel startHosting/joinFromQr/leaveSession"
```

---

## Task 15: Add ZXing QR generator + scanner deps + create overlay composables

**Files:**
- Modify: `app/build.gradle.kts` (add ZXing deps)
- Modify: `gradle/libs.versions.toml` (add aliases)
- Create: `app/src/main/java/com/hereliesaz/graffitixr/ui/coop/CoopHostQrOverlay.kt`
- Create: `app/src/main/java/com/hereliesaz/graffitixr/ui/coop/CoopJoinQrScannerOverlay.kt`
- Create: `app/src/main/java/com/hereliesaz/graffitixr/ui/coop/CoopSpectatorBanner.kt`

- [ ] **Step 15.1: Add deps**

In `gradle/libs.versions.toml` `[versions]`:

```toml
zxingCore = "3.5.3"
zxingAndroidEmbedded = "4.3.0"
```

In `[libraries]`:

```toml
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxingCore" }
zxing-android-embedded = { group = "com.journeyapps", name = "zxing-android-embedded", version.ref = "zxingAndroidEmbedded" }
```

In `app/build.gradle.kts` dependencies:

```kotlin
implementation(libs.zxing.core)
implementation(libs.zxing.android.embedded)
```

- [ ] **Step 15.2: Create CoopHostQrOverlay**

```kotlin
// app/src/main/java/com/hereliesaz/graffitixr/ui/coop/CoopHostQrOverlay.kt
package com.hereliesaz.graffitixr.ui.coop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import android.graphics.Color as AColor

@Composable
fun CoopHostQrOverlay(
    qrPayload: String,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(qrPayload) { qrPayload.toQrBitmap(size = 512) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Scan to join", color = Color.White)
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "co-op QR")
            Button(onClick = onStopSharing) { Text("Stop sharing") }
        }
    }
}

private fun String.toQrBitmap(size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(this, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) AColor.BLACK else AColor.WHITE)
        }
    }
    return bmp
}
```

- [ ] **Step 15.3: Create CoopJoinQrScannerOverlay**

```kotlin
// app/src/main/java/com/hereliesaz/graffitixr/ui/coop/CoopJoinQrScannerOverlay.kt
package com.hereliesaz.graffitixr.ui.coop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun CoopJoinQrScannerOverlay(
    onScanned: (String) -> Unit,
    onCancelled: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents == null) onCancelled()
        else onScanned(result.contents)
    }
    LaunchedEffect(Unit) {
        val opts = ScanOptions().apply {
            setPrompt("Scan host QR")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        launcher.launch(opts)
    }
}
```

- [ ] **Step 15.4: Create CoopSpectatorBanner**

```kotlin
// app/src/main/java/com/hereliesaz/graffitixr/ui/coop/CoopSpectatorBanner.kt
package com.hereliesaz.graffitixr.ui.coop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CoopSpectatorBanner(
    peerName: String,
    isReconnecting: Boolean,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isReconnecting) "Reconnecting to $peerName…" else "Spectating $peerName",
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onLeave) { Text("Leave") }
    }
}
```

- [ ] **Step 15.5: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

```bash
git add app/build.gradle.kts gradle/libs.versions.toml \
        app/src/main/java/com/hereliesaz/graffitixr/ui/coop/CoopHostQrOverlay.kt \
        app/src/main/java/com/hereliesaz/graffitixr/ui/coop/CoopJoinQrScannerOverlay.kt \
        app/src/main/java/com/hereliesaz/graffitixr/ui/coop/CoopSpectatorBanner.kt
git commit -m "feat: add co-op host QR overlay, scanner overlay, and spectator banner"
```

---

## Task 16: Wire coop.main rail callback + show overlays + hide guest editing

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

- [ ] **Step 16.1: State for overlay visibility**

Inside the composable scope (near the top of `setContent`), add:

```kotlin
val coopState = arUiState.coopSessionState
var showJoinScanner by remember { mutableStateOf(false) }
val hostQr by arViewModel.hostQrPayload.collectAsState()
```

- [ ] **Step 16.2: Replace coop.main rail callback**

Locate the `coop.main` rail item (around `MainActivity.kt:991`). Replace the existing callback body with state-driven branching:

```kotlin
azRailSubItem(
    id = "coop.main",
    hostId = "mode.host",
    text = when (coopState) {
        is CoopSessionState.Idle -> "Co-op"
        is CoopSessionState.WaitingForGuest -> "Waiting…"
        is CoopSessionState.Connected -> "Connected"
        is CoopSessionState.Reconnecting -> "Reconnecting…"
        is CoopSessionState.Ended -> "Co-op"
    },
    color = when (coopState) {
        is CoopSessionState.WaitingForGuest,
        is CoopSessionState.Reconnecting,
        is CoopSessionState.Connected -> Cyan
        else -> navItemColor
    },
    shape = AzButtonShape.RECTANGLE
) {
    when (coopState) {
        is CoopSessionState.Idle, is CoopSessionState.Ended -> {
            if (arUiState.coopRole == CoopRole.NONE) {
                // Offer either Host or Join. Simplest UI: always Host on tap;
                // a separate path triggers Join.
                arViewModel.startHosting()
            } else if (arUiState.coopRole == CoopRole.GUEST) {
                arViewModel.leaveSession()
            }
        }
        is CoopSessionState.WaitingForGuest, is CoopSessionState.Connected -> {
            arViewModel.leaveSession()
        }
        is CoopSessionState.Reconnecting -> { /* no-op */ }
    }
}
```

To start Join, add a separate sub-item adjacent to `coop.main`:

```kotlin
azRailSubItem(
    id = "coop.join",
    hostId = "mode.host",
    text = "Join",
    color = navItemColor,
    shape = AzButtonShape.RECTANGLE,
) {
    showJoinScanner = true
}
```

(Add `"coop.join"` to `enumerateRailItemIds` and to `buildHelpItems` with whatever help string fits, or note the orphan in `RailIntegrityCheck`'s warn output.)

- [ ] **Step 16.3: Render overlays inside `onscreen { }`**

Inside the `onscreen { }` block in `MainActivity.kt`, after the existing children, add:

```kotlin
if (hostQr != null && coopState is CoopSessionState.WaitingForGuest) {
    CoopHostQrOverlay(
        qrPayload = hostQr!!,
        onStopSharing = { arViewModel.leaveSession() },
    )
}
if (showJoinScanner) {
    CoopJoinQrScannerOverlay(
        onScanned = { qr ->
            showJoinScanner = false
            arViewModel.joinFromQr(qr)
        },
        onCancelled = { showJoinScanner = false },
    )
}
if (arUiState.coopRole == CoopRole.GUEST &&
    (coopState is CoopSessionState.Connected || coopState is CoopSessionState.Reconnecting)) {
    CoopSpectatorBanner(
        peerName = (coopState as? CoopSessionState.Connected)?.peerName ?: "host",
        isReconnecting = coopState is CoopSessionState.Reconnecting,
        onLeave = { arViewModel.leaveSession() },
        modifier = Modifier.align(Alignment.TopCenter),
    )
}
```

- [ ] **Step 16.4: Hide editing rail items in guest mode**

Wrap the existing `if (canEdit)` block in `ConfigureRailItems` (around line 1042) with an additional `&& arUiState.coopRole != CoopRole.GUEST` check:

```kotlin
if (canEdit && arUiState.coopRole != CoopRole.GUEST) {
    // existing design.host + design.addImg + design.addDraw + ... block
}
```

Same gating for the layer-loop section (around line 1076 onward).

- [ ] **Step 16.5: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "feat: wire coop rail callbacks, QR/scanner/spectator overlays, guest gating"
```

---

## Task 17: EditorViewModel — emit Ops + applySpectatorOp + loadAsSpectator

**Files:**
- Modify: `feature/editor/.../EditorViewModel.kt` (or wherever the existing layer-mutation methods live)

- [ ] **Step 17.1: Inject OpEmitter**

Add to the `@HiltViewModel`-annotated constructor:

```kotlin
private val opEmitter: OpEmitter,
```

Add the import:

```kotlin
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.model.Op
```

- [ ] **Step 17.2: Emit on every mutation**

For each existing layer-mutation method (e.g., `onAddBlankLayer`, `onAddTextLayer`, layer transform handlers, brush stroke completion handlers, text edits), add `opEmitter.emit(...)` immediately after mutating local state.

Pattern (concrete shape will vary per method):

```kotlin
fun onAddBlankLayer() {
    val newLayer = createBlankLayer()
    _uiState.update { it.copy(layers = it.layers + newLayer) }
    opEmitter.emit(Op.LayerAdd(newLayer))
}

fun onLayerTransform(layerId: String, matrix: List<Float>) {
    _uiState.update { state ->
        state.copy(layers = state.layers.map {
            if (it.id == layerId) it.copy(transform = matrix) else it
        })
    }
    opEmitter.emit(Op.LayerTransform(layerId, matrix))
}

// etc. for LayerRemove, LayerReorder, LayerPropsChange, StrokeComplete, TextContentChange.
```

If the project has a single `applyLayerOp(op)` choke-point, prefer routing all mutations through it and emitting once there. Otherwise add per-method emits.

- [ ] **Step 17.3: Add applySpectatorOp**

```kotlin
/** Applies a remote Op without echoing it back through opEmitter. */
fun applySpectatorOp(op: Op) {
    when (op) {
        is Op.LayerAdd -> _uiState.update { it.copy(layers = it.layers + op.layer) }
        is Op.LayerRemove -> _uiState.update { state ->
            state.copy(layers = state.layers.filterNot { it.id == op.layerId })
        }
        is Op.LayerReorder -> _uiState.update { state ->
            val byId = state.layers.associateBy { it.id }
            state.copy(layers = op.newOrder.mapNotNull { byId[it] })
        }
        is Op.LayerTransform -> _uiState.update { state ->
            state.copy(layers = state.layers.map {
                if (it.id == op.layerId) it.copy(transform = op.matrix) else it
            })
        }
        is Op.LayerPropsChange -> _uiState.update { state ->
            state.copy(layers = state.layers.map {
                if (it.id == op.layerId) it.copy(props = op.props) else it
            })
        }
        is Op.StrokeComplete -> _uiState.update { state ->
            state.copy(layers = state.layers.map {
                if (it.id == op.layerId) it.copy(strokes = it.strokes + op.stroke) else it
            })
        }
        is Op.TextContentChange -> _uiState.update { state ->
            state.copy(layers = state.layers.map {
                if (it.id == op.layerId) it.copy(text = op.text) else it
            })
        }
    }
}
```

(Adjust property names to match the actual `Layer` shape in `EditorModels.kt` — the structure is illustrative, not necessarily field-name accurate.)

- [ ] **Step 17.4: Add loadAsSpectator**

```kotlin
fun loadAsSpectator(projectBytes: ByteArray) {
    val project = projectManager.deserialize(projectBytes)
    _uiState.update { it.copy(layers = project.layers) }
}
```

- [ ] **Step 17.5: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

```bash
git add feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt
git commit -m "feat: EditorViewModel emits Ops + applies spectator Ops + loadAsSpectator"
```

---

## Task 18: Integration test — LocalLoopTest (in-process host + guest)

**Files:**
- Create: `collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/LocalLoopTest.kt`

- [ ] **Step 18.1: Write the test**

```kotlin
// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/LocalLoopTest.kt
package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * In-process: host listens on a real ServerSocket on localhost, guest connects
 * via real Socket. Verifies bulk + delta + ack flow.
 */
class LocalLoopTest {

    @Test
    fun `host-to-guest 50 deltas survive bulk and arrive in order`() = runBlocking {
        val fingerprint = ByteArray(1024) { it.toByte() }
        val projectBytes = ByteArray(2048) { (it * 7).toByte() }

        val host = HostSession(
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "host",
            fingerprintBytes = fingerprint,
            projectBytes = projectBytes,
            projectId = "p1",
            layerCount = 0,
        )
        val port = host.startListening()

        val received = mutableListOf<Op>()
        var bulkOk = false
        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "guest",
            onBulkReceived = { fp, pb ->
                bulkOk = fp.contentEquals(fingerprint) && pb.contentEquals(projectBytes)
            },
            onOp = { op -> received.add(op) },
        )
        guest.connect()

        // Wait for bulk handshake to land.
        withTimeout(5_000) {
            while (!bulkOk) delay(50)
        }

        // Host emits 50 ops.
        repeat(50) { i ->
            host.enqueueOp(Op.LayerAdd(Layer(id = "L$i", name = "n$i")))
        }

        withTimeout(10_000) {
            while (received.size < 50) delay(50)
        }

        assertEquals(50, received.size)
        received.forEachIndexed { i, op ->
            assertEquals("L$i", (op as Op.LayerAdd).layer.id)
        }

        host.close(CoopSessionState.EndReason.UserLeft)
        guest.close(CoopSessionState.EndReason.UserLeft)
    }
}
```

- [ ] **Step 18.2: Run + commit**

```bash
./gradlew :collab:testDebugUnitTest --tests com.hereliesaz.graffitixr.core.collaboration.LocalLoopTest
```

Expected: PASS.

```bash
git add collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/LocalLoopTest.kt
git commit -m "test: in-process host+guest LocalLoopTest covering bulk + 50 deltas"
```

---

## Task 19: Integration test — InterruptedBulkTest

**Files:**
- Create: `collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/InterruptedBulkTest.kt`

- [ ] **Step 19.1: Write the test**

```kotlin
// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/InterruptedBulkTest.kt
package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedBulkTest {

    @Test
    fun `guest sees Ended NetworkLost when host closes mid-bulk`() = runBlocking {
        val fingerprint = ByteArray(64 * 1024) { it.toByte() }   // 64KB chunk
        val projectBytes = ByteArray(2 * 1024 * 1024) { 0 }       // 2MB → multiple chunks

        val host = HostSession(
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "host",
            fingerprintBytes = fingerprint,
            projectBytes = projectBytes,
            projectId = "p1",
            layerCount = 0,
        )
        val port = host.startListening()

        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "guest",
            onBulkReceived = { _, _ -> },
            onOp = { },
        )
        guest.connect()

        // Brief pause to let bulk start.
        delay(100)

        // Force-close the host.
        host.close(CoopSessionState.EndReason.NetworkLost)

        // Guest should transition to Ended within a reasonable timeout.
        val state = withTimeout(35_000) {
            guest.state.first { it is CoopSessionState.Ended }
        }
        assertTrue("expected Ended state, got $state", state is CoopSessionState.Ended)
    }
}
```

- [ ] **Step 19.2: Run + commit**

```bash
./gradlew :collab:testDebugUnitTest --tests com.hereliesaz.graffitixr.core.collaboration.InterruptedBulkTest
```

Expected: PASS within 35s.

```bash
git add collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/InterruptedBulkTest.kt
git commit -m "test: guest gracefully ends when host closes mid-bulk"
```

---

## Task 20: Manual verification

**Files:** None (verification only).

- [ ] **Step 20.1: Build + install**

```bash
./gradlew :app:installDebug
```

Install on two devices on the same Wi-Fi.

- [ ] **Step 20.2: Pair via QR**

On Device A: tap `coop.main` rail item. QR overlay appears. On Device B: tap the `coop.join` rail item. Scanner opens. Scan the QR. Verify Device B receives the project state and shows the spectator banner.

- [ ] **Step 20.3: Real-time deltas**

On Device A: paint a stroke. Verify it appears on Device B within ~500ms.

- [ ] **Step 20.4: Reconnect within 30s**

Toggle airplane mode on Device B. Wait 10s. Toggle back on. Verify session resumes; missed deltas are replayed.

- [ ] **Step 20.5: Reconnect timeout**

Toggle airplane mode on Device B for >30s. Verify session ends with appropriate banner.

- [ ] **Step 20.6: Long session**

On Device A: add 50+ layers, do a long stroke. Verify Device B stays in sync without lag or drift.

- [ ] **Step 20.7: Guest gating**

Verify Device B (guest) cannot see editing rail items (no `design.*` items, no per-layer items). Mode-switch and Leave still work.

- [ ] **Step 20.8: QR overlay disappears**

When guest connects, QR overlay on host should auto-dismiss to a small "Connected" indicator. When guest leaves, QR returns.

- [ ] **Step 20.9: Final tag commit**

```bash
git commit --allow-empty -m "chore: co-op real-time spectator implementation complete

All 20 tasks of docs/superpowers/plans/2026-04-30-coop-realtime-spectator.md
executed; spec docs/superpowers/specs/2026-04-30-coop-realtime-spectator-design.md
fully implemented."
```

---

## Definition of done

- [ ] All unit tests pass: FrameTest (7), OpTest (5), OpCodecTest (5), QrPayloadTest (8), DeltaBufferTest (6), NoOpOpEmitterTest (1).
- [ ] Both integration tests pass: LocalLoopTest, InterruptedBulkTest.
- [ ] All eight manual scenarios in Task 20 pass on two devices.
- [ ] `:collab` module no longer references NSD or Wi-Fi P2P APIs.
- [ ] No `try {} catch (Exception) { null }` patterns in `:collab` (`grep -nE 'catch \(.*Exception.*\)\s*\{\s*null' collab/src/main/.../*.kt` returns zero matches).
- [ ] Hosting + spectating works through a 5-minute exploratory session without unexpected state.
