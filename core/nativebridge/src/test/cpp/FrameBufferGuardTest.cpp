// Pins the JNI frame-buffer bounds arithmetic (include/FrameBufferGuard.h) used by
// nativeFeedYuvFrame, nativeFeedColorFrame and nativeYuvToRgbaBitmap, plus the DEPTH16 check in
// nativeSetWallFingerprint / nativeSetArtworkFingerprint. Expected byte counts are
// written out by hand, not recomputed with the guard's own formula.
#include <gtest/gtest.h>
#include "FrameBufferGuard.h"

using graffitixr::packedFrameFits;
using graffitixr::plane16Fits;
using graffitixr::planeFits;

TEST(FrameBufferGuard, YPlaneNeedsLastRowOnlyToWidthNotStride) {
    // 640x480, stride 704: (480-1)*704 + 640 = 337856 bytes. The final row's padding is not read.
    EXPECT_TRUE(planeFits(337856, 640, 480, 704));
    EXPECT_FALSE(planeFits(337855, 640, 480, 704));
}

TEST(FrameBufferGuard, ColorFrameNeedsEveryRgbaByte) {
    // 640x480 RGBA = 1228800 bytes.
    EXPECT_TRUE(packedFrameFits(1228800, 640, 480, 4));
    EXPECT_FALSE(packedFrameFits(1228799, 640, 480, 4));
}

TEST(FrameBufferGuard, UnknownCapacityIsAcceptedAsBefore) {
    EXPECT_TRUE(planeFits(0, 640, 480, 640));
    EXPECT_TRUE(planeFits(-1, 640, 480, 640));
    EXPECT_TRUE(packedFrameFits(0, 640, 480, 4));
}

TEST(FrameBufferGuard, DegenerateGeometryIsRefusedEvenWithUnknownCapacity) {
    EXPECT_FALSE(planeFits(0, 0, 480, 640));
    EXPECT_FALSE(planeFits(0, 640, 0, 640));
    EXPECT_FALSE(planeFits(0, 640, 480, 639));   // stride narrower than a row
    EXPECT_FALSE(packedFrameFits(0, -1, 480, 4));
    EXPECT_FALSE(packedFrameFits(0, 640, 480, 0));
}

TEST(FrameBufferGuard, NoWrapOnHugeDimensions) {
    // 65536 x 65536 x 4 = 17179869184 bytes: wraps 32-bit size_t to 0, which a 32-bit build of the
    // old inline arithmetic would have compared against and passed.
    EXPECT_FALSE(packedFrameFits(1LL << 20, 65536, 65536, 4));
    EXPECT_TRUE(packedFrameFits(17179869184LL, 65536, 65536, 4));
}

TEST(FrameBufferGuard, Depth16NeedsTwoBytesPerSampleToTheLastSample) {
    // ARCore 160x120 DEPTH16, stride 320: (120-1)*320 + 160*2 = 38400 bytes.
    EXPECT_TRUE(plane16Fits(38400, 160, 120, 320));
    EXPECT_FALSE(plane16Fits(38399, 160, 120, 320));
    // Padded stride 352: the final row still stops at its 160th sample. (119*352 + 320 = 42208)
    EXPECT_TRUE(plane16Fits(42208, 160, 120, 352));
    EXPECT_FALSE(plane16Fits(42207, 160, 120, 352));
    // A buffer sized as if samples were one byte (the 8-bit planeFits answer) is too short.
    EXPECT_FALSE(plane16Fits(119 * 320 + 160, 160, 120, 320));
}

TEST(FrameBufferGuard, Depth16RefusesStrideNarrowerThanTwoBytesPerSample) {
    EXPECT_FALSE(plane16Fits(0, 160, 120, 160));   // stride counted in samples, not bytes
    EXPECT_FALSE(plane16Fits(0, 160, 120, 319));
    EXPECT_TRUE(plane16Fits(0, 160, 120, 320));    // unknown capacity accepted as elsewhere
    EXPECT_FALSE(plane16Fits(0, 0, 120, 320));
    EXPECT_FALSE(plane16Fits(0, 160, 0, 320));
}
