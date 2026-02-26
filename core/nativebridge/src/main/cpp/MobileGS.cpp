// ~~~ FILE: ./core/nativebridge/src/main/cpp/MobileGS.cpp ~~~
#include "include/MobileGS.h"
#include <android/log.h>
#include <fstream>
#include <algorithm>
#include <cmath>

#define LOG_TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

MobileGS::MobileGS() : mIsRunning(true), mNeedsResort(false) {
    std::fill(std::begin(mViewMatrix), std::end(mViewMatrix), 0.0f);
    std::fill(std::begin(mProjMatrix), std::end(mProjMatrix), 0.0f);
    mViewMatrix[0] = mViewMatrix[5] = mViewMatrix[10] = mViewMatrix[15] = 1.0f;
    mProjMatrix[0] = mProjMatrix[5] = mProjMatrix[10] = mProjMatrix[15] = 1.0f;

    mSortThread = std::thread(&MobileGS::sortThreadLoop, this);
}

MobileGS::~MobileGS() {
    mIsRunning = false;
    if (mSortThread.joinable()) mSortThread.join();
}

void MobileGS::initialize(int width, int height) {
    LOGI("MobileGS initialized for %dx%d", width, height);
}

void MobileGS::updateCamera(const float* viewMatrix, const float* projMatrix) {
    std::copy(viewMatrix, viewMatrix + 16, mViewMatrix);
    std::copy(projMatrix, projMatrix + 16, mProjMatrix);
}

void MobileGS::processDepthFrame(const cv::Mat& depthMap, const cv::Mat& colorFrame) {
    std::lock_guard<std::mutex> lock(mDataMutex);

    for (int y = 0; y < depthMap.rows; y += 4) {
        for (int x = 0; x < depthMap.cols; x += 4) {
            float depth = depthMap.at<float>(y, x);
            if (depth <= 0.1f || depth > 5.0f) continue;

            VoxelKey key = { (int)(x/10), (int)(y/10), (int)(depth*100) };
            if (mVoxelGrid.find(key) != mVoxelGrid.end()) continue;

            SplatGaussian splat;
            splat.pos[0] = (float)x;
            splat.pos[1] = (float)y;
            splat.pos[2] = depth;

            cv::Vec3b color = colorFrame.at<cv::Vec3b>(y, x);
            splat.color[0] = color[2] / 255.0f;
            splat.color[1] = color[1] / 255.0f;
            splat.color[2] = color[0] / 255.0f;
            splat.color[3] = 1.0f;

            splat.scale[0] = splat.scale[1] = splat.scale[2] = 0.01f;
            splat.opacity = 1.0f;

            mGaussians.push_back(splat);
            mVoxelGrid[key] = mGaussians.size() - 1;
            mNeedsResort = true;
        }
    }
}

void MobileGS::sortThreadLoop() {
    while (mIsRunning) {
        if (mNeedsResort) {
            std::lock_guard<std::mutex> lock(mDataMutex);
            std::sort(mGaussians.begin(), mGaussians.end(), [this](const SplatGaussian& a, const SplatGaussian& b) {
                return a.pos[2] > b.pos[2];
            });
            mNeedsResort = false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
}

bool MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    std::ofstream file(path, std::ios::binary);
    if (!file.is_open()) return false;

    size_t count = mGaussians.size();
    file.write(reinterpret_cast<char*>(&count), sizeof(count));
    file.write(reinterpret_cast<char*>(mGaussians.data()), count * sizeof(SplatGaussian));
    return true;
}

bool MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) return false;

    size_t count;
    file.read(reinterpret_cast<char*>(&count), sizeof(count));
    mGaussians.resize(count);
    file.read(reinterpret_cast<char*>(mGaussians.data()), count * sizeof(SplatGaussian));
    return true;
}

std::vector<SplatGaussian>& MobileGS::getSplats() {
    return mGaussians;
}

std::mutex& MobileGS::getMutex() {
    return mDataMutex;
}