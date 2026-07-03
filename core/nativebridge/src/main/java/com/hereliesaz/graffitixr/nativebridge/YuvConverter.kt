package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import android.media.Image
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import java.nio.ByteBuffer

/**
 * Thin JNI binding that converts a camera-frame YUV_420_888 [Image] into RGBA and writes it
 * directly into a caller-owned [Bitmap]. Replaces the old
 * `ImageProcessingUtils.convertYuvToRgbaDirect` path — despite that name it round-tripped through
 * `YuvImage.compressToJpeg` + `BitmapFactory.decodeByteArray`, i.e. a full JPEG codec per call.
 *
 * The native side reuses the same OpenCV cvtColor path already linked into the C++ engine
 * (`nativeFeedYuvFrame`), so no new native dependency is added. NEON-optimised on ARM.
 *
 * This is a top-level `object` (no DI, no state): it holds no engine state and can be called from
 * anywhere on the camera-image ownership boundary.
 */
object YuvConverter {

    init {
        // Idempotent: matches the pattern used by SlamManager, ImageProcessor, StencilProcessor,
        // etc. NativeLibLoader guards with an AtomicBoolean, so the first constructor to run wins.
        NativeLibLoader.loadAll()
    }

    /**
     * Convert [image] (YUV_420_888) into [out] (ARGB_8888, allocated by the caller at the same
     * dimensions as [image]). Unpacks the three planes' strides once, delegates the actual
     * conversion + bitmap write to native.
     */
    fun yuvToRgbaBitmap(image: Image, out: Bitmap) {
        require(out.config == Bitmap.Config.ARGB_8888) { "out must be ARGB_8888" }
        require(out.width == image.width && out.height == image.height) {
            "out bitmap ${out.width}x${out.height} does not match image ${image.width}x${image.height}"
        }
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        // NV21/NV12 semi-planar layouts share UV row stride between the U and V planes; I420 has
        // pixelStride == 1 on both. Native uses uvRowStride and uvPixelStride together to pick
        // the branch — same contract SlamManager.nativeFeedYuvFrame already uses.
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        nativeYuvToRgbaBitmap(
            yPlane.buffer, uPlane.buffer, vPlane.buffer,
            image.width, image.height,
            yPlane.rowStride, uvRowStride, uvPixelStride,
            out,
        )
    }

    /**
     * FROZEN JNI ABI — GraffitiJNI.cpp resolves this by exact descriptor.
     * See YuvConverterContractTest, which locks the descriptor against a literal.
     * Buffers must be direct (`ByteBuffer.isDirect == true`) — Image planes already are.
     */
    external fun nativeYuvToRgbaBitmap(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        outBitmap: Bitmap,
    )
}
