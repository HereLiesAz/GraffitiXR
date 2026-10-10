#pragma once
#include <cstdint>

/**
 * Bounds checks for camera frames handed across JNI as direct ByteBuffers.
 *
 * Every JNI entry point that wraps a caller's buffer in a cv::Mat must prove the buffer holds
 * every byte that Mat will read; OpenCV does not know the allocation's real length. These were
 * inline arithmetic at three call sites (nativeFeedYuvFrame, nativeFeedColorFrame,
 * nativeYuvToRgbaBitmap), later joined by the DEPTH16 buffers of nativeSetWallFingerprint and
 * nativeSetArtworkFingerprint; they live here so a host test (src/test/cpp/FrameBufferGuardTest.cpp)
 * can pin the arithmetic without a JVM.
 *
 * `capacity <= 0` means GetDirectBufferCapacity could not report one (not a direct buffer, or the
 * JNI call is unsupported). That is accepted, as the original call sites did: refusing every
 * frame from such a source would turn a missing diagnostic into a dead camera feed.
 *
 * All arithmetic is 64-bit: 32-bit Android ABIs ship, where height * stride wraps size_t.
 */
namespace graffitixr {

/** A single 8-bit plane read as `height` rows of `width` bytes, `rowStride` bytes apart. */
inline bool planeFits(long long capacity, int width, int height, int rowStride) {
    if (width <= 0 || height <= 0 || rowStride < width) return false;
    if (capacity <= 0) return true;
    const uint64_t need =
        static_cast<uint64_t>(height - 1) * static_cast<uint64_t>(rowStride) + static_cast<uint64_t>(width);
    return static_cast<uint64_t>(capacity) >= need;
}

/** A tightly packed frame of `bytesPerPixel`-byte pixels (e.g. 4 for RGBA_8888). */
inline bool packedFrameFits(long long capacity, int width, int height, int bytesPerPixel) {
    if (width <= 0 || height <= 0 || bytesPerPixel <= 0) return false;
    if (capacity <= 0) return true;
    const uint64_t need = static_cast<uint64_t>(width) * static_cast<uint64_t>(height) *
                          static_cast<uint64_t>(bytesPerPixel);
    return static_cast<uint64_t>(capacity) >= need;
}

/**
 * A 16-bit-per-sample plane (ARCore DEPTH16) read as `height` rows of `width` samples, `rowStride`
 * BYTES apart — what MobileGS's depth back-projection indexes. The last row is read only to its
 * final sample, not to the stride.
 */
inline bool plane16Fits(long long capacity, int width, int height, int rowStride) {
    if (width <= 0 || height <= 0 || rowStride <= 0) return false;
    const uint64_t rowBytes = static_cast<uint64_t>(width) * 2u;
    if (static_cast<uint64_t>(rowStride) < rowBytes) return false;
    if (capacity <= 0) return true;
    const uint64_t need = static_cast<uint64_t>(height - 1) * static_cast<uint64_t>(rowStride) + rowBytes;
    return static_cast<uint64_t>(capacity) >= need;
}

} // namespace graffitixr
