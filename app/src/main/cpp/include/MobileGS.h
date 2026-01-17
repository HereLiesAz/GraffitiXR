#pragma once

#include <vector>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <opencv2/core/mat.hpp>

struct SplatGaussian {
    glm::vec3 position;
    glm::vec3 color;
    glm::vec3 scale;
    float opacity;
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
    void addGaussians(const std::vector<SplatGaussian>& gaussians);

    // New: Handle dense depth map
    void processDepthFrame(const cv::Mat& depthMap, int width, int height);
    void setBackgroundFrame(const cv::Mat& frame);

    void draw();
    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);
    void clear();

    glm::mat4 getViewMatrix() const { return mViewMatrix; }
    glm::mat4 getProjMatrix() const { return mProjMatrix; }

private:
    void compileShaders();
    void sortThreadLoop();
    void uploadBgTexture();
    void drawBackground();

    GLuint mProgram;
    GLuint mVAO, mVBO;
    GLuint mBgProgram;
    GLuint mBgVAO, mBgVBO;
    GLuint mBgTexture;

    glm::mat4 mViewMatrix;
    glm::mat4 mProjMatrix;
    glm::mat4 mSortViewMatrix;

    std::vector<SplatGaussian> mRenderGaussians;
    std::vector<SplatGaussian> mIncomingGaussians;
    cv::Mat mPendingBgFrame;

    std::vector<Sortable> mSortListFront;
    std::vector<Sortable> mSortListBack;
    std::thread mSortThread;
    std::mutex mSortMutex;
    std::condition_variable mSortCV;
    bool mSortRunning;
    bool mStopThread;
    bool mSortResultReady;

    std::mutex mDataMutex;
    bool mNewDataAvailable;
    std::mutex mBgMutex;
    bool mNewBgAvailable;
    bool mIsInitialized;

    // Throttling for density generation
    int mFrameCounter;
};