// SuperPointDetector input geometry: aspect-preserving resize + stride padding. (The bundled
// superpoint.onnx uses an ONNX Clip form the host's OpenCV 4.6 dnn importer rejects, so the model
// itself is not run here; the geometry is what decides where keypoints map back to.)
#include <gtest/gtest.h>
#include <opencv2/opencv.hpp>
#include "SuperPointDetector.h"

using G = SuperPointDetector::InputGeometry;

TEST(SuperPointGeometry, LargeInputsKeepTheirAspect) {
    G g = SuperPointDetector::inputGeometry(1920, 1080);
    EXPECT_TRUE(g.resized);
    EXPECT_EQ(640, g.scaledW); EXPECT_EQ(360, g.scaledH);
    EXPECT_EQ(640, g.netW); EXPECT_EQ(360, g.netH);
    EXPECT_FLOAT_EQ(3.0f, g.scaleX); EXPECT_FLOAT_EQ(3.0f, g.scaleY);

    g = SuperPointDetector::inputGeometry(1080, 1920);   // portrait: NOT squashed to 640x480
    EXPECT_EQ(360, g.scaledW); EXPECT_EQ(640, g.scaledH);
    EXPECT_FLOAT_EQ(g.scaleX, g.scaleY);

    g = SuperPointDetector::inputGeometry(480, 640);     // portrait at the cap: untouched
    EXPECT_FALSE(g.resized);
    EXPECT_EQ(480, g.netW); EXPECT_EQ(640, g.netH);
    EXPECT_FLOAT_EQ(1.0f, g.scaleX); EXPECT_FLOAT_EQ(1.0f, g.scaleY);
}

TEST(SuperPointGeometry, NonMultipleOfEightIsPaddedNotStretched) {
    G g = SuperPointDetector::inputGeometry(100, 75);
    EXPECT_FALSE(g.resized);
    EXPECT_EQ(100, g.scaledW); EXPECT_EQ(75, g.scaledH);
    EXPECT_EQ(104, g.netW); EXPECT_EQ(80, g.netH);
    EXPECT_FLOAT_EQ(1.0f, g.scaleX); EXPECT_FLOAT_EQ(1.0f, g.scaleY);

    g = SuperPointDetector::inputGeometry(1000, 751);
    EXPECT_EQ(640, g.scaledW); EXPECT_EQ(481, g.scaledH);
    EXPECT_EQ(640, g.netW); EXPECT_EQ(488, g.netH);
    EXPECT_NEAR(g.scaleX, g.scaleY, 0.005f) << "per-axis scales differ only by pixel rounding";
}
