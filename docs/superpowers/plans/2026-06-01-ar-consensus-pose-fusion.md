# AR Consensus Pose Fusion — Implementation Plan (Sub-project B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fix the relocalization frame bug and fuse the ARCore-consensus backbone with smoothed, confidence-weighted mark-PnP corrections into a single rendered anchor pose, replacing the `ArRenderer.kt:398-403` toggle.

**Architecture:** The reloc thread stops writing a (wrongly-framed) matrix and instead publishes its raw PnP result + the fingerprint-frame anchor. A pure, unit-tested Kotlin `PoseFusion` composes the correct world-frame correction (`inverse(V_current)·pnpMat·fpAnchor`) using the fresh GL-thread view matrix, then SLERP/nlerp-blends the rendered anchor from the consensus backbone toward that correction, weighted by PnP inlier ratio and real splat confidence. A `fusionEnabled` flag preserves the old path for A/B with the Sub-project A harness.

**Tech Stack:** Kotlin (pure matrix/quaternion math, ARCore), C++17 (MobileGS, VoxelHash, JNI), JUnit.

**Spec:** `docs/superpowers/specs/2026-06-01-ar-consensus-pose-fusion-design.md`

**Verified reality:**
- Buggy write: `MobileGS.cpp:336-340` (memcpy pnpMat→mAnchorMatrix). PnP result built at `:330-334`; `imgPts`/`inliers` at `:308-325`.
- Fingerprint commit site: `MobileGS.cpp:601-602` (`mWallDescriptors`/`mWallKeypoints3D` set) — snapshot the anchor here.
- Splat confidence: `VoxelHash::Splat.confidence` (`VoxelHash.h:14`), `mSplatData` (`VoxelHash.h:51`); stubs at `VoxelHash.cpp:262-263`; real-loop pattern at `getImmutableSplatCount` (`VoxelHash.cpp:245-248`).
- Render seam: `ArRenderer.kt:398-403`; 15Hz block at `:399`. Consensus blend reference: `AnchorOrchestrator.getConsensusMatrix` (nlerp + hemisphere check).
- All poses involved are rigid (no scale): view, PnP `[R|t]`, ARCore anchor.

---

## File structure

**Create:**
- `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/anchor/PoseMath.kt` — pure rigid-transform + quaternion helpers.
- `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/anchor/PoseFusion.kt` — fusion logic.
- `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/anchor/PoseMathTest.kt`
- `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/anchor/PoseFusionTest.kt`

**Modify:**
- `core/nativebridge/src/main/cpp/include/MobileGS.h` — reloc-result + fp-anchor fields + method decls.
- `core/nativebridge/src/main/cpp/MobileGS.cpp` — stop buggy write; store PnP result; snapshot fp-anchor; getters.
- `core/nativebridge/src/main/cpp/include/VoxelHash.h` / `VoxelHash.cpp` — real confidence means.
- `core/nativebridge/src/main/cpp/GraffitiJNI.cpp` — JNI for getRelocResult/getFingerprintAnchor.
- `core/nativebridge/.../SlamManager.kt` — Kotlin bindings.
- `feature/ar/.../rendering/ArRenderer.kt` — replace toggle with PoseFusion + `fusionEnabled` flag.

**Reloc-result array contract** (`FloatArray(19)`): `[0..15]` = pnpMat (column-major), `[16]` = inlierCount, `[17]` = matchCount, `[18]` = seq.

---

## Task 1: Pure rigid-transform + quaternion math (TDD)

**Files:** Create `PoseMath.kt`; Test `PoseMathTest.kt`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class PoseMathTest {
    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    @Test fun `multiply by identity returns original`() {
        val m = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 5f,6f,7f,1f) // translate (5,6,7)
        assertEquals(m.toList(), PoseMath.multiply(m, identity()).toList())
        assertEquals(m.toList(), PoseMath.multiply(identity(), m).toList())
    }

    @Test fun `rigidInverse undoes a translation+rotation`() {
        // 90° about Z then translate (1,2,3). inverse(M)*M == identity.
        val c = 0f; val s = 1f
        val m = floatArrayOf(c,s,0f,0f, -s,c,0f,0f, 0f,0f,1f,0f, 1f,2f,3f,1f)
        val prod = PoseMath.multiply(PoseMath.rigidInverse(m), m)
        identity().forEachIndexed { i, e -> assertEquals(e, prod[i], 1e-4f) }
    }

    @Test fun `quaternion round-trips through matrix`() {
        // 90° about Z
        val q = PoseMath.matrixToQuaternion(floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f))
        val m = PoseMath.fromQuaternionTranslation(q, floatArrayOf(0f,0f,0f))
        assertEquals(0f, m[0], 1e-4f); assertEquals(1f, m[1], 1e-4f)
        assertEquals(-1f, m[4], 1e-4f); assertEquals(0f, m[5], 1e-4f)
    }

    @Test fun `nlerp at 0 and 1 returns endpoints`() {
        val a = floatArrayOf(0f,0f,0f,1f); val b = PoseMath.matrixToQuaternion(
            floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f))
        PoseMath.nlerpQuat(a, b, 0f).forEachIndexed { i, e -> assertEquals(a[i], e, 1e-4f) }
        val n = b.let { val l = sqrt(it[0]*it[0]+it[1]*it[1]+it[2]*it[2]+it[3]*it[3]); floatArrayOf(it[0]/l,it[1]/l,it[2]/l,it[3]/l) }
        PoseMath.nlerpQuat(a, b, 1f).forEachIndexed { i, e -> assertEquals(n[i], e, 1e-4f) }
    }
}
```

- [ ] **Step 2: Run — expect FAIL** `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*PoseMathTest"`

- [ ] **Step 3: Implement `PoseMath.kt`**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.sqrt

/** Pure column-major 4x4 (OpenGL/ARCore layout) helpers for rigid transforms. No Android deps. */
object PoseMath {

    /** Column-major 4x4 multiply: returns a*b. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = sum
        }
        return r
    }

    /** Inverse of a rigid transform [R|t] (rotation+translation, no scale): [R^T | -R^T t]. */
    fun rigidInverse(m: FloatArray): FloatArray {
        val r = FloatArray(16)
        // transpose rotation
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j]
        // -R^T t
        val tx = m[12]; val ty = m[13]; val tz = m[14]
        r[12] = -(r[0] * tx + r[4] * ty + r[8] * tz)
        r[13] = -(r[1] * tx + r[5] * ty + r[9] * tz)
        r[14] = -(r[2] * tx + r[6] * ty + r[10] * tz)
        r[15] = 1f
        return r
    }

    fun translationOf(m: FloatArray) = floatArrayOf(m[12], m[13], m[14])

    /** Extract a unit quaternion (x,y,z,w) from the rotation part of a column-major matrix. */
    fun matrixToQuaternion(m: FloatArray): FloatArray {
        // m[col*4+row]; rotation r(row,col) = m[col*4+row]
        val m00 = m[0]; val m10 = m[1]; val m20 = m[2]
        val m01 = m[4]; val m11 = m[5]; val m21 = m[6]
        val m02 = m[8]; val m12 = m[9]; val m22 = m[10]
        val trace = m00 + m11 + m22
        val q = FloatArray(4)
        if (trace > 0f) {
            var s = sqrt(trace + 1f) * 2f
            q[3] = 0.25f * s
            q[0] = (m21 - m12) / s
            q[1] = (m02 - m20) / s
            q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            var s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s; q[0] = 0.25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            var s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = 0.25f * s; q[2] = (m12 + m21) / s
        } else {
            var s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = 0.25f * s
        }
        return normalizeQuat(q)
    }

    fun normalizeQuat(q: FloatArray): FloatArray {
        val l = sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3])
        return if (l == 0f) floatArrayOf(0f,0f,0f,1f) else floatArrayOf(q[0]/l, q[1]/l, q[2]/l, q[3]/l)
    }

    /** Build a column-major matrix from a unit quaternion (x,y,z,w) and a translation. */
    fun fromQuaternionTranslation(q: FloatArray, t: FloatArray): FloatArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val m = FloatArray(16)
        m[0] = 1 - 2*(y*y + z*z); m[1] = 2*(x*y + z*w);     m[2] = 2*(x*z - y*w)
        m[4] = 2*(x*y - z*w);     m[5] = 1 - 2*(x*x + z*z); m[6] = 2*(y*z + x*w)
        m[8] = 2*(x*z + y*w);     m[9] = 2*(y*z - x*w);     m[10] = 1 - 2*(x*x + y*y)
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1f
        return m
    }

    /** Normalized lerp between unit quaternions, hemisphere-corrected. t in [0,1]. */
    fun nlerpQuat(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        val dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2] + a[3]*b[3]
        val s = if (dot < 0f) -1f else 1f
        return normalizeQuat(floatArrayOf(
            a[0] + (b[0]*s - a[0]) * t,
            a[1] + (b[1]*s - a[1]) * t,
            a[2] + (b[2]*s - a[2]) * t,
            a[3] + (b[3]*s - a[3]) * t,
        ))
    }

    fun lerp(a: FloatArray, b: FloatArray, t: Float) =
        floatArrayOf(a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t, a[2]+(b[2]-a[2])*t)
}
```

- [ ] **Step 4: Run — expect PASS (4 tests).** `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*PoseMathTest"`
- [ ] **Step 5: Commit** `git add feature/ar/.../anchor/PoseMath.kt feature/ar/.../anchor/PoseMathTest.kt && git commit -m "feat(ar-fusion): pure rigid-transform + quaternion math with tests"`

---

## Task 2: PoseFusion blend + correction (TDD)

**Files:** Create `PoseFusion.kt`; Test `PoseFusionTest.kt`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseFusionTest {
    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
    private fun trans(x: Float, y: Float, z: Float) =
        floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,y,z,1f)

    @Test fun `composeCorrected with V=pnp and fpAnchor=I yields identity`() {
        val v = trans(2f, 0f, 0f)
        val r = PoseFusion.composeCorrected(vCurrent = v, pnpMat = v, fpAnchor = identity())
        identity().forEachIndexed { i, e -> assertEquals(e, r[i], 1e-4f) }
    }

    @Test fun `blend alpha 0 returns current, alpha 1 returns target`() {
        val cur = trans(0f,0f,0f); val tgt = trans(10f,0f,0f)
        assertEquals(0f, PoseFusion.blend(cur, tgt, 0f)[12], 1e-4f)
        assertEquals(10f, PoseFusion.blend(cur, tgt, 1f)[12], 1e-4f)
        assertEquals(5f, PoseFusion.blend(cur, tgt, 0.5f)[12], 1e-4f)
    }

    @Test fun `fusion returns backbone when no new reloc result`() {
        val f = PoseFusion()
        val backbone = trans(1f,1f,1f)
        // seq 0 = no result; inlierRatio irrelevant
        val out = f.currentAnchor(backbone, identity(), reloc = FloatArray(19), fpAnchor = identity(), confGlobal = 1f)
        backbone.forEachIndexed { i, e -> assertEquals(e, out[i], 1e-4f) }
    }

    @Test fun `fusion ignores low-inlier-ratio snaps`() {
        val f = PoseFusion()
        val backbone = trans(0f,0f,0f)
        // pnp that would move far, but ratio = 1/100 below threshold
        val reloc = FloatArray(19).also {
            System.arraycopy(trans(99f,0f,0f), 0, it, 0, 16); it[16] = 1f; it[17] = 100f; it[18] = 1f
        }
        val out = f.currentAnchor(backbone, identity(), reloc, fpAnchor = identity(), confGlobal = 1f)
        assertEquals(0f, out[12], 1e-3f) // unmoved
    }

    @Test fun `fusion moves toward correction on a confident new snap`() {
        val f = PoseFusion()
        val backbone = trans(0f,0f,0f)
        // V=I, pnp=trans(10,0,0), fpAnchor=I -> corrected = inv(I)*pnp*I = trans(10,0,0)
        val reloc = FloatArray(19).also {
            System.arraycopy(trans(10f,0f,0f), 0, it, 0, 16); it[16] = 90f; it[17] = 100f; it[18] = 1f
        }
        val out = f.currentAnchor(backbone, identity(), reloc, fpAnchor = identity(), confGlobal = 1f)
        // moved partway (smoothed), strictly between 0 and 10
        assert(out[12] > 0f && out[12] < 10f) { "expected partial move, got ${out[12]}" }
    }
}
```

- [ ] **Step 2: Run — expect FAIL.** `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*PoseFusionTest"`

- [ ] **Step 3: Implement `PoseFusion.kt`**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * Fuses the ARCore-consensus backbone with smoothed, confidence-weighted mark-PnP corrections into a
 * single rendered anchor model matrix (ARCore world frame). Stateful only across frames (last seq +
 * current smoothed pose); the geometry is pure (see [composeCorrected]/[blend]).
 */
class PoseFusion {
    private var lastSeq = 0f
    private var smoothed: FloatArray? = null // current rendered anchor, for cross-frame smoothing

    companion object {
        const val MIN_INLIER_RATIO = 0.5f // ignore snaps that matched poorly
        const val BASE_ALPHA = 0.25f       // max per-frame convergence toward a correction

        /** Corrected anchor model matrix in the CURRENT world frame. All inputs rigid. */
        fun composeCorrected(vCurrent: FloatArray, pnpMat: FloatArray, fpAnchor: FloatArray): FloatArray =
            PoseMath.multiply(PoseMath.multiply(PoseMath.rigidInverse(vCurrent), pnpMat), fpAnchor)

        /** Smoothed interpolation between two rigid poses (translation lerp + quaternion nlerp). */
        fun blend(current: FloatArray, target: FloatArray, alpha: Float): FloatArray {
            val t = PoseMath.lerp(PoseMath.translationOf(current), PoseMath.translationOf(target), alpha)
            val q = PoseMath.nlerpQuat(PoseMath.matrixToQuaternion(current), PoseMath.matrixToQuaternion(target), alpha)
            return PoseMath.fromQuaternionTranslation(q, t)
        }
    }

    /**
     * @param backbone ARCore-consensus model matrix (world frame), the smooth per-frame source
     * @param vCurrent current ARCore view matrix (fresh, GL thread)
     * @param reloc    FloatArray(19): [0..15]=pnpMat, [16]=inlierCount, [17]=matchCount, [18]=seq
     * @param fpAnchor fingerprint-frame anchor model matrix
     * @param confGlobal global splat confidence in [0,1] — map maturity, scales correction strength
     */
    fun currentAnchor(
        backbone: FloatArray,
        vCurrent: FloatArray,
        reloc: FloatArray,
        fpAnchor: FloatArray,
        confGlobal: Float,
    ): FloatArray {
        val seq = reloc[18]
        val matchCount = reloc[17]
        val inlierRatio = if (matchCount > 0f) reloc[16] / matchCount else 0f
        val isNew = seq > 0f && seq != lastSeq
        val base = smoothed ?: backbone

        if (!isNew || inlierRatio < MIN_INLIER_RATIO) {
            // No correction this frame: track the backbone directly.
            smoothed = backbone.copyOf()
            return backbone
        }
        lastSeq = seq
        val pnpMat = reloc.copyOf(16)
        val corrected = composeCorrected(vCurrent, pnpMat, fpAnchor)
        val alpha = (BASE_ALPHA * inlierRatio * confGlobal.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val out = blend(base, corrected, alpha)
        smoothed = out
        return out
    }
}
```

- [ ] **Step 4: Run — expect PASS (5 tests).**
- [ ] **Step 5: Commit** `git commit -m "feat(ar-fusion): PoseFusion correction + smoothed blend with tests"`

---

## Task 3: Native — publish PnP result, snapshot fingerprint anchor, stop buggy write

**Files:** `MobileGS.h`, `MobileGS.cpp`.

- [ ] **Step 1: Add fields to `MobileGS.h`** (private, near `float mAnchorMatrix[16];`)

```cpp
    // --- Pose fusion (Sub-project B): reloc result published for Kotlin to compose correctly ---
    float mPnpCamFromFpWorld[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    std::atomic<int> mPnpInlierCount{0};
    std::atomic<int> mPnpMatchCount{0};
    std::atomic<float> mPnpResultSeq{0.0f};
    float mFingerprintAnchorMatrix[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
```
Public method decls (near getAnchorTransform):
```cpp
    void getRelocResult(float* out19) const;       // [0..15]=pnpMat,16=inliers,17=matches,18=seq
    void getFingerprintAnchor(float* out16) const;
```

- [ ] **Step 2: In `MobileGS.cpp` relocThreadFunc, replace the buggy block** (`:329-340`)

Replace:
```cpp
                    // Construct 4x4 matrix from PnP result (Camera-to-World in Fingerprint Space)
                    glm::mat4 pnpMat = glm::mat4(1.0f);
                    for(int i=0; i<3; ++i) {
                        for(int j=0; j<3; ++j) pnpMat[j][i] = (float)R.at<double>(i,j);
                        pnpMat[3][i] = (float)tvec.at<double>(i);
                    }

                    // [TELEOLOGICAL CORRECTION] Snap the global anchor to match the physical fingerprint
                    std::lock_guard<std::mutex> lock(mMutex);
                    // This is a simplified version of the correction logic
                    memcpy(mAnchorMatrix, glm::value_ptr(pnpMat), 16 * sizeof(float));
                    LOGI("Relocalization: Snap-Back successful with %zu inliers", inliers.size());
```
With:
```cpp
                    // PnP gives T_camera_from_fingerprintWorld (a view matrix). DO NOT write it to
                    // mAnchorMatrix (which is a world-space MODEL matrix) — that caused overlay
                    // teleport. Publish the raw result; Kotlin composes inverse(V_current)*pnp*fpAnchor
                    // with the FRESH view matrix (see PoseFusion).
                    glm::mat4 pnpMat = glm::mat4(1.0f);
                    for(int i=0; i<3; ++i) {
                        for(int j=0; j<3; ++j) pnpMat[j][i] = (float)R.at<double>(i,j);
                        pnpMat[3][i] = (float)tvec.at<double>(i);
                    }
                    {
                        std::lock_guard<std::mutex> lock(mMutex);
                        memcpy(mPnpCamFromFpWorld, glm::value_ptr(pnpMat), 16 * sizeof(float));
                    }
                    mPnpInlierCount.store((int)inliers.size(), std::memory_order_relaxed);
                    mPnpMatchCount.store((int)imgPts.size(), std::memory_order_relaxed);
                    mPnpResultSeq.fetch_add(1.0f, std::memory_order_relaxed);
                    LOGI("Relocalization: PnP match published (%zu/%zu inliers)", inliers.size(), imgPts.size());
```
(If `atomic<float>::fetch_add` is unavailable on this NDK as in Sub-project A, use a CAS loop or make `mPnpResultSeq` an `atomic<int>` and store `(float)`. Prefer `std::atomic<long> mPnpResultSeq{0}` + `fetch_add(1)`, exposing it as a float — integers are exact up to 2^24.)

- [ ] **Step 3: Snapshot the fingerprint anchor at fingerprint commit** (`MobileGS.cpp:601-602`, where `mWallDescriptors`/`mWallKeypoints3D` are assigned — already under `mMutex` there if present; if not, guard it)

After the assignments add:
```cpp
        memcpy(mFingerprintAnchorMatrix, mAnchorMatrix, 16 * sizeof(float));
```

- [ ] **Step 4: Implement getters at end of `MobileGS.cpp`**

```cpp
void MobileGS::getRelocResult(float* out19) const {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(out19, mPnpCamFromFpWorld, 16 * sizeof(float));
    out19[16] = (float) mPnpInlierCount.load(std::memory_order_relaxed);
    out19[17] = (float) mPnpMatchCount.load(std::memory_order_relaxed);
    out19[18] = mPnpResultSeq.load(std::memory_order_relaxed);
}
void MobileGS::getFingerprintAnchor(float* out16) const {
    std::lock_guard<std::mutex> lock(mMutex);
    memcpy(out16, mFingerprintAnchorMatrix, 16 * sizeof(float));
}
```
(Adjust `mPnpResultSeq` load to `(float)` if it's an integer atomic per Step 2.)

- [ ] **Step 5: Build** `sh gradlew --offline :core:nativebridge:assembleDebug` → SUCCESSFUL.
- [ ] **Step 6: Commit** `git commit -m "fix(ar-fusion): publish PnP result instead of writing mis-framed anchor; snapshot fp anchor"`

---

## Task 4: Native — real splat confidence

**Files:** `VoxelHash.cpp` (`:262-263`).

- [ ] **Step 1: Replace the stubs**

```cpp
float VoxelHash::getGlobalConfidenceAvg() const {
    if (mSplatData.empty()) return 0.0f;
    double sum = 0.0;
    for (const auto& s : mSplatData) sum += s.confidence;
    return static_cast<float>(sum / mSplatData.size());
}
// "Visible" frustum culling is not yet available here; approximate with the global mean (documented).
// TODO(B-followup): pass the view/proj matrix to cull to the current frustum.
float VoxelHash::getVisibleConfidenceAvg() const { return getGlobalConfidenceAvg(); }
```
(If `mSplatData` access needs the same lock other methods use, mirror `getImmutableSplatCount`'s locking, which iterates `mSplatData` directly — match its pattern.)

- [ ] **Step 2: Build** `sh gradlew --offline :core:nativebridge:assembleDebug` → SUCCESSFUL.
- [ ] **Step 3: Commit** `git commit -m "feat(ar-fusion): real splat confidence averages (replace 0.5f stubs)"`

---

## Task 5: JNI + SlamManager bindings

**Files:** `GraffitiJNI.cpp`, `SlamManager.kt`.

- [ ] **Step 1: JNI** (after the stage-timing JNI funcs)

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetRelocResult(JNIEnv* env, jobject, jfloatArray out) {
    if (!gSlamEngine) return;
    float buf[19]; gSlamEngine->getRelocResult(buf);
    env->SetFloatArrayRegion(out, 0, 19, buf);
}
extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeGetFingerprintAnchor(JNIEnv* env, jobject, jfloatArray out) {
    if (!gSlamEngine) return;
    float buf[16]; gSlamEngine->getFingerprintAnchor(buf);
    env->SetFloatArrayRegion(out, 0, 16, buf);
}
```

- [ ] **Step 2: SlamManager** (public methods + externals)

```kotlin
    /** Pose fusion (B): [0..15]=pnpMat, [16]=inlierCount, [17]=matchCount, [18]=seq. */
    fun getRelocResult(): FloatArray { val o = FloatArray(19); nativeGetRelocResult(o); return o }
    fun getFingerprintAnchor(): FloatArray { val o = FloatArray(16); nativeGetFingerprintAnchor(o); return o }
```
```kotlin
    private external fun nativeGetRelocResult(out: FloatArray)
    private external fun nativeGetFingerprintAnchor(out: FloatArray)
```

- [ ] **Step 3: Build** `sh gradlew --offline :core:nativebridge:assembleDebug` → SUCCESSFUL.
- [ ] **Step 4: Commit** `git commit -m "feat(ar-fusion): JNI + SlamManager bindings for reloc result + fp anchor"`

---

## Task 6: Wire PoseFusion into ArRenderer (replace toggle) + fusionEnabled flag

**Files:** `ArRenderer.kt`.

- [ ] **Step 1: Add fields** (near other volatiles)

```kotlin
    private val poseFusion = com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion()
    // A/B switch for the Sub-project A harness: when false, reproduce the old pre/post-anchor toggle.
    @Volatile var fusionEnabled: Boolean = true
```

- [ ] **Step 2: Replace the toggle block** (`ArRenderer.kt:398-403`)

Replace:
```kotlin
            val anchorMatrix = FloatArray(16)
            if (anchorEstablished) {
                anchorOrchestrator.getConsensusMatrix(anchorMatrix)
            } else {
                val rawAnchor = slamManager.getAnchorTransform()
                System.arraycopy(rawAnchor, 0, anchorMatrix, 0, 16)
            }
```
With:
```kotlin
            // Backbone: ARCore consensus once anchored, else the native cached pose (as before).
            val backbone = FloatArray(16)
            if (anchorEstablished) {
                anchorOrchestrator.getConsensusMatrix(backbone)
            } else {
                System.arraycopy(slamManager.getAnchorTransform(), 0, backbone, 0, 16)
            }
            val anchorMatrix: FloatArray = if (fusionEnabled && anchorEstablished) {
                poseFusion.currentAnchor(
                    backbone = backbone,
                    vCurrent = viewMatrix,
                    reloc = slamManager.getRelocResult(),
                    fpAnchor = slamManager.getFingerprintAnchor(),
                    confGlobal = slamManager.getGlobalConfidenceAvg(),
                )
            } else backbone
```
(`viewMatrix` is already computed in `onDrawFrame` from `camera.getViewMatrix()` — confirm it is in scope above this block; it is used immediately below for distance math.)

- [ ] **Step 3: Build** `sh gradlew --offline :feature:ar:assembleDebug` → SUCCESSFUL.
- [ ] **Step 4: Commit** `git commit -m "feat(ar-fusion): render fused anchor pose, replacing the pre/post-anchor toggle"`

---

## Task 7: Surface the fusion flag in the dev overlay (optional A/B control)

**Files:** `ArViewModel.kt`, `app/.../MainActivity.kt` (the Sub-project A `EvalOverlay`).

- [ ] **Step 1: ArViewModel** — add `fun evalSetFusionEnabled(on: Boolean) { renderer?.fusionEnabled = on }`.
- [ ] **Step 2: MainActivity EvalOverlay** — add a `TextButton` row toggling it: `onClick = { arViewModel.evalSetFusionEnabled(!fusion); fusion = !fusion }` with a remembered `var fusion by remember { mutableStateOf(true) }`, label `"Fusion: ${if (fusion) "ON" else "OFF"}"`. Keep it inside the existing `EVAL_OVERLAY_ENABLED` gate.
- [ ] **Step 3: Build** `sh gradlew --offline :app:assembleDebug` → SUCCESSFUL.
- [ ] **Step 4: Commit** `git commit -m "feat(ar-fusion): dev-overlay toggle to A/B the fusion vs old path"`

---

## Final verification
- [ ] `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*PoseMathTest" --tests "*PoseFusionTest"` → all PASS.
- [ ] `sh gradlew --offline :core:nativebridge:assembleDebug :feature:ar:assembleDebug :app:assembleDebug` → SUCCESSFUL.
- [ ] Full `:feature:ar:testDebugUnitTest` shows only the 3 known pre-existing `ArViewModelTest` failures.
- [ ] **On-device (deferred, acceptance gate):** with the Sub-project A overlay, toggle Fusion ON/OFF and confirm reloc snap-back converges smoothly (no teleport) and lowers measured drift before trusting fusion as the release default.

## Self-review checklist (run after writing)
- Spec coverage: reloc fix (T3), one fused pose replacing toggle (T6), real confidence (T4), flag (T6/T7), pure tested math (T1/T2) — all present.
- Type consistency: reloc FloatArray(19) contract identical across native getter, JNI, SlamManager, PoseFusion. `getGlobalConfidenceAvg` already bound (Sub-project context) — confirm it exists in SlamManager; if not, add a binding in Task 5.

## Notes for the implementer
- Accuracy is paramount; the fusion ships behind `fusionEnabled` precisely so it can be validated with the A harness before becoming the unconditional default.
- This is **B** of A→B→C. **C** (tap-to-distance) folds in next, feeding tapped marks to `AnchorOrchestrator.addSupportAnchor`.
