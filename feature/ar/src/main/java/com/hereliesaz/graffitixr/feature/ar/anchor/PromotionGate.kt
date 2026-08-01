package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * `IMPLEMENTATION.md` **Phase 3** — whether a relock is trusted enough to promote new marks into the
 * authoritative reloc fingerprint.
 *
 * This is the pure-Kotlin **reference**. `MobileGS::growTrusted` transliterates it, and
 * `PromotionGateTest` pins the decisions the transliteration has to reproduce. The arrangement
 * exists because this predicate guards the one mechanism in the engine that **permanently mutates**
 * the map: a bad promotion is not a transient error, it is written into the fingerprint and
 * compounds. Phase 3's risk section calls it the only phase that can make the system worse forever
 * rather than for a frame, which is why the predicate is tested somewhere it can actually be tested
 * and the C++ is held to it by source-text assertion.
 *
 * ## Two questions, not one
 *
 * `growTrusted` as it shipped asks only about the **match**: how many correspondences survived
 * RANSAC, and what fraction of the total they were. That is necessary and it is not sufficient,
 * because a pose is fitted to the *geometry* of its inliers, not their count.
 *
 * Twenty inliers clustered in one textured corner of the frame produce a confident-looking pose with
 * a badly conditioned rotation: the correspondences pin the camera's position along the viewing ray
 * but barely constrain its orientation, so the solve is free to rotate the wall about that cluster
 * at almost no reprojection cost. The inlier ratio is *high* in that state — everything in the
 * cluster agrees — so the match test not only fails to catch it, it actively endorses it.
 *
 * So [trusted] asks a second question: do the inliers **span** the frame. Cheap — a bounding box
 * over the inlier image points, as a fraction of frame area — and it kills exactly the failure the
 * first test cannot see.
 */
object PromotionGate {

    /**
     * @param inliers RANSAC inliers from the reloc PnP.
     * @param matches correspondences the solve was given.
     * @param inlierSpread the inliers' bounding box as a fraction of frame area, in `[0, 1]`, or
     *   **negative when not measured** — the project-wide sentinel. Not-measured is treated as
     *   *failing* the spread test, which is the opposite of how `SearchRadius` treats an unmeasured
     *   drift reading, and the asymmetry is deliberate: there a missing measurement must not narrow
     *   a search, here a missing measurement must not authorise a permanent map mutation. Both
     *   choices are "fail toward the recoverable outcome"; the recoverable outcome is just in the
     *   opposite direction.
     */
    fun trusted(
        inliers: Int,
        matches: Int,
        inlierSpread: Float = NOT_MEASURED,
        minSpread: Float = MIN_INLIER_SPREAD,
    ): Boolean {
        if (!spreadOk(inlierSpread, minSpread)) return false
        return matchTrusted(inliers, matches)
    }

    /**
     * The match half, unchanged from what shipped — deliberately kept as its own function so the
     * spread term can be added without altering it, and so a test can show the two are independent.
     *
     * The bootstrap reasoning behind the ratio branch is worth keeping visible: this used to be a
     * flat `inliers >= 20`, which could not bootstrap. Self-grow is the mechanism that keeps
     * relocalization alive as the original marks get painted over, so a wall whose marks are already
     * half-covered — precisely when growth is needed — rarely reaches 20 raw inliers. The fingerprint
     * could only grow when it was already strong.
     */
    fun matchTrusted(inliers: Int, matches: Int): Boolean {
        if (inliers >= BIG_LOCK_INLIERS) return true          // a big lock always qualifies
        if (inliers < MIN_INLIERS || matches <= 0) return false  // absolute floor — PnP noise below
        return inliers.toFloat() / matches.toFloat() >= MIN_INLIER_RATIO
    }

    /**
     * The spread half. Split out so `MIN_INLIER_SPREAD` can be swept against a fixed match rule.
     *
     * A **non-finite** reading fails, and so does a negative one. NaN is what an area computation
     * over degenerate or absent points produces, and `NaN >= minSpread` is false in Kotlin anyway —
     * but relying on that would leave the behaviour to a comparison rule rather than to a decision,
     * and the C++ transliteration has no such guarantee to lean on.
     */
    fun spreadOk(inlierSpread: Float, minSpread: Float = MIN_INLIER_SPREAD): Boolean {
        if (!inlierSpread.isFinite() || inlierSpread < 0f) return false
        return inlierSpread >= minSpread
    }

    /**
     * Inliers' bounding box as a fraction of frame area.
     *
     * Bounding box, not convex hull or covariance: the failure being caught is "all the inliers are
     * in one corner", and a box catches that at a fraction of the cost. It over-reports for an
     * L-shaped or diagonal distribution — two tight clusters at opposite corners score a full frame —
     * which is a real limitation and the honest one to accept, because both of those *are* well
     * conditioned for rotation. The case it must not miss is the compact cluster, and a box cannot
     * miss that.
     *
     * Returns [NOT_MEASURED] rather than 0 when it cannot compute an answer — no points, a degenerate
     * frame — because 0 is a real reading meaning "every inlier landed on one pixel", which is the
     * single worst-conditioned state this exists to reject. If absence read as 0 the two would be
     * indistinguishable, and the one case where the gate must refuse would look identical to the case
     * where nobody asked.
     *
     * @param pointsXY flat `[x0, y0, x1, y1, ...]` inlier image points, in pixels.
     */
    fun spreadOf(pointsXY: FloatArray, frameW: Float, frameH: Float): Float {
        val n = pointsXY.size / 2
        if (n == 0 || frameW <= 0f || frameH <= 0f || !frameW.isFinite() || !frameH.isFinite()) {
            return NOT_MEASURED
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var seen = 0
        for (i in 0 until n) {
            val x = pointsXY[i * 2]
            val y = pointsXY[i * 2 + 1]
            // A non-finite point would blow the box out to the whole frame and turn the gate into a
            // rubber stamp — the precise inversion of what it is for.
            if (!x.isFinite() || !y.isFinite()) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            seen++
        }
        if (seen == 0) return NOT_MEASURED
        // Clamped because an inlier can sit fractionally outside the frame after undistortion, and a
        // box larger than the frame would report a spread above 1 — a number the threshold's units
        // do not admit.
        val w = ((maxX - minX) / frameW).coerceIn(0f, 1f)
        val h = ((maxY - minY) / frameH).coerceIn(0f, 1f)
        return w * h
    }

    /**
     * Pre-registered priors — `PARAMETERS.md` §7. The three match constants are the values that
     * shipped in `growTrusted`; `MIN_INLIER_SPREAD` is new and is a guess.
     *
     * `MIN_INLIER_SPREAD` at 0.04 is a 20% × 20% box. Chosen as the smallest span that cannot be one
     * textured object — a poster, a window frame, a patch of graffiti — at typical painting distance,
     * while still admitting the legitimate close-up where the artist is working on one part of a
     * large design. It is a prior and E5 sets it; the number matters less than the fact that the
     * term exists, since without it the spread failure is invisible to every other gate.
     */
    const val BIG_LOCK_INLIERS = 20
    const val MIN_INLIERS = 10
    const val MIN_INLIER_RATIO = 0.6f
    const val MIN_INLIER_SPREAD = 0.04f

    /** Negative, never 0 — a spread of 0 is a real and maximally bad reading. */
    const val NOT_MEASURED = -1f
}
