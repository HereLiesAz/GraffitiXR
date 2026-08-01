package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseFusionTest {
    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
    private fun trans(x: Float, y: Float, z: Float) =
        floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,y,z,1f)

    /** reloc payload with vCurrent=I, fpAnchor=I so composeCorrected(reloc)=target. */
    private fun reloc(target: FloatArray, inliers: Float, matches: Float, seq: Float) =
        FloatArray(19).also {
            System.arraycopy(target, 0, it, 0, 16); it[16] = inliers; it[17] = matches; it[18] = seq
        }

    @Test fun `composeCorrected with V=pnp and fpAnchor=I yields identity`() {
        val v = trans(2f, 0f, 0f)
        val r = PoseFusion.composeCorrected(vCurrent = v, pnpMat = v, fpAnchor = identity())
        identity().forEachIndexed { i, e -> assertEquals(e, r[i], 1e-4f) }
    }

    /**
     * The test above uses a pure translation for all three operands, so `inverse(V)·pnp·fpAnchor` is
     * order-independent for that input and a composition written in any order passes it. Use a
     * rotation and a translation that do not commute so the ORDER of the three factors is actually
     * pinned — this is the composition PlaneMarks' documented rotation-convention question turns on,
     * and it had no test that could see a swap.
     */
    @Test fun `composeCorrected pins the order of its three factors`() {
        // 90 deg about Z, no translation.
        val rotZ = floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        val v = rotZ                       // world -> camera is a pure rotation
        val pnp = identity()               // camera -> fingerprint world is identity
        val fpAnchor = trans(1f, 0f, 0f)   // anchor sits +1 along the fingerprint frame's X

        // inverse(V)·pnp·fpAnchor = rotZ⁻¹ · I · trans(1,0,0). rotZ⁻¹ maps +X to -Y, so the composed
        // anchor sits at (0, -1, 0). A composition in any other order does not land there.
        val r = PoseFusion.composeCorrected(vCurrent = v, pnpMat = pnp, fpAnchor = fpAnchor)
        assertEquals(0f, r[12], 1e-4f)
        assertEquals(-1f, r[13], 1e-4f)
        assertEquals(0f, r[14], 1e-4f)
    }

    /**
     * `IMPLEMENTATION.md` **0.6**, honestly scoped.
     *
     * An earlier version of this file had two tests here claiming to "pin" that `composeCorrected`
     * is consistent under convention B. They did not, and the way they failed is worth recording:
     * one asserted `composeCorrected(R, R, A) == composeCorrected(I, I, A)`, which is
     * `inv(R)·R·A == A` — true by associativity for **every** invertible `R`, transposed or
     * mirrored or a shear. The other asserted that inserting a rotation into the middle of a matrix
     * product changes the product. Neither referenced ARCore, the capture path, or `rotationNeeded`;
     * neither could tell convention A from convention B; and neither would have failed under the
     * missing-factor defect described in 0.6/0.9. The claim lived in the KDoc, not the assertions.
     *
     * What is genuinely testable here is the factor ORDER, which the test above already covers with
     * non-commuting operands. Whether the three operands are in mutually consistent FRAMES is not a
     * property of this function — it is a property of its four callers' data, and it is currently
     * **false**: `vCurrent` is GL-convention, `pnpMat` is CV-convention, `pnpMat`'s domain is the
     * capture camera frame, and `fpAnchor` is a world-space model matrix. See 0.9.
     *
     * So this file deliberately asserts nothing about frames. A test that cannot fail is worse than
     * no test, because it is counted as coverage.
     */
    @Test fun `composeCorrected is frame-agnostic — the convention lives in its callers`() {
        // Pinned as executable documentation of the above: the function is pure composition, so it
        // cannot detect a frame error and must not be credited with doing so.
        val rotZ90 = floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        val fpAnchor = trans(1f, 0f, 0f)
        val bothRotated = PoseFusion.composeCorrected(rotZ90, rotZ90, fpAnchor)
        val neitherRotated = PoseFusion.composeCorrected(identity(), identity(), fpAnchor)
        for (i in 0 until 16) assertEquals("element $i", neitherRotated[i], bothRotated[i], 1e-4f)

        // ...and it holds just as well for transforms that are nothing to do with a display
        // rotation — a rotation about a DIFFERENT axis, and a pure translation. That is the point:
        // the cancellation is associativity over `rigidInverse`, not evidence about `R_z`.
        //
        // (Both stay RIGID deliberately. `composeCorrected` inverts via `PoseMath.rigidInverse`,
        // which is only an inverse for rotation+translation; feeding it a shear breaks the identity
        // for that reason and not for any reason about frames.)
        val rotX90 = floatArrayOf(1f,0f,0f,0f, 0f,0f,1f,0f, 0f,-1f,0f,0f, 0f,0f,0f,1f)
        val bothRotX = PoseFusion.composeCorrected(rotX90, rotX90, fpAnchor)
        for (i in 0 until 16) assertEquals("rotX element $i", neitherRotated[i], bothRotX[i], 1e-4f)

        val move = trans(3f, -2f, 7f)
        val bothMoved = PoseFusion.composeCorrected(move, move, fpAnchor)
        for (i in 0 until 16) assertEquals("trans element $i", neitherRotated[i], bothMoved[i], 1e-4f)
    }

    /**
     * `IMPLEMENTATION.md` **0.6**, rotation half.
     *
     * The order test above pins the factors with ONE rotation and a translation, which fixes where
     * the anchor lands but says almost nothing about the 3×3 block — a rotation composed on the
     * wrong side, or through a wrong-handed frame conversion, can still put the origin in the right
     * place. This asserts the whole rotation block against a matrix derived by hand.
     *
     * What 0.6 settled (`PAPER.md` §8.3): `vCurrent` is `Camera.getViewMatrix`, which ARCore
     * documents as already display-oriented, and convention B puts the capture side in the same
     * display frame. So the two sides agree *for rotation* and the composition needs **no** `R_z`
     * inserted and **no** handedness flip between `inverse(vCurrent)` and `pnpMat`. The expected
     * rotation is therefore exactly `Rx(−30) · Ry(40) · Rz(50)` and nothing else — which is a claim
     * with content, because every one of the plausible defects lands somewhere else:
     *
     *  - factors reordered → the three axes do not commute, so any permutation moves the block;
     *  - `vCurrent` not inverted → `sin(30)` changes sign in rows 1 and 2;
     *  - a `C = diag(1,−1,−1)` inserted between the first two factors → `C·Ry(b)·C = Ry(−b)`, so
     *    `sin(40)` flips wherever it appears;
     *  - an inverse taken by negating rather than transposing → mirrored, not rotated.
     *
     * The expected entries below are written out from the symbolic product of the three axis
     * rotations, NOT by calling [PoseMath.multiply] — deriving them the way the code derives them
     * would make this an identity rather than a check. Nothing here touches `android.opengl.Matrix`,
     * which is a return-default stub in this source set and would make any assertion built on it
     * pass against an array of zeros.
     *
     * Deliberately NOT in scope: whether the three operands are in mutually consistent frames. They
     * are not — see the test above and 0.9. This pins the three-factor form as it stands, so 0.9's
     * two extra factors have to change it on purpose rather than by accident.
     */
    @Test fun `composeCorrected rotation block matches a hand-derived product`() {
        val a = Math.toRadians(30.0).toFloat(); val ca = cos(a); val sa = sin(a)
        val b = Math.toRadians(40.0).toFloat(); val cb = cos(b); val sb = sin(b)
        val g = Math.toRadians(50.0).toFloat(); val cg = cos(g); val sg = sin(g)
        val tx = 1f; val ty = 2f; val tz = 3f

        // Column-major (element [col*4+row]), same layout PoseMath and ARCore use. Right-handed,
        // matching the existing rotZ fixture above: column 0 of Rz(90) is (0,1,0), i.e. +X → +Y.
        val vCurrent = floatArrayOf(1f,0f,0f,0f, 0f,ca,sa,0f, 0f,-sa,ca,0f, 0f,0f,0f,1f)   // Rx(30)
        val pnpMat = floatArrayOf(cb,0f,-sb,0f, 0f,1f,0f,0f, sb,0f,cb,0f, 0f,0f,0f,1f)     // Ry(40)
        val fpAnchor = floatArrayOf(cg,sg,0f,0f, -sg,cg,0f,0f, 0f,0f,1f,0f, tx,ty,tz,1f)   // Rz(50), t

        // E = Rx(-a)·Ry(b)·Rz(g), multiplied out by hand.
        //   Rx(-a)·Ry(b) = [ cb      0    sb   ]
        //                  [-sa·sb   ca   sa·cb]
        //                  [-ca·sb  -sa   ca·cb]
        // then right-multiplied by Rz(g), whose third column is (0,0,1) — which is why column 2 of E
        // is just column 2 of that intermediate.
        val e = arrayOf(
            floatArrayOf(cb * cg,                    -cb * sg,                   sb),
            floatArrayOf(ca * sg - sa * sb * cg,     ca * cg + sa * sb * sg,     sa * cb),
            floatArrayOf(-ca * sb * cg - sa * sg,    ca * sb * sg - sa * cg,     ca * cb),
        )
        // The first two factors carry no translation, so the composed translation is the same
        // intermediate applied to fpAnchor's t.
        val et = floatArrayOf(
            cb * tx + sb * tz,
            -sa * sb * tx + ca * ty + sa * cb * tz,
            -ca * sb * tx - sa * ty + ca * cb * tz,
        )

        val r = PoseFusion.composeCorrected(vCurrent, pnpMat, fpAnchor)
        for (row in 0 until 3) for (col in 0 until 3) {
            assertEquals("rotation [$row][$col]", e[row][col], r[col * 4 + row], 1e-5f)
        }
        for (i in 0 until 3) assertEquals("translation [$i]", et[i], r[12 + i], 1e-5f)
        assertEquals(0f, r[3], 1e-6f); assertEquals(0f, r[7], 1e-6f)
        assertEquals(0f, r[11], 1e-6f); assertEquals(1f, r[15], 1e-6f)
    }

    @Test fun `blend alpha 0 returns current, alpha 1 returns target`() {
        val cur = trans(0f,0f,0f); val tgt = trans(10f,0f,0f)
        assertEquals(0f, PoseFusion.blend(cur, tgt, 0f)[12], 1e-4f)
        assertEquals(10f, PoseFusion.blend(cur, tgt, 1f)[12], 1e-4f)
        assertEquals(5f, PoseFusion.blend(cur, tgt, 0.5f)[12], 1e-4f)
    }

    @Test fun `returns backbone when no new reloc result`() {
        val f = PoseFusion()
        val backbone = trans(1f,1f,1f)
        val out = f.currentAnchor(backbone, identity(), FloatArray(19), identity(), confGlobal = 1f)
        backbone.forEachIndexed { i, e -> assertEquals(e, out[i], 1e-4f) }
    }

    @Test fun `ignores low-inlier-ratio snaps`() {
        val f = PoseFusion()
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(99f,0f,0f), inliers = 1f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        assertEquals(0f, out[12], 1e-3f)
    }

    @Test fun `first confident snap hard-snaps to correction`() {
        val f = PoseFusion()
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 90f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        assertEquals(10f, out[12], 1e-3f)
    }

    @Test fun `moderate-confidence first snap partially corrects`() {
        val f = PoseFusion()
        // ratio 0.6: above MIN_INLIER_RATIO but below COLD_SNAP_INLIER_RATIO -> smooth, not snap.
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        assertTrue("expected partial move, got ${out[12]}", out[12] > 0f && out[12] < 10f)
    }

    @Test fun `depth-off (confGlobal 0) still corrects via inlier ratio`() {
        val f = PoseFusion()
        // The old design multiplied alpha by confGlobal, so conf=0 froze the overlay. The floor fixes it.
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 0f)
        assertTrue("expected non-zero correction with depth off, got ${out[12]}", out[12] > 0f)
    }

    /**
     * The teleological claim: the further along the painting, the harder the overlay locks. This is
     * the behaviour that was inert while ArRenderer pinned confGlobal at 1f — both ends of the range
     * produced the identical correction.
     */
    @Test fun `corroboration scales correction strength`() {
        // Same relock, same inlier ratio; only the corroboration differs.
        val bare = PoseFusion().currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 0f)
        val painted = PoseFusion().currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        assertTrue(
            "a corroborated wall should pull harder than a bare one: bare=${bare[12]} painted=${painted[12]}",
            painted[12] > bare[12],
        )
        // And the floor bounds how much. This 2x is ARITHMETIC at confGlobal = 1, and it is pinned
        // here as a check on CONF_FLOOR's VALUE — 1/0.5 — not as a claim about real walls.
        //
        // IMPLEMENTATION.md 5b.2: since the denominator became `matched / predicted`, an input of
        // 1.0 is not reachable on any wall (descriptor repeatability across a repaint, lighting
        // drift, and Phase 4's lone-candidate skip each hold it below). The realistic ratio is
        // `1 + m` where m is the achievable maximum, and it is smaller than this. Read this
        // assertion as "CONF_FLOOR is still 0.5", which is what it can actually prove.
        assertEquals(2f, painted[12] / bare[12], 1e-3f)
        assertEquals("...which is exactly a restatement of the constant", 0.5f, PoseFusion.CONF_FLOOR, 0f)
    }

    /**
     * `IMPLEMENTATION.md` **5b.2** — the CONTRACT the floor has to satisfy, asserted so it survives
     * E11 moving the number.
     *
     * The test above pins `CONF_FLOOR == 0.5` through an arithmetic consequence, which is useful and
     * will need editing the moment E11 reports. These three properties are what must hold for *any*
     * floor in `(0, 1)`, so they keep their meaning across that change — and they are the ones a
     * re-derivation could plausibly break:
     *
     *  - a bare wall still corrects, or an unpainted mural can never relock;
     *  - correction is monotone in corroboration, or the signal is wired backwards;
     *  - the floor is a floor — nothing drives correction below it, including the "not measured"
     *    sentinel, which `ArRenderer` maps to 0 precisely so it lands here.
     *
     * Asserted at a REALISTIC corroboration maximum rather than at 1.0, because that is the regime
     * the system actually runs in and the one 5b.2 says the old rationale mis-described.
     */
    @Test
    fun `the floor bounds correction from below at any achievable corroboration`() {
        fun at(conf: Float) = PoseFusion().currentAnchor(
            trans(0f, 0f, 0f), identity(),
            reloc(trans(10f, 0f, 0f), inliers = 60f, matches = 100f, seq = 1f), identity(),
            confGlobal = conf,
        )[12]

        val bare = at(0f)
        assertTrue("a bare wall must still correct", bare > 0f)
        // Monotone, sampled across the range a real wall can produce rather than only at the ends.
        var prev = bare
        for (c in floatArrayOf(0.1f, 0.25f, 0.4f, 0.55f, 0.7f)) {
            val here = at(c)
            assertTrue("correction must not fall as corroboration rises: $c gave $here after $prev",
                here >= prev)
            prev = here
        }
        // The floor is the low end, and the unmeasured sentinel lands exactly on it — ArRenderer
        // coerces the negative to 0f, so this is the value PoseFusion actually sees when the artist
        // is looking away from the design.
        assertEquals("the sentinel path must land on the floor, not below it", bare, at(0f), 0f)
        assertTrue("nothing may drive correction below the bare-wall floor", at(0.7f) >= bare)
    }

    /**
     * Phase 5a moved the per-attempt decay off the painting-progress channel and onto a separate
     * corroboration-confidence channel, which is what now feeds `confGlobal`. The reason the split
     * matters is a TRANSIENT, not a steady state: a few bad frames used to decay the signal by 0.9
     * per reloc tick, and at a 60 ms tick that suppressed correction strength for seconds after the
     * wall came back. A steady-state comparison cannot see that; this simulates the dip.
     *
     * The contract being pinned: a confidence dip may SLOW the correction, never reverse it.
     */
    @Test fun `a confidence dip slows the correction but never reverses it`() {
        val f = PoseFusion()
        val backbone = trans(0f, 0f, 0f)
        // Warm relock at full confidence.
        f.currentAnchor(backbone, identity(),
            reloc(trans(10f, 0f, 0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        val afterGood = f.currentAnchor(backbone, identity(),
            reloc(trans(10f, 0f, 0f), inliers = 60f, matches = 100f, seq = 2f), identity(), confGlobal = 1f)[12]

        // Three ticks of a decayed reading (0.9^1..0.9^3), same relock quality throughout.
        var last = afterGood
        for ((i, conf) in listOf(0.9f, 0.81f, 0.729f).withIndex()) {
            val out = f.currentAnchor(backbone, identity(),
                reloc(trans(10f, 0f, 0f), inliers = 60f, matches = 100f, seq = 3f + i), identity(),
                confGlobal = conf)[12]
            assertTrue("a dip must not push the overlay backwards: was $last now $out", out >= last)
            last = out
        }
        assertTrue("the correction must still be advancing toward the fix, got $last", last > afterGood)
    }

    /**
     * `getCorroborationConfidence` returns a NEGATIVE sentinel when nothing has been measured yet,
     * which is a different state from "measured and found nothing". ArRenderer maps it to 0f. Pin
     * that 0f behaves as the conservative floor rather than freezing the overlay — otherwise a
     * never-corroborated wall (no design registered at all) would stop correcting entirely.
     */
    @Test fun `unmeasured corroboration mapped to zero still corrects at the floor`() {
        val unmeasured = PoseFusion().currentAnchor(trans(0f, 0f, 0f), identity(),
            reloc(trans(10f, 0f, 0f), inliers = 60f, matches = 100f, seq = 1f), identity(),
            confGlobal = (-1f).coerceAtLeast(0f))
        assertTrue("an unmeasured wall must still correct, got ${unmeasured[12]}", unmeasured[12] > 0f)
    }

    @Test fun `corroboration outside 0-1 is clamped, not extrapolated`() {
        val full = PoseFusion().currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        val over = PoseFusion().currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 5f)
        assertEquals(full[12], over[12], 1e-4f)
    }

    @Test fun `correction persists and stays world-locked between snaps`() {
        val f = PoseFusion()
        // Confident cold snap establishes D = +10x at backbone origin.
        f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 90f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        // No new snap, but ARCore's frame drifts the backbone by +1z: D must still apply.
        val out = f.currentAnchor(trans(0f,0f,1f), identity(), FloatArray(19), identity(), confGlobal = 1f)
        assertEquals(10f, out[12], 1e-3f)
        assertEquals(1f, out[14], 1e-3f)
    }

    @Test fun `confident relock that diverges far hard-snaps (pocket case)`() {
        val f = PoseFusion()
        f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 90f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        // New confident snap puts the anchor 10m away -> beyond COLD_SNAP_DIST_M -> instant relock.
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(20f,0f,0f), inliers = 90f, matches = 100f, seq = 2f), identity(), confGlobal = 1f)
        assertEquals(20f, out[12], 1e-3f)
    }

    @Test fun `small confident relock smooths instead of teleporting`() {
        val f = PoseFusion()
        f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 90f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        // 5cm move (< COLD_SNAP_DIST_M) -> not cold -> smoothed, lands just past 10, not snapped to 10.05.
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10.05f,0f,0f), inliers = 90f, matches = 100f, seq = 2f), identity(), confGlobal = 1f)
        assertTrue("expected smoothed move, got ${out[12]}", out[12] > 10f && out[12] < 10.05f)
    }
}
