// Pins the JNI frame-buffer bounds arithmetic (include/FrameBufferGuard.h) used by
// nativeFeedYuvFrame, nativeFeedColorFrame and nativeYuvToRgbaBitmap. Expected byte counts are
// written out by hand, not recomputed with the guard's own formula.
#include <gtest/gtest.h>
#include "FrameBufferGuard.h"

using graffitixr::packedFrameFits;
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
