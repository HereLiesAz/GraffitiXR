package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable
import org.opencv.core.KeyPoint

/**
 * A unique identifier for an AR target image.
 * Now includes 3D points for Perspective-n-Point (PnP) relocalization.
 */
@Serializable
data class Fingerprint(
    val keypoints: List<@Serializable(with = com.hereliesaz.graffitixr.common.serialization.KeyPointSerializer::class) org.opencv.core.KeyPoint>,
    // 3D coordinates relative to the anchor at capture time.
    // Flattened list [x0, y0, z0, x1, y1, z1...] for serialization efficiency.
    val points3d: List<Float> = emptyList(),
    val descriptorsData: ByteArray,
    val descriptorsRows: Int,
    val descriptorsCols: Int,
    val descriptorsType: Int,
    // Canonical 256x256 raw-gray patch of the marks (row-major) for the distortion head. Empty when
    // the head isn't in use. Defaulted so older saved projects deserialize unchanged.
    val patchData: ByteArray = ByteArray(0),
    // Marks centroid [x,y,z] in the fingerprint anchor's local frame, so the AR overlay can re-center
    // on the marks after a project reload (when the builder doesn't run). Empty when unavailable;
    // defaulted so older saved projects deserialize unchanged.
    val markCenterLocal: List<Float> = emptyList(),
    /**
     * `rotationNeeded` the capture was taken under — 0/90/180/270 — or **-1 when unknown**
     * (`IMPLEMENTATION.md` 0.7).
     *
     * `-1` means the fingerprint predates Phase 0, so its 3D points are in the *sensor* camera
     * frame while everything that consumes them now assumes *display*. That is the `PAPER.md` §8
     * mismatch, frozen into a saved file: the points cannot be repaired after the fact because the
     * rotation that produced them was never recorded. Such a fingerprint must be re-captured, not
     * silently reloaded — see [isLegacyFrame].
     *
     * Adding this field is safe for the native path specifically because JNI constructs through the
     * frozen [fromNative] factory rather than the primary constructor, so a new defaulted field does
     * not change the descriptor JNI looks up. The depth path supplies no rotation and therefore gets
     * `-1`, which is honest: it never applied one.
     */
    val captureRotationDeg: Int = -1,
    /**
     * `IMPLEMENTATION.md` Phase 2 — one byte per 3D point, holding `Footprint.Region.ordinal`:
     * the point's position relative to the design's projected footprint at capture.
     *
     * **Empty means legacy "all backbone"**, not "all inside". A fingerprint saved before Phase 2
     * has no partition, and the safe reading is that every point is load-bearing — treating them as
     * corroboration-only would exclude the entire map from the reloc PnP and the target would never
     * lock at all. Consumers must branch on `regions.isEmpty()` rather than indexing blindly.
     *
     * A parallel array rather than a struct-of-arrays rewrite, matching how the descriptors (an
     * opaque row-major blob) and the points (a flat float list) are already stored, and cheap to
     * serialize. The invariant that it is 1:1 with the points is enforced in `init`, for the same
     * reason the points/descriptor-rows check is: the reloc path indexes these by row, so a
     * disagreement reads past the end of a vector rather than failing.
     */
    val regions: ByteArray = ByteArray(0),
    /**
     * The design's **effective** (scale-included) half-extents in metres at the moment [regions]
     * was computed, or `-1` when unknown.
     *
     * Stored because the regions are only meaningful against the design size that produced them.
     * Folding the overlay scale in — rather than storing raw extents and a separate scale — makes
     * the staleness check a single float comparison per axis, and sidesteps the trap
     * `IMPLEMENTATION.md` Phase 2 flags: `OverlayRenderer.setExtent`'s two call sites are a fixed
     * constant and a one-shot screen-fit, **neither driven by the user**. The user's resize is
     * `overlayScale`, which enters the model matrix, so a staleness check watching the extents alone
     * would compare two numbers that never move.
     */
    val captureHalfW: Float = -1f,
    val captureHalfH: Float = -1f,
    /**
     * The fingerprint **anchor's** pose expressed in the capture camera frame — `V_cv · A`, where
     * `V_cv` is the capture's CV-convention world→camera view and `A` the anchor's world pose
     * (column-major 4x4, 16 floats). Empty when unknown.
     *
     * This is the bridge the Phase-2 partition crosses. [points3d] are in the capture camera frame;
     * the artwork's pose is known in world. Φ needs both in one frame, and there is no *durable*
     * world frame to meet in — ARCore's world origin does not survive a session, and even within one
     * it drifts and gets corrected by reloc. The anchor is the frame that does survive, which is why
     * [markCenterLocal] is already stored relative to it.
     *
     * So the durable composition is
     *
     * ```
     * design_in_points_frame  =  captureAnchorCam · (A_live⁻¹ · M_designWorld)
     * ```
     *
     * — capture-time constant on the left, live anchor-relative design pose on the right, and no
     * world frame anywhere. Both factors are rigid, so the product is, and `Footprint`'s rigid
     * inverse stays applicable.
     *
     * Stored unconditionally at capture, **including when no design is placed yet**. That case is
     * not an edge case: target creation is what *establishes* the anchor the artwork will sit on, so
     * on a first capture there is no artwork to partition against. Recording this now is what lets
     * the partition be computed later, the moment the design does land, instead of the fingerprint
     * being permanently unpartitionable.
     */
    val captureAnchorCam: List<Float> = emptyList(),
) {
    init {
        // Defensive: a corrupt or truncated project file must never construct a Fingerprint whose
        // descriptor blob disagrees with its declared dims — the native restore (GraffitiJNI) wraps
        // this blob in a cv::Mat, so a short blob would read out of bounds. Mirrors WallFeatureMap's
        // guards. Empty optional arrays (older projects, or a keypoint-only fingerprint) are allowed.
        require(descriptorsRows >= 0 && descriptorsCols >= 0) { "descriptor dims must be non-negative" }
        require(points3d.isEmpty() || points3d.size % 3 == 0) {
            "points3d (${points3d.size}) must be a flat list of [x,y,z] triplets"
        }
        if (descriptorsRows > 0) {
            require(descriptorsData.size % descriptorsRows == 0) {
                "descriptorsData (${descriptorsData.size}) must divide evenly into $descriptorsRows rows"
            }
            if (descriptorsCols > 0) {
                require((descriptorsData.size / descriptorsRows) % descriptorsCols == 0) {
                    "descriptor row stride (${descriptorsData.size / descriptorsRows}) must be a multiple of descriptorsCols ($descriptorsCols)"
                }
            }
            // Same argument, second array. The reloc PnP subscripts the 3D point list by DESCRIPTOR
            // ROW (`wallKps3d[match.trainIdx]`), so a file whose point count disagrees with its row
            // count indexes past the end of the vector and feeds garbage 3D into solvePnPRansac. The
            // blob check above was carried over from WallFeatureMap; this one was not.
            require(points3d.isEmpty() || points3d.size / 3 == descriptorsRows) {
                "points3d holds ${points3d.size / 3} points but $descriptorsRows descriptor rows were declared; " +
                    "the reloc path indexes points by descriptor row, so they must be 1:1"
            }
        }
        // Same argument, third array. Native filters the reloc correspondence build by
        // `mWallRegions[i]` for the row it just matched, so a regions array shorter than the point
        // list indexes past the end of a vector. Empty is the legacy "no partition" case and is
        // explicitly allowed; anything else must be exactly 1:1.
        require(regions.isEmpty() || regions.size == points3d.size / 3) {
            "regions holds ${regions.size} tags but ${points3d.size / 3} points were supplied; " +
                "the partition is indexed per point, so they must be 1:1 (or regions empty for legacy)"
        }
        // A 4x4 or nothing. A short list here would be read as a matrix by every consumer and index
        // past its end; a long one silently ignores whatever follows. Neither should reach a
        // reclassify from a truncated project file.
        require(captureAnchorCam.isEmpty() || captureAnchorCam.size == 16) {
            "captureAnchorCam holds ${captureAnchorCam.size} floats; a column-major 4x4 is 16 " +
                "(or empty when unknown)"
        }
    }

    /** True when this fingerprint carries no partition — a pre-Phase-2 capture. See [regions]. */
    fun isUnpartitioned(): Boolean = regions.isEmpty()

    /**
     * True when [regions] was computed against a different design size than [halfW]/[halfH].
     *
     * The artist pinching the design mid-session is normal, and the right response is to recompute
     * the regions from the stored 3D points (a cheap pure-Kotlin pass) rather than force a
     * re-capture. An unpartitioned or extent-less fingerprint is never "stale" — there is nothing to
     * recompute against.
     */
    fun isPartitionStale(halfW: Float, halfH: Float, eps: Float = 1e-4f): Boolean {
        if (regions.isEmpty() || captureHalfW < 0f || captureHalfH < 0f) return false
        return kotlin.math.abs(captureHalfW - halfW) > eps ||
            kotlin.math.abs(captureHalfH - halfH) > eps
    }

    /**
     * True when this fingerprint carries metric 3D points but no record of the rotation they were
     * built under — i.e. it was saved before Phase 0 and its geometry is skewed by `PAPER.md` §8.
     *
     * Keyed on [points3d] being non-empty deliberately: a descriptors-only fingerprint has no 3D to
     * be wrong about, so it is not legacy in this sense and must not be refused.
     */
    fun isLegacyFrame(): Boolean = points3d.isNotEmpty() && captureRotationDeg < 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Fingerprint
        // OpenCV's KeyPoint has no value-based equals(), so a plain List<KeyPoint> comparison is
        // reference-based and returns false for structurally-identical fingerprints. Compare
        // field-wise to honour the value-equality this override already provides for the ByteArrays.
        if (!keypointsValueEqual(keypoints, other.keypoints)) return false
        if (points3d != other.points3d) return false
        if (!descriptorsData.contentEquals(other.descriptorsData)) return false
        if (descriptorsRows != other.descriptorsRows) return false
        if (descriptorsCols != other.descriptorsCols) return false
        if (descriptorsType != other.descriptorsType) return false
        if (!patchData.contentEquals(other.patchData)) return false
        if (markCenterLocal != other.markCenterLocal) return false
        // The Phase-0/Phase-2 fields belong here for one concrete reason: a persistence round-trip
        // test asserts `decoded == original`, and a field missing from equals makes that assertion
        // pass whether or not the field survived serialization. Two fingerprints differing only in
        // which frame their points are in, or in how their points are partitioned, are not equal.
        if (captureRotationDeg != other.captureRotationDeg) return false
        if (!regions.contentEquals(other.regions)) return false
        if (captureHalfW != other.captureHalfW) return false
        if (captureHalfH != other.captureHalfH) return false
        if (captureAnchorCam != other.captureAnchorCam) return false
        return true
    }

    override fun hashCode(): Int {
        var result = keypointsValueHash(keypoints)
        result = 31 * result + points3d.hashCode()
        result = 31 * result + descriptorsData.contentHashCode()
        result = 31 * result + descriptorsRows
        result = 31 * result + descriptorsCols
        result = 31 * result + descriptorsType
        result = 31 * result + patchData.contentHashCode()
        result = 31 * result + markCenterLocal.hashCode()
        result = 31 * result + captureRotationDeg
        result = 31 * result + regions.contentHashCode()
        result = 31 * result + captureHalfW.hashCode()
        result = 31 * result + captureHalfH.hashCode()
        result = 31 * result + captureAnchorCam.hashCode()
        return result
    }

    companion object {
        /**
         * FROZEN JNI ABI — `buildFingerprintObject` in GraffitiJNI.cpp constructs Fingerprints
         * through [fromNative] by this exact name/descriptor. The raw constructor must never be
         * used from JNI: Kotlin default parameters don't emit reduced-arity JVM overloads, so
         * every field added to the data class silently broke the native lookup (twice now:
         * patchData, then markCenterLocal) and setWallFingerprint returned null on every capture.
         *
         * Never change [fromNative]'s signature. Add new Fingerprint fields with defaults and,
         * if native code must supply them, introduce a NEW factory with its own frozen descriptor.
         * FingerprintJniContractTest asserts the descriptor below matches the compiled method.
         */
        const val JNI_FACTORY_NAME = "fromNative"
        const val JNI_FACTORY_DESCRIPTOR =
            "(Ljava/util/List;Ljava/util/List;[BIII[BLjava/util/List;)" +
                "Lcom/hereliesaz/graffitixr/common/model/Fingerprint;"

        @JvmStatic
        fun fromNative(
            keypoints: List<KeyPoint>,
            points3d: List<Float>,
            descriptorsData: ByteArray,
            descriptorsRows: Int,
            descriptorsCols: Int,
            descriptorsType: Int,
            patchData: ByteArray,
            markCenterLocal: List<Float>,
        ): Fingerprint = Fingerprint(
            keypoints = keypoints,
            points3d = points3d,
            descriptorsData = descriptorsData,
            descriptorsRows = descriptorsRows,
            descriptorsCols = descriptorsCols,
            descriptorsType = descriptorsType,
            patchData = patchData,
            markCenterLocal = markCenterLocal,
        )
    }
}

/** Value-equality for OpenCV KeyPoints (which compare by reference): all geometric fields match. */
private fun keypointsValueEqual(a: List<KeyPoint>, b: List<KeyPoint>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        val p = a[i]
        val q = b[i]
        if (p.pt.x != q.pt.x || p.pt.y != q.pt.y) return false
        if (p.size != q.size || p.angle != q.angle || p.response != q.response) return false
        if (p.octave != q.octave || p.class_id != q.class_id) return false
    }
    return true
}

/** Field-wise hash matching [keypointsValueEqual] (KeyPoint.hashCode is identity-based). */
private fun keypointsValueHash(kps: List<KeyPoint>): Int {
    var h = 1
    for (k in kps) {
        h = 31 * h + k.pt.x.hashCode()
        h = 31 * h + k.pt.y.hashCode()
        h = 31 * h + k.size.hashCode()
        h = 31 * h + k.angle.hashCode()
        h = 31 * h + k.response.hashCode()
        h = 31 * h + k.octave
        h = 31 * h + k.class_id
    }
    return h
}
