// ~~~ FILE: ./core/nativebridge/src/main/cpp/MobileGS.cpp ~~~
#include "include/MobileGS.h"
#include <android/log.h>
#include <fstream>
#include <algorithm>
#include <cmath>

#define LOG_TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const int   MAX_SPLATS  = 500000;
static const float VOXEL_SIZE  = 0.02f;  // 20mm world-space voxel grid
static const float MIN_DEPTH   = 0.1f;   // metres
static const float MAX_DEPTH   = 5.0f;   // metres

static const char  GXRM_MAGIC[4]  = {'G', 'X', 'R', 'M'};
static const int   FORMAT_VERSION = 1;

MobileGS::MobileGS() : mIsRunning(true), mNeedsResort(false) {
    std::fill(std::begin(mViewMatrix), std::end(mViewMatrix), 0.0f);
    std::fill(std::begin(mProjMatrix), std::end(mProjMatrix), 0.0f);
    mViewMatrix[0] = mViewMatrix[5] = mViewMatrix[10] = mViewMatrix[15] = 1.0f;
    mProjMatrix[0] = mProjMatrix[5] = mProjMatrix[10] = mProjMatrix[15] = 1.0f;

    std::fill(std::begin(mAnchorMatrix), std::end(mAnchorMatrix), 0.0f);
    mAnchorMatrix[0] = mAnchorMatrix[5] = mAnchorMatrix[10] = mAnchorMatrix[15] = 1.0f;

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
    // Apply anchor transform: EffectiveView = View * Anchor
    // Note: Assuming column-major order for OpenGL-style matrices
    float effectiveView[16];

    // Matrix multiplication: effectiveView = viewMatrix * mAnchorMatrix
    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 4; ++c) {
            effectiveView[c * 4 + r] = 0.0f;
            for (int k = 0; k < 4; ++k) {
                effectiveView[c * 4 + r] += viewMatrix[k * 4 + r] * mAnchorMatrix[c * 4 + k];
            }
        }
    }

    std::copy(effectiveView, effectiveView + 16, mViewMatrix);
    std::copy(projMatrix, projMatrix + 16, mProjMatrix);
}

void MobileGS::updateAnchorTransform(const float* transformMatrix) {
    std::copy(transformMatrix, transformMatrix + 16, mAnchorMatrix);
}

void MobileGS::processDepthFrame(const cv::Mat& depthMap, const cv::Mat& colorFrame) {
    if (depthMap.empty() || colorFrame.empty()) return;
    if (depthMap.type() != CV_32F) {
        LOGE("processDepthFrame: depthMap must be CV_32F"); return;
    }
    if (colorFrame.type() != CV_8UC3) {
        LOGE("processDepthFrame: colorFrame must be CV_8UC3"); return;
    }
    if (depthMap.size() != colorFrame.size()) {
        LOGE("processDepthFrame: size mismatch"); return;
    }

    std::lock_guard<std::mutex> lock(mDataMutex);

    const int W = depthMap.cols;
    const int H = depthMap.rows;
    // proj[0] = proj[0][0] (col-major), proj[5] = proj[1][1]
    const float invFx = 1.0f / mProjMatrix[0];
    const float invFy = 1.0f / mProjMatrix[5];

    // Precompute inv(mViewMatrix) translation part (rigid body)
    const float* V = mViewMatrix;
    const float invTx = -(V[0]*V[12] + V[1]*V[13] + V[2]*V[14]);
    const float invTy = -(V[4]*V[12] + V[5]*V[13] + V[6]*V[14]);
    const float invTz = -(V[8]*V[12] + V[9]*V[13] + V[10]*V[14]);

    for (int y = 0; y < H; y += 4) {
        for (int x = 0; x < W; x += 4) {
            if ((int)mGaussians.size() >= MAX_SPLATS) goto done;

            float depth = depthMap.at<float>(y, x);
            if (depth < MIN_DEPTH || depth > MAX_DEPTH) continue;

            // Unproject pixel → view space (OpenGL -Z forward)
            float ndcX =  (2.0f * x / (float)W) - 1.0f;
            float ndcY = -(2.0f * y / (float)H) + 1.0f;
            float vx = ndcX * depth * invFx;
            float vy = ndcY * depth * invFy;
            float vz = -depth;

            // View space → world space: world = inv(V) * [vx,vy,vz,1]
            // mViewMatrix already has anchor correction baked in via updateCamera()
            float wx = V[0]*vx + V[1]*vy + V[2]*vz + invTx;
            float wy = V[4]*vx + V[5]*vy + V[6]*vz + invTy;
            float wz = V[8]*vx + V[9]*vy + V[10]*vz + invTz;

            VoxelKey key = {
                (int)std::floor(wx / VOXEL_SIZE),
                (int)std::floor(wy / VOXEL_SIZE),
                (int)std::floor(wz / VOXEL_SIZE)
            };

            auto it = mVoxelGrid.find(key);
            if (it != mVoxelGrid.end()) {
                // Update: average position, increment confidence
                SplatGaussian& s = mGaussians[it->second];
                s.pos[0] = s.pos[0] * 0.9f + wx * 0.1f;
                s.pos[1] = s.pos[1] * 0.9f + wy * 0.1f;
                s.pos[2] = s.pos[2] * 0.9f + wz * 0.1f;
                s.confidence = std::min(s.confidence + 0.05f, 1.0f);
                s.color[3]   = s.confidence;
                s.opacity    = s.confidence;
                mNeedsResort = true;
                continue;
            }

            cv::Vec3b color = colorFrame.at<cv::Vec3b>(y, x);
            SplatGaussian splat{};
            splat.pos[0] = wx;
            splat.pos[1] = wy;
            splat.pos[2] = wz;
            splat.scale[0] = splat.scale[1] = splat.scale[2] = VOXEL_SIZE;
            splat.rot[3] = 1.0f; // identity quaternion
            splat.color[0] = color[2] / 255.0f; // BGR → RGB
            splat.color[1] = color[1] / 255.0f;
            splat.color[2] = color[0] / 255.0f;
            splat.confidence = 0.05f;
            splat.color[3]   = splat.confidence;
            splat.opacity    = splat.confidence;

            mVoxelGrid[key] = mGaussians.size();
            mGaussians.push_back(splat);
            mNeedsResort = true;
        }
    }
    done:;
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

    file.write(GXRM_MAGIC, 4);
    file.write(reinterpret_cast<const char*>(&FORMAT_VERSION), sizeof(int));

    int32_t count = static_cast<int32_t>(mGaussians.size());
    file.write(reinterpret_cast<const char*>(&count), sizeof(int32_t));
    if (count > 0) {
        file.write(reinterpret_cast<const char*>(mGaussians.data()),
                   count * sizeof(SplatGaussian));
    }
    file.write(reinterpret_cast<const char*>(mAnchorMatrix), 16 * sizeof(float));
    return file.good();
}

bool MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) return false;

    char magic[4] = {};
    file.read(magic, 4);
    if (magic[0]!='G' || magic[1]!='X' || magic[2]!='R' || magic[3]!='M') {
        LOGE("loadModel: invalid magic header"); return false;
    }
    int version = 0;
    file.read(reinterpret_cast<char*>(&version), sizeof(int));
    if (version != FORMAT_VERSION) {
        LOGE("loadModel: unsupported version %d", version); return false;
    }
    int32_t count = 0;
    file.read(reinterpret_cast<char*>(&count), sizeof(int32_t));
    if (count < 0 || count > MAX_SPLATS) {
        LOGE("loadModel: invalid splat count %d", count); return false;
    }
    mGaussians.resize(static_cast<size_t>(count));
    if (count > 0) {
        file.read(reinterpret_cast<char*>(mGaussians.data()),
                  count * sizeof(SplatGaussian));
    }
    file.read(reinterpret_cast<char*>(mAnchorMatrix), 16 * sizeof(float));

    // Rebuild voxel grid index from loaded splats
    mVoxelGrid.clear();
    for (size_t i = 0; i < mGaussians.size(); i++) {
        const auto& s = mGaussians[i];
        VoxelKey key = {
            (int)std::floor(s.pos[0] / VOXEL_SIZE),
            (int)std::floor(s.pos[1] / VOXEL_SIZE),
            (int)std::floor(s.pos[2] / VOXEL_SIZE)
        };
        mVoxelGrid[key] = i;
    }
    mNeedsResort = true;
    return file.good();
}

std::vector<SplatGaussian>& MobileGS::getSplats() {
    return mGaussians;
}

std::mutex& MobileGS::getMutex() {
    return mDataMutex;
}