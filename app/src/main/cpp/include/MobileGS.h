#pragma once

#include <vector>
#include <string>
#include <mutex>
#include <thread>
#include <atomic>
#include <condition_variable>
#include <map>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <opencv2/core.hpp>

// Constants inferred from usage
const int MAX_POINTS = 500000;
const float VOXEL_SIZE = 0.05f;

struct SplatGaussian {
    glm::vec3 position;
    glm::vec3 scale;
    glm::vec3 color;
    float opacity;
};

struct VoxelKey {
    int x, y, z;
    bool operator<(const VoxelKey& other) const {
        if (x != other.x) return x < other.x;
        if (y != other.y) return y < other.y;
        return z < other.z;
    }
};

struct Sortable {
    int index;
    float depth;
};

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void initialize();
    void updateCamera(const float* viewMtx, const float* projMtx);
    void processDepthFrame(const cv::Mat& depthMap, int width, int height);
    void processImage(const cv::Mat& image, int width, int height, int64_t timestamp);
    void draw();
    void clear();
    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);
    int getPointCount();

private:
    void sortThreadLoop();
    void compileShaders();
    void pruneMap();
    void setBackgroundFrame(const cv::Mat& frame);

    std::mutex mDataMutex;
    std::mutex mBgMutex;
    std::mutex mSortMutex;
    std::condition_variable mSortCV;
    std::thread mSortThread;
    std::atomic<bool> mStopThread;
    std::atomic<bool> mSortRunning;
    std::atomic<bool> mSortResultReady;
    bool mMapChanged;
    bool mIsInitialized;

    std::vector<SplatGaussian> mRenderGaussians;
    std::map<VoxelKey, int> mVoxelGrid;

    glm::mat4 mViewMatrix;
    glm::mat4 mProjMatrix;
    glm::mat4 mSortViewMatrix;

    GLuint mProgram;
    GLuint mVAO, mVBO, mQuadVBO;
    GLuint mBgProgram, mBgVAO, mBgVBO, mBgTexture;

    std::chrono::steady_clock::time_point mLastUpdateTime;

    bool mNewBgAvailable;
    bool mHasBgData;
    cv::Mat mPendingBgFrame;

    std::vector<Sortable> mSortListBack;
    std::vector<Sortable> mSortListFront;
};
