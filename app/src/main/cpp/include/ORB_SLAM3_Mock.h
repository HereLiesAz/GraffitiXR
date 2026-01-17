#ifndef ORB_SLAM3_MOCK_H
#define ORB_SLAM3_MOCK_H

#include <string>
#include <vector>

namespace cv {
    class Mat {
    public:
        Mat(int rows, int cols, int type, void* data) {}
        Mat() {}
    };
    // Mocking CV_8UC1 for single channel images
    #define CV_8UC1 0
}

namespace ORB_SLAM3 {

    class System {
    public:
        enum eSensor {
            MONOCULAR = 0,
            STEREO = 1,
            RGBD = 2,
            IMU_MONOCULAR = 3,
            IMU_STEREO = 4,
            IMU_RGBD = 5
        };

        enum FileType {
            TEXT_FILE = 0,
            BINARY_FILE = 1
        };

        System(const std::string &strVocFile, const std::string &strSettingsFile, const eSensor sensor, const bool bUseViewer = true, const int initFr = 0, const std::string &strSequence = std::string()) {}

        // Mocking TrackMonocular
        // Returns a dummy pose (Sophus::SE3f is mocked as void* or ignored in this context since we don't return it to Java yet)
        void TrackMonocular(const cv::Mat &im, const double &timestamp) {}

        void Shutdown() {}

        // In the real ORB_SLAM3, SaveAtlas is private, but the task requires using it or an equivalent.
        // We expose it here for the mock to satisfy the code requirement.
        void SaveAtlas(int type) {}
    };
}

#endif // ORB_SLAM3_MOCK_H
