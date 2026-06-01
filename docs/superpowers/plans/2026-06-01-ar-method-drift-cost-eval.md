# AR Method Drift & Cost Evaluation Harness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a harness that measures each of the 4 anti-drift mechanisms (ARCore VIO, voxel-reloc, surface-mesh, cloud-offset) for effectiveness (pose error vs mark-PnP truth, jitter, recovery, availability) and cost (native stage time, CPU, memory, battery), via a live dev telemetry overlay plus a deterministic ARCore record/playback bench, and emits a keep/drop recommendation per mechanism.

**Architecture:** A pure, unit-tested Kotlin metric core (`:feature:ar/eval`) consumes per-frame pose samples + native stage timings. Native C++ (`MobileGS`) gains zero-cost accumulating stage timers and per-stage enable flags exposed over JNI. A throttled collector in `ArRenderer` feeds the core and writes a session log; a dev-flag-gated overlay shows live metrics and drives ARCore record/playback for repeatable A/B. A decision generator aggregates a log into a ranked table. Accuracy is paramount: cost is reported alongside effectiveness, never as a divisor; a mechanism is dropped only if redundant.

**Tech Stack:** Kotlin, Android (Compose, ARCore Recording & Playback API, BatteryManager), C++17 (MobileGS native engine, JNI), JUnit.

**Spec:** `docs/superpowers/specs/2026-06-01-ar-method-drift-cost-eval-design.md`

**Reference reality (verified):**
- Native stage branch points: `MobileGS.cpp:126` (`mVoxelHash.update`, universal), `:137` (`addKeyframe`, VOXEL_HASH), `:140` (`mSurfaceMesh.update`, SURFACE_MESH), `:94-103` (`draw`). Relocalization runs on `mRelocThread` (`MobileGS.h:154`).
- JNI pattern: `GraffitiJNI.cpp:683-691` (`nativeSetArScanMode`/`nativeSetMuralMethod` → `gSlamEngine->...`).
- Kotlin binding pattern: `SlamManager.kt:200-201`; `external` decls near `SlamManager.kt:350-360`.
- Pose application seam: `ArRenderer.kt:391-396`; render throttle `ArRenderer.kt:399` (`frameCount % 4`).
- Existing diagnostic overlay: `MainActivity.kt:2363` (`DiagnosticRow`).

---

## File Structure

**Create:**
- `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetrics.kt` — pure metric math + data types. No Android deps.
- `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalSampleLog.kt` — sample row + CSV/JSON serialization (pure string building).
- `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/DriftCostProbe.kt` — stateful collector orchestrating the pure core + log + battery/cpu sampling.
- `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/ArRecordingController.kt` — ARCore record/playback wrapper.
- `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalDecision.kt` — aggregates a log into the ranked keep/drop table.
- `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetricsTest.kt`
- `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalSampleLogTest.kt`
- `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalDecisionTest.kt`

**Modify:**
- `core/nativebridge/src/main/cpp/include/MobileGS.h` — timing accumulators + stage-enable flags + method decls.
- `core/nativebridge/src/main/cpp/MobileGS.cpp` — wrap stages in timers; honor enable flags; getters.
- `core/nativebridge/src/main/cpp/GraffitiJNI.cpp` — `nativeGetStageTimings`, `nativeSetStageEnabled`.
- `core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt` — Kotlin bindings.
- `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt` — wire probe (throttled).
- `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt` — dev-flag-gated eval overlay.

**Stage index contract (used everywhere):** `0=voxelUpdate, 1=voxelKeyframe, 2=surfaceMesh, 3=draw, 4=pnpReloc`. Mechanism mapping: M1 ARCore VIO (no native stage; measured by pose only), M2 voxel-reloc = stages 0,1,4; M3 surface-mesh = stage 2; M4 cloud-offset = stage 0 only (shares voxel map).

---

## Phase 1 — Pure metric core (TDD, no Android deps)

### Task 1: Mechanism + pose-error types

**Files:**
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetrics.kt`
- Test: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetricsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalMetricsTest {
    // Build matrices as plain FloatArrays so the tests stay pure-JVM (no android.opengl.Matrix,
    // which is a stubbed "not mocked" class outside Robolectric). Column-major identity.
    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun `poseError is zero for identical poses`() {
        val e = EvalMetrics.poseError(identity(), identity())
        assertEquals(0f, e.translationMm, 1e-3f)
        assertEquals(0f, e.rotationDeg, 1e-3f)
    }

    @Test
    fun `poseError reports translation in millimeters`() {
        val truth = identity()
        val candidate = identity().also { it[12] = 0.10f } // +0.10 m on X
        val e = EvalMetrics.poseError(candidate, truth)
        assertEquals(100f, e.translationMm, 1e-2f) // 0.10 m = 100 mm
        assertEquals(0f, e.rotationDeg, 1e-3f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*EvalMetricsTest"`
Expected: FAIL — `EvalMetrics` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/** The four anti-drift mechanisms under evaluation. Mark-PnP is the reference of truth, not here. */
enum class MechanismId { ARCORE_VIO, VOXEL_RELOC, SURFACE_MESH, CLOUD_OFFSET }

/** Difference between a candidate pose and the mark-PnP truth pose. */
data class PoseError(val translationMm: Float, val rotationDeg: Float)

/** Pure metric math — no Android framework state, fully unit-testable. */
object EvalMetrics {

    /** Both args are column-major 4x4 matrices (OpenGL/ARCore layout). */
    fun poseError(candidate: FloatArray, truth: FloatArray): PoseError {
        val dx = candidate[12] - truth[12]
        val dy = candidate[13] - truth[13]
        val dz = candidate[14] - truth[14]
        val translationMm = sqrt(dx * dx + dy * dy + dz * dz) * 1000f

        // Relative rotation angle from the trace of R = Rc * Rt^T (rotation part only).
        val rc = rotationOnly(candidate)
        val rt = rotationOnly(truth)
        // trace(Rc * Rt^T)
        var trace = 0f
        for (i in 0 until 3) {
            for (k in 0 until 3) {
                trace += rc[i * 3 + k] * rt[i * 3 + k] // since Rt^T row = Rt col
            }
        }
        val cosTheta = ((trace - 1f) / 2f).coerceIn(-1f, 1f)
        val rotationDeg = Math.toDegrees(acos(cosTheta).toDouble()).toFloat()
        return PoseError(translationMm, if (rotationDeg.isNaN()) 0f else rotationDeg)
    }

    // Extract the upper-left 3x3 rotation, row-major, normalizing out scale.
    private fun rotationOnly(m: FloatArray): FloatArray {
        fun colLen(c: Int) = sqrt(m[c*4]*m[c*4] + m[c*4+1]*m[c*4+1] + m[c*4+2]*m[c*4+2]).let { if (it == 0f) 1f else it }
        val s0 = colLen(0); val s1 = colLen(1); val s2 = colLen(2)
        return floatArrayOf(
            m[0]/s0, m[4]/s1, m[8]/s2,
            m[1]/s0, m[5]/s1, m[9]/s2,
            m[2]/s0, m[6]/s1, m[10]/s2,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*EvalMetricsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetrics.kt \
        feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetricsTest.kt
git commit -m "feat(ar-eval): pose-error metric core with tests"
```

### Task 2: jitter, availability, recoveryMs

**Files:**
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetrics.kt`
- Test: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetricsTest.kt`

- [ ] **Step 1: Write the failing tests** (append to `EvalMetricsTest`)

```kotlin
    @Test
    fun `jitter is zero for a stationary point`() {
        val pts = List(10) { floatArrayOf(1f, 2f, 3f) }
        assertEquals(0f, EvalMetrics.jitterMm(pts), 1e-3f)
    }

    @Test
    fun `jitter is stddev of distance from centroid in mm`() {
        // Two points: (0,0,0) and (0,0,0.02) -> centroid z=0.01, each 0.01 m = 10 mm away. stddev = 10mm.
        val pts = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0.02f))
        assertEquals(10f, EvalMetrics.jitterMm(pts), 1e-2f)
    }

    @Test
    fun `availability is usable over total`() {
        assertEquals(0.75f, EvalMetrics.availability(usable = 3, total = 4), 1e-4f)
        assertEquals(0f, EvalMetrics.availability(usable = 0, total = 0), 1e-4f) // guard /0
    }

    @Test
    fun `recoveryMs is relock minus loss, null if never relocked`() {
        assertEquals(1500L, EvalMetrics.recoveryMs(lossMs = 1000L, relockMs = 2500L))
        assertEquals(null, EvalMetrics.recoveryMs(lossMs = 1000L, relockMs = null))
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*EvalMetricsTest"`
Expected: FAIL — `jitterMm`/`availability`/`recoveryMs` unresolved.

- [ ] **Step 3: Implement** (append inside `object EvalMetrics`)

```kotlin
    /** Stddev (population) of each point's distance from the centroid, in millimeters. */
    fun jitterMm(translations: List<FloatArray>): Float {
        if (translations.size < 2) return 0f
        val n = translations.size
        val cx = translations.sumOf { it[0].toDouble() } / n
        val cy = translations.sumOf { it[1].toDouble() } / n
        val cz = translations.sumOf { it[2].toDouble() } / n
        val dists = translations.map {
            val dx = it[0] - cx; val dy = it[1] - cy; val dz = it[2] - cz
            sqrt(dx * dx + dy * dy + dz * dz)
        }
        val mean = dists.average()
        val variance = dists.sumOf { (it - mean) * (it - mean) } / n
        return (sqrt(variance) * 1000.0).toFloat()
    }

    fun availability(usable: Int, total: Int): Float = if (total <= 0) 0f else usable.toFloat() / total

    /** Milliseconds from an induced tracking loss to the first re-lock; null if never relocked. */
    fun recoveryMs(lossMs: Long, relockMs: Long?): Long? = relockMs?.let { it - lossMs }
```

- [ ] **Step 4: Run to verify it passes**

Run: `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*EvalMetricsTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetrics.kt \
        feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalMetricsTest.kt
git commit -m "feat(ar-eval): jitter, availability, recovery metrics with tests"
```

### Task 3: Sample row + CSV serialization

**Files:**
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalSampleLog.kt`
- Test: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalSampleLogTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalSampleLogTest {
    @Test
    fun `header lists all columns in order`() {
        assertEquals(
            "tsMs,deviceClass,marksVisible,errMm,errDeg,jitterMm,availability," +
                "voxelUpdateMs,voxelKeyframeMs,surfaceMeshMs,drawMs,pnpRelocMs,cpuPct,batteryMa,tempC,nativeHeapKb",
            EvalSampleLog.CSV_HEADER
        )
    }

    @Test
    fun `row serializes fields in header order`() {
        val row = EvalSample(
            tsMs = 12L, deviceClass = "dual", marksVisible = true,
            errMm = 1.5f, errDeg = 0.25f, jitterMm = 3f, availability = 1f,
            stageMs = floatArrayOf(2f, 0f, 4f, 1f, 8f), cpuPct = 30f, batteryMa = -450f, tempC = 31f,
            nativeHeapKb = 20480L,
        )
        assertEquals(
            "12,dual,true,1.5,0.25,3.0,1.0,2.0,0.0,4.0,1.0,8.0,30.0,-450.0,31.0,20480",
            EvalSampleLog.toCsvRow(row)
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*EvalSampleLogTest"`
Expected: FAIL — `EvalSample`/`EvalSampleLog` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval

/** One throttled measurement tick. stageMs is indexed by the stage contract: 0=voxelUpdate,
 *  1=voxelKeyframe, 2=surfaceMesh, 3=draw, 4=pnpReloc. */
data class EvalSample(
    val tsMs: Long,
    val deviceClass: String,   // "dual" or "mono"
    val marksVisible: Boolean,
    val errMm: Float,          // pose error vs mark-PnP truth; -1 when marks not visible
    val errDeg: Float,
    val jitterMm: Float,
    val availability: Float,
    val stageMs: FloatArray,   // length 5
    val cpuPct: Float,
    val batteryMa: Float,      // BatteryManager CURRENT_NOW (µA→mA); negative = discharging
    val tempC: Float,
    val nativeHeapKb: Long,    // Debug.getNativeHeapAllocatedSize()/1024 — memory cost proxy
)

object EvalSampleLog {
    const val CSV_HEADER =
        "tsMs,deviceClass,marksVisible,errMm,errDeg,jitterMm,availability," +
            "voxelUpdateMs,voxelKeyframeMs,surfaceMeshMs,drawMs,pnpRelocMs,cpuPct,batteryMa,tempC,nativeHeapKb"

    fun toCsvRow(s: EvalSample): String {
        val st = FloatArray(5).also { System.arraycopy(s.stageMs, 0, it, 0, minOf(5, s.stageMs.size)) }
        return listOf(
            s.tsMs.toString(), s.deviceClass, s.marksVisible.toString(),
            s.errMm.toString(), s.errDeg.toString(), s.jitterMm.toString(), s.availability.toString(),
            st[0].toString(), st[1].toString(), st[2].toString(), st[3].toString(), st[4].toString(),
            s.cpuPct.toString(), s.batteryMa.toString(), s.tempC.toString(), s.nativeHeapKb.toString(),
        ).joinToString(",")
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*EvalSampleLogTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalSampleLog.kt \
        feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalSampleLogTest.kt
git commit -m "feat(ar-eval): eval sample model + CSV serialization with tests"
```

---

## Phase 2 — Native stage timing + JNI

### Task 4: Native timing accumulators + enable flags

**Files:**
- Modify: `core/nativebridge/src/main/cpp/include/MobileGS.h` (after line 152, in the private section before `mRelocThread`)
- Modify: `core/nativebridge/src/main/cpp/MobileGS.cpp` (stage sites + new getters)

- [ ] **Step 1: Add fields + method decls to `MobileGS.h`**

In the private member block (after `int mMuralMethod = 0;`, before `std::thread mRelocThread;`):

```cpp
    // --- Evaluation instrumentation (Sub-project A) ---
    // Accumulated wall-time per stage and a sample count, for averaging. Indexes match the Kotlin
    // stage contract: 0=voxelUpdate,1=voxelKeyframe,2=surfaceMesh,3=draw,4=pnpReloc.
    static constexpr int kStageCount = 5;
    std::atomic<double> mStageAccumMs[kStageCount] = {};
    std::atomic<uint64_t> mStageSamples[kStageCount] = {};
    // Per-stage A/B enable flags (default on). When off, the stage's work is skipped so cost diffs
    // are clean. Stage 0 (voxelUpdate) is the relocalization backbone and is NOT gateable.
    std::atomic<bool> mStageEnabled[kStageCount] = { true, true, true, true, true };
```

In the public section (near other public methods, e.g. after `setMuralMethod`):

```cpp
    // Eval: fill out[kStageCount] with average ms/stage since last reset, then reset accumulators.
    void getStageTimingsAndReset(float* out);
    void setStageEnabled(int stage, bool enabled);
```

Add `#include <atomic>` and `#include <chrono>` to the header includes if not already present.

- [ ] **Step 2: Add a timing helper + getters to `MobileGS.cpp`** (top of file, after includes)

```cpp
namespace {
struct StageTimer {
    std::atomic<double>* accum;
    std::atomic<uint64_t>* count;
    std::chrono::steady_clock::time_point start;
    StageTimer(std::atomic<double>* a, std::atomic<uint64_t>* c)
        : accum(a), count(c), start(std::chrono::steady_clock::now()) {}
    ~StageTimer() {
        double ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count();
        accum->fetch_add(ms, std::memory_order_relaxed);
        count->fetch_add(1, std::memory_order_relaxed);
    }
};
}
```

Append the getter/setter implementations at the end of `MobileGS.cpp`:

```cpp
void MobileGS::getStageTimingsAndReset(float* out) {
    for (int i = 0; i < kStageCount; ++i) {
        uint64_t n = mStageSamples[i].exchange(0, std::memory_order_relaxed);
        double acc = mStageAccumMs[i].exchange(0.0, std::memory_order_relaxed);
        out[i] = (n > 0) ? static_cast<float>(acc / static_cast<double>(n)) : 0.0f;
    }
}

void MobileGS::setStageEnabled(int stage, bool enabled) {
    if (stage > 0 && stage < kStageCount) mStageEnabled[stage].store(enabled, std::memory_order_relaxed);
}
```

- [ ] **Step 3: Wrap the stage call sites in `processDepthFrame` and `draw`**

In `MobileGS.cpp:126`, wrap the universal voxel update:

```cpp
    // Universal Ingestion: Build the Voxel Map in ALL modes to enable Snap-Back relocalization.
    { StageTimer _t(&mStageAccumMs[0], &mStageSamples[0]);
      mVoxelHash.update(depth, colorRGB, viewMat, projMat, mVoxelSize, confidence); }
```

In the VOXEL_HASH keyframe branch (`:131-138`), gate + time:

```cpp
    if (mMuralMethod == 0) { // VOXEL_HASH
        if (mStageEnabled[1].load(std::memory_order_relaxed) && mFrameCounter % 30 == 0) {
            StageTimer _t(&mStageAccumMs[1], &mStageSamples[1]);
            VoxelFrame kf;
            kf.depth = depth.clone(); kf.color = colorRGB.clone();
            memcpy(kf.viewMatrix, viewMat, 16 * sizeof(float));
            memcpy(kf.projMatrix, projMat, 16 * sizeof(float));
            mVoxelHash.addKeyframe(kf);
        }
    } else if (mMuralMethod == 1) { // SURFACE_MESH
        if (mStageEnabled[2].load(std::memory_order_relaxed)) {
            StageTimer _t(&mStageAccumMs[2], &mStageSamples[2]);
            mSurfaceMesh.update(depth, colorRGB, viewMat, projMat, mAnchorMatrix, mLightLevel);
        }
    } else if (mMuralMethod == 2) { // CLOUD_OFFSET
        // Cloud Offset mode leverages the mVoxelHash map updated above.
    }
```

In `draw()` (`:94-103`), wrap the splat/mesh draw block:

```cpp
    if (mSplatsVisible) {
        StageTimer _t(&mStageAccumMs[3], &mStageSamples[3]);
        if (mMuralMethod == 0) {
            mVoxelHash.draw(mvp, V, std::abs(mProjMatrix[5]) * (mScreenHeight / 2.0f), mScreenHeight);
        } else if (mMuralMethod == 1) {
            mSurfaceMesh.draw(mvp);
        } else if (mMuralMethod == 2) {
            mVoxelHash.draw(mvp, V, std::abs(mProjMatrix[5]) * (mScreenHeight / 2.0f), mScreenHeight);
        }
    }
```

For stage 4 (pnpReloc): locate the relocalization/PnP call inside the reloc thread body (search `runPnP`
or the matcher invocation in `mapThreadFunc`/reloc handler). Wrap that single call:
`{ StageTimer _t(&mStageAccumMs[4], &mStageSamples[4]); /* existing PnP/match call */ }`.
Do not gate stage 4 (recovery must always run); timing only.

- [ ] **Step 4: Build to verify it compiles**

Run: `sh gradlew --offline :core:nativebridge:assembleDebug`
Expected: BUILD SUCCESSFUL. (No unit test — native timing is verified via the Kotlin smoke test in Task 6 and the overlay.)

- [ ] **Step 5: Commit**

```bash
git add core/nativebridge/src/main/cpp/include/MobileGS.h core/nativebridge/src/main/cpp/MobileGS.cpp
git commit -m "feat(ar-eval): native per-stage timers + A/B enable flags"
```

### Task 5: JNI entry points

**Files:**
- Modify: `core/nativebridge/src/main/cpp/GraffitiJNI.cpp` (after `nativeSetMuralMethod`, ~line 691)

- [ ] **Step 1: Add JNI functions**

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetStageTimings(JNIEnv* env, jobject, jfloatArray out) {
    if (!gSlamEngine) return;
    float buf[5] = {0,0,0,0,0};
    gSlamEngine->getStageTimingsAndReset(buf);
    env->SetFloatArrayRegion(out, 0, 5, buf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetStageEnabled(JNIEnv* env, jobject, jint stage, jboolean enabled) {
    if (gSlamEngine) gSlamEngine->setStageEnabled((int) stage, enabled == JNI_TRUE);
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `sh gradlew --offline :core:nativebridge:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/nativebridge/src/main/cpp/GraffitiJNI.cpp
git commit -m "feat(ar-eval): JNI bindings for stage timings + enable flags"
```

### Task 6: Kotlin SlamManager bindings + smoke test

**Files:**
- Modify: `core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt` (public methods near :201; `external` decls near :350)

- [ ] **Step 1: Add public methods** (after `setMuralMethod`, line 201)

```kotlin
    /** Eval (Sub-project A): average ms/stage since last call, then resets native accumulators.
     *  Indexes: 0=voxelUpdate,1=voxelKeyframe,2=surfaceMesh,3=draw,4=pnpReloc. */
    fun getStageTimings(): FloatArray {
        val out = FloatArray(5)
        nativeGetStageTimings(out)
        return out
    }

    /** Eval: toggle a native stage for A/B cost attribution. Stage 0 is non-gateable (reloc backbone). */
    fun setStageEnabled(stage: Int, enabled: Boolean) = nativeSetStageEnabled(stage, enabled)
```

- [ ] **Step 2: Add `external` declarations** (with the other `private external fun` lines near :350)

```kotlin
    private external fun nativeGetStageTimings(out: FloatArray)
    private external fun nativeSetStageEnabled(stage: Int, enabled: Boolean)
```

- [ ] **Step 3: Build to verify it compiles**

Run: `sh gradlew --offline :core:nativebridge:assembleDebug`
Expected: BUILD SUCCESSFUL. (Behavioral verification of native timing happens on-device via the overlay in Task 9; JVM unit tests can't load the `.so`.)

- [ ] **Step 4: Commit**

```bash
git add core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt
git commit -m "feat(ar-eval): SlamManager bindings for stage timings + enable flags"
```

---

## Phase 3 — Collector wiring

### Task 7: DriftCostProbe collector

**Files:**
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/DriftCostProbe.kt`

- [ ] **Step 1: Implement the collector** (no new unit test — it orchestrates already-tested pure functions; correctness of those is covered by Tasks 1–3; the file I/O + sampling is verified on-device)

```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval

import android.content.Context
import android.os.BatteryManager
import java.io.File

/**
 * Stateful per-session collector. Call [onTick] from the GL loop (throttled). It computes metrics
 * from the supplied poses using [EvalMetrics], samples battery/temp, and appends CSV rows to a log
 * file in the app files dir. Enabled only in dev/eval mode — must be cheap and gated by the caller.
 */
class DriftCostProbe(
    private val context: Context,
    private val deviceClass: String, // "dual" or "mono"
    private val nowMs: () -> Long,
) {
    private val recentTranslations = ArrayDeque<FloatArray>() // for jitter, last N stationary samples
    private val jitterWindow = 30
    private var usableFrames = 0
    private var totalFrames = 0
    private var lossMs: Long? = null
    private var relockMs: Long? = null
    private var logFile: File? = null

    private val batteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    fun start(): File {
        val dir = File(context.filesDir, "eval").apply { mkdirs() }
        val f = File(dir, "eval_${deviceClass}_${nowMs()}.csv")
        f.writeText(EvalSampleLog.CSV_HEADER + "\n")
        logFile = f
        usableFrames = 0; totalFrames = 0; lossMs = null; relockMs = null
        recentTranslations.clear()
        return f
    }

    fun stop() { logFile = null }

    /** Marks an induced tracking loss (overlay button) so the next re-lock yields recovery time. */
    fun markTrackingLoss() { lossMs = nowMs(); relockMs = null }

    /**
     * @param candidatePose 4x4 column-major pose of the mechanism under test (or the active fused pose)
     * @param truthPose     4x4 mark-PnP pose, or null when marks are not visible
     * @param isTracking    whether the mechanism currently has a usable lock (for recovery/availability)
     * @param stageMs        native stage timings from SlamManager.getStageTimings()
     * @param cpuPct         caller-sampled CPU percent (or -1 if unavailable)
     */
    fun onTick(
        candidatePose: FloatArray,
        truthPose: FloatArray?,
        isTracking: Boolean,
        stageMs: FloatArray,
        cpuPct: Float,
    ) {
        val file = logFile ?: return
        totalFrames++
        if (isTracking) usableFrames++

        // Recovery: first re-lock after an induced loss.
        if (lossMs != null && relockMs == null && isTracking) relockMs = nowMs()

        // Jitter window (translation column of the candidate pose).
        val t = floatArrayOf(candidatePose[12], candidatePose[13], candidatePose[14])
        recentTranslations.addLast(t)
        while (recentTranslations.size > jitterWindow) recentTranslations.removeFirst()

        val marksVisible = truthPose != null
        val err = if (truthPose != null) EvalMetrics.poseError(candidatePose, truthPose) else null

        val sample = EvalSample(
            tsMs = nowMs(),
            deviceClass = deviceClass,
            marksVisible = marksVisible,
            errMm = err?.translationMm ?: -1f,
            errDeg = err?.rotationDeg ?: -1f,
            jitterMm = EvalMetrics.jitterMm(recentTranslations.toList()),
            availability = EvalMetrics.availability(usableFrames, totalFrames),
            stageMs = stageMs,
            cpuPct = cpuPct,
            batteryMa = sampleBatteryMa(),
            tempC = -1f, // wired from caller's thermal sample if available; -1 = not sampled
            nativeHeapKb = android.os.Debug.getNativeHeapAllocatedSize() / 1024L,
        )
        file.appendText(EvalSampleLog.toCsvRow(sample) + "\n")
    }

    private fun sampleBatteryMa(): Float {
        // CURRENT_NOW is µA on most devices; convert to mA. Sign convention is device-dependent.
        val micro = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (micro == Int.MIN_VALUE) 0f else micro / 1000f
    }

    fun recoveryMs(): Long? = lossMs?.let { EvalMetrics.recoveryMs(it, relockMs) }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `sh gradlew --offline :feature:ar:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/DriftCostProbe.kt
git commit -m "feat(ar-eval): DriftCostProbe session collector"
```

### Task 8: Wire the probe into ArRenderer (throttled, dev-flag-gated)

**Files:**
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt`

- [ ] **Step 1: Add a nullable probe field + setter** (near the other volatile fields, ~`ArRenderer.kt:87`)

```kotlin
    // Eval (Sub-project A): null unless dev/eval mode is on. Set from ArViewModel.
    @Volatile var driftCostProbe: com.hereliesaz.graffitixr.feature.ar.eval.DriftCostProbe? = null
    // Mark-PnP truth pose for the current frame, or null when marks aren't visible. Provided by the
    // native PnP path; expose via the existing anchor transform when a confident match exists.
    private val truthPoseScratch = FloatArray(16)
```

- [ ] **Step 2: Feed the probe inside the existing 15 Hz throttle** (in `onDrawFrame`, inside the `if (frameCount % 4 == 0)` block at `ArRenderer.kt:399`, after `anchorMatrix` is computed)

```kotlin
                driftCostProbe?.let { probe ->
                    val stageMs = slamManager.getStageTimings()
                    // Truth = mark-PnP pose when a confident match exists; getVisibleConfidenceAvg()
                    // gates "marks visible". Reuse the native anchor transform as the PnP-refined pose.
                    val marksVisible = slamManager.getVisibleConfidenceAvg() > MARK_VISIBLE_CONF
                    val truth = if (marksVisible) {
                        System.arraycopy(slamManager.getAnchorTransform(), 0, truthPoseScratch, 0, 16)
                        truthPoseScratch
                    } else null
                    probe.onTick(
                        candidatePose = anchorMatrix,
                        truthPose = truth,
                        isTracking = anchorEstablished,
                        stageMs = stageMs,
                        cpuPct = -1f, // CPU% sampled by overlay; -1 here keeps the GL thread cheap
                    )
                }
```

Add the constant near the top of the class:

```kotlin
    private val MARK_VISIBLE_CONF = 0.5f // visible-confidence threshold to treat mark-PnP as truth
```

- [ ] **Step 2b: Note on truth vs candidate**

For the **per-mechanism** A/B runs, the candidate pose is whichever mechanism is isolated via
`setStageEnabled` (others off). For the **live** overlay run, `candidatePose` is the active fused/anchor
pose. This task wires the live path; the bench (Task 9) drives the per-mechanism A/B by toggling stages
between playback runs and tagging each run's log file name with the enabled mechanism.

- [ ] **Step 3: Build to verify it compiles**

Run: `sh gradlew --offline :feature:ar:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt
git commit -m "feat(ar-eval): feed DriftCostProbe from the ArRenderer 15Hz loop"
```

---

## Phase 4 — Bench (ARCore record/playback) + depth validation

### Task 9: ArRecordingController + depth-replay validation

**Files:**
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/ArRecordingController.kt`

- [ ] **Step 1: Implement the controller**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval

import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import timber.log.Timber
import java.io.File

/**
 * Wraps ARCore's Recording & Playback API so the bench can replay one captured wall session
 * deterministically against each mechanism config. Recording must be configured BEFORE the session
 * is resumed; playback dataset must be set while the session is paused.
 */
class ArRecordingController(private val context: android.content.Context) {

    fun recordingsDir(): File = File(context.filesDir, "eval/recordings").apply { mkdirs() }

    /** Start recording the live session to an MP4 dataset. Returns the target file. */
    fun startRecording(session: Session, name: String): File {
        val file = File(recordingsDir(), "$name.mp4")
        val config = RecordingConfig(session).setMp4DatasetFilePath(file.absolutePath)
        session.startRecording(config)
        Timber.i("eval: recording -> ${file.absolutePath}")
        return file
    }

    fun stopRecording(session: Session) {
        if (session.recordingStatus == RecordingStatus.OK) session.stopRecording()
    }

    /** Set a recorded dataset for playback. Session must be paused; caller resumes after. */
    fun startPlayback(session: Session, file: File) {
        session.setPlaybackDatasetUri(android.net.Uri.fromFile(file))
    }

    fun isPlaying(session: Session): Boolean = session.playbackStatus == PlaybackStatus.OK

    /**
     * DEPTH-REPLAY VALIDATION (spec risk): during playback, attempt to acquire a depth image and
     * report whether depth is present. If false, M2–M4 cost/effectiveness can only come from the
     * live telemetry path, not the bench. Call once after playback starts and a few frames elapse.
     */
    fun playbackHasDepth(session: Session): Boolean = try {
        session.update().acquireDepthImage16Bits().use { it.width > 0 }
    } catch (e: Exception) {
        Timber.w(e, "eval: playback depth unavailable")
        false
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `sh gradlew --offline :feature:ar:assembleDebug`
Expected: BUILD SUCCESSFUL. (If `setPlaybackDatasetUri`/`RecordingConfig` signatures differ in the
pinned ARCore version, adjust to the version's API — confirm against `libs.versions.toml` ARCore.)

- [ ] **Step 3: Manual validation (the spec's primary risk)**

On a dual-lens device: record a 30 s wall session via the overlay (Task 10), then replay it and call
`playbackHasDepth`. **Document the result** in the eval log dir as `DEPTH_REPLAY=<true|false>`. If
false, mark M2–M4 bench columns "telemetry-only" in the decision artifact.

- [ ] **Step 4: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/ArRecordingController.kt
git commit -m "feat(ar-eval): ARCore record/playback controller + depth-replay probe"
```

---

## Phase 5 — Dev overlay

### Task 10: Eval diagnostics overlay (dev-flag gated)

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt` (near the existing diagnostic overlay, ~`MainActivity.kt:2363`)

- [ ] **Step 1: Add a dev flag** (top-level `const` or `BuildConfig` check; reuse `BuildConfig.DEBUG`)

```kotlin
// Eval overlay only renders in debug builds; production users never see it.
private val EVAL_OVERLAY_ENABLED = BuildConfig.DEBUG
```

- [ ] **Step 1b: Define `EvalLiveMetrics` in `:core:common` and add it to `ArUiState`**

`EvalLiveMetrics` is read by `ArUiState` (`:core:common`), so it must live there — a UI-module type
can't be referenced upstream. In `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt`:

```kotlin
/** Live eval metrics snapshot rendered by the dev overlay (Sub-project A). */
data class EvalLiveMetrics(
    val errMm: Float = -1f,
    val errDeg: Float = -1f,
    val jitterMm: Float = 0f,
    val availability: Float = 0f,
    val recoveryMs: Long? = null,
    val stageMs: FloatArray = FloatArray(5),
    val batteryMa: Float = 0f,
)
```

Add to `ArUiState`: `val evalLiveMetrics: EvalLiveMetrics = EvalLiveMetrics(),`

- [ ] **Step 2: Add a Composable overlay** (place beside the existing `DiagnosticRow` usage; imports `EvalLiveMetrics` from `:core:common`)

```kotlin
@Composable
private fun EvalOverlay(
    metrics: EvalLiveMetrics,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onStartLog: () -> Unit,
    onStopLog: () -> Unit,
    onInduceLoss: () -> Unit,
) {
    Column(Modifier.background(Color(0xAA000000)).padding(8.dp)) {
        DiagnosticRow("Err", if (metrics.errMm >= 0) "%.0fmm / %.1f°".format(metrics.errMm, metrics.errDeg) else "no marks", Color.Cyan)
        DiagnosticRow("Jitter", "%.1fmm".format(metrics.jitterMm), Color.White)
        DiagnosticRow("Avail", "%.0f%%".format(metrics.availability * 100), Color.White)
        DiagnosticRow("Recovery", metrics.recoveryMs?.let { "${it}ms" } ?: "—", Color.White)
        DiagnosticRow("Stage ms", metrics.stageMs.joinToString(" ") { "%.1f".format(it) }, Color.Yellow)
        DiagnosticRow("Batt", "%.0fmA".format(metrics.batteryMa), Color.White)
        Row {
            TextButton(onClick = onStartLog) { Text("Log▶") }
            TextButton(onClick = onStopLog) { Text("Log■") }
            TextButton(onClick = onInduceLoss) { Text("Loss") }
            TextButton(onClick = onStartRecord) { Text("Rec▶") }
            TextButton(onClick = onStopRecord) { Text("Rec■") }
        }
    }
}
```

(`EvalLiveMetrics` is the `:core:common` type from Step 1b — do **not** redefine it here.)

- [ ] **Step 3: Render it gated** (where the existing diagnostic overlay is conditionally shown)

```kotlin
if (EVAL_OVERLAY_ENABLED && editorUiState.editorMode == EditorMode.AR && !showLibrary && !showSettings) {
    EvalOverlay(
        metrics = arUiState.evalLiveMetrics,
        onStartRecord = { arViewModel.evalStartRecording() },
        onStopRecord = { arViewModel.evalStopRecording() },
        onStartLog = { arViewModel.evalStartLog() },
        onStopLog = { arViewModel.evalStopLog() },
        onInduceLoss = { arViewModel.evalInduceLoss() },
    )
}
```

- [ ] **Step 4: Add the ViewModel plumbing** in `ArViewModel.kt`

Add fields (near the other private fields) and the five eval methods. `appContext` and `slamManager`
are already injected (`ArViewModel.kt:64,71`); `renderer` is the `ArRenderer` reference held by the VM.

```kotlin
    // Eval (Sub-project A) — dev-only.
    private val evalProbe by lazy {
        com.hereliesaz.graffitixr.feature.ar.eval.DriftCostProbe(
            context = appContext,
            deviceClass = if (_uiState.value.isHardwareStereoActive) "dual" else "mono",
            nowMs = { android.os.SystemClock.elapsedRealtime() },
        )
    }
    private val evalRecorder by lazy {
        com.hereliesaz.graffitixr.feature.ar.eval.ArRecordingController(appContext)
    }

    fun evalStartLog() {
        evalProbe.start()
        renderer?.driftCostProbe = evalProbe
    }

    fun evalStopLog() {
        renderer?.driftCostProbe = null
        evalProbe.stop()
    }

    /** Simulate a tracking loss: mark it for recovery timing and briefly pause mapping. */
    fun evalInduceLoss() {
        evalProbe.markTrackingLoss()
        slamManager.setMappingPaused(true)
        viewModelScope.launch { delay(500); slamManager.setMappingPaused(false) }
    }

    fun evalStartRecording() {
        session?.let { evalRecorder.startRecording(it, "eval_${android.os.SystemClock.elapsedRealtime()}") }
    }

    fun evalStopRecording() {
        session?.let { evalRecorder.stopRecording(it) }
    }
```

> `evalLiveMetrics` in `ArUiState` is updated from the probe inside the same throttled block where the
> probe ticks. Since the probe writes the CSV but doesn't itself expose a live snapshot, either (a)
> read back the last computed values via small getters on `DriftCostProbe` (add
> `var lastMetrics: EvalLiveMetrics` updated at the end of `onTick`), or (b) recompute the snapshot in
> the VM. Use (a): add a public `@Volatile var lastMetrics = EvalLiveMetrics()` to `DriftCostProbe`,
> set it at the end of `onTick`, and have the VM publish it: `_uiState.update { it.copy(evalLiveMetrics = evalProbe.lastMetrics) }`
> on the existing 15 Hz UI update. (Add `import com.hereliesaz.graffitixr.common.model.EvalLiveMetrics`
> to `DriftCostProbe.kt`.)

- [ ] **Step 5: Build + install + manual smoke**

Run: `sh gradlew --offline :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Install on a device; in AR mode confirm the overlay shows live numbers,
"Loss" produces a recovery time, and "Log■" leaves a CSV in `files/eval/` (pull with
`adb exec-out run-as <pkg> cat files/eval/<name>.csv`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt \
        feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt \
        core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt
git commit -m "feat(ar-eval): dev-gated eval overlay + ViewModel plumbing"
```

---

## Phase 6 — Decision artifact

### Task 11: Decision aggregator (TDD)

**Files:**
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalDecision.kt`
- Test: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalDecisionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalDecisionTest {
    @Test
    fun `aggregate averages error and max stage time per mechanism`() {
        val rows = listOf(
            MechanismRun(MechanismId.SURFACE_MESH, errMm = 2f, jitterMm = 1f, availability = 1f, recoveryMs = 100, stageMs = 4f, uniqueCoverage = true),
            MechanismRun(MechanismId.CLOUD_OFFSET, errMm = 50f, jitterMm = 20f, availability = 1f, recoveryMs = null, stageMs = 0.1f, uniqueCoverage = false),
        )
        val report = EvalDecision.decide(rows)
        // Accuracy-first: redundant + no unique coverage -> drop, regardless of low cost.
        assertEquals(Verdict.DROP, report.first { it.id == MechanismId.CLOUD_OFFSET }.verdict)
        // Unique coverage -> keep, even though it's the costliest.
        assertEquals(Verdict.KEEP, report.first { it.id == MechanismId.SURFACE_MESH }.verdict)
    }

    @Test
    fun `cost never flips an accurate uniquely-covering mechanism to drop`() {
        val rows = listOf(
            MechanismRun(MechanismId.VOXEL_RELOC, errMm = 1f, jitterMm = 0.5f, availability = 1f, recoveryMs = 80, stageMs = 999f, uniqueCoverage = true),
        )
        assertEquals(Verdict.KEEP, EvalDecision.decide(rows).single().verdict)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*EvalDecisionTest"`
Expected: FAIL — `EvalDecision`/`MechanismRun`/`Verdict` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval

enum class Verdict { KEEP, DROP }

/** Aggregated metrics for one mechanism across a session/playback run. */
data class MechanismRun(
    val id: MechanismId,
    val errMm: Float,
    val jitterMm: Float,
    val availability: Float,
    val recoveryMs: Long?,
    val stageMs: Float,
    val uniqueCoverage: Boolean, // does it cover a failure mode no kept mechanism does?
)

data class MechanismVerdict(val id: MechanismId, val verdict: Verdict, val rationale: String)

/**
 * Accuracy-paramount rubric (spec "Guiding principle"): a mechanism is KEPT if it is effective OR
 * provides unique failure-mode coverage. It is DROPPED only when it is BOTH redundant (no unique
 * coverage) AND not meaningfully more accurate than the survivors. Cost is reported but never flips
 * an accurate or uniquely-covering mechanism to DROP.
 */
object EvalDecision {
    private const val GOOD_ERR_MM = 10f // <=1 cm error is "effective" at mural scale

    fun decide(runs: List<MechanismRun>): List<MechanismVerdict> = runs.map { r ->
        val effective = r.errMm in 0f..GOOD_ERR_MM && r.availability > 0f
        val keep = r.uniqueCoverage || effective
        MechanismVerdict(
            id = r.id,
            verdict = if (keep) Verdict.KEEP else Verdict.DROP,
            rationale = buildString {
                append("err=${r.errMm}mm jitter=${r.jitterMm}mm avail=${r.availability} ")
                append("recovery=${r.recoveryMs ?: "n/a"}ms cost=${r.stageMs}ms ")
                append(if (r.uniqueCoverage) "uniqueCoverage " else "redundant ")
                append(if (keep) "-> KEEP" else "-> DROP (redundant & not more accurate)")
            },
        )
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*EvalDecisionTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalDecision.kt \
        feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/eval/EvalDecisionTest.kt
git commit -m "feat(ar-eval): accuracy-first keep/drop decision aggregator with tests"
```

---

## Final verification

- [ ] Run the full eval unit suite: `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*Eval*"` — Expected: all PASS.
- [ ] Build everything touched: `sh gradlew --offline :core:nativebridge:assembleDebug :feature:ar:assembleDebug :app:assembleDebug` — Expected: BUILD SUCCESSFUL.
- [ ] Confirm no new failures beyond the 3 known pre-existing `ArViewModelTest` cases.
- [ ] On-device: record→replay a session on a dual-lens device; record the `DEPTH_REPLAY` result (Task 9 Step 3). On a mono device, confirm M2–M4 report 0% availability rather than feeding never-arriving depth.
- [ ] Collect one session log per device class and run `EvalDecision.decide` over the aggregated runs to produce the keep/drop table — this is the artifact handed to Sub-project B.

## Notes for the implementer

- **Accuracy is paramount** (spec): cost is informational. Never let a stage-time number alone justify dropping a mechanism — only redundancy does.
- **Probe overhead**: the probe must stay dev-flag-gated and cheap; CPU% is sampled by the overlay, not the GL thread, to avoid distorting the cost it measures.
- **ARCore API drift**: confirm `RecordingConfig`/`setPlaybackDatasetUri`/`acquireDepthImage16Bits` signatures against the pinned ARCore version in `gradle/libs.versions.toml` before relying on Task 9.
- This is **Sub-project A**. B (fuse the survivors, replacing the `ArRenderer.kt:391-396` toggle) and C (tap-to-distance) are separate specs/plans.
