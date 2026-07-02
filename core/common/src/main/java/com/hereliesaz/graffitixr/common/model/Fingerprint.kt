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
) {
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