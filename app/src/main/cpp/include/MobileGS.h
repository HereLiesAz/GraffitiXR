#include "include/MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <fstream>

#define TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

const float CONFIDENCE_THRESHOLD = 0.6f;
const float CONFIDENCE_INCREMENT = 0.05f;

// ... (Shaders remain the same) ...

MobileGS::MobileGS() :
        mProgram(0), mVAO(0), mVBO(0), mQuadVBO(0),
        mViewMatrix(1.0f), mProjMatrix(1.0f), mSortViewMatrix(1.0f),
        mSortRunning(false), mStopThread(false), mSortResultReady(false),
        mMapChanged(false), mIsInitialized(false)
{
    mLastUpdateTime = std::chrono::steady_clock::now();
    mSortThread = std::thread(&MobileGS::sortThreadLoop, this);
}

// ... (Destructor and initialize remain the same) ...

int MobileGS::getPointCount() {
    std::lock_guard<std::mutex> lock(mDataMutex);
    return (int)mRenderGaussians.size();
}

void MobileGS::processDepthFrame(const cv::Mat& depthMap, int width, int height) {
    // ... (Throttling logic same as before) ...
    auto now = std::chrono::steady_clock::now();
    if (std::chrono::duration_cast<std::chrono::milliseconds>(now - mLastUpdateTime).count() < 33) return;
    mLastUpdateTime = now;

    std::lock_guard<std::mutex> lock(mDataMutex);
    if (mProjMatrix[0][0] == 0) return;

    if (mRenderGaussians.size() > MAX_POINTS * 0.9) {
        pruneMap();
    }

    // ... (Matrix calculation same as before) ...
    glm::mat4 invView = glm::inverse(mViewMatrix);
    float p00 = mProjMatrix[0][0];
    float p11 = mProjMatrix[1][1];
    float p20 = mProjMatrix[2][0];
    float p21 = mProjMatrix[2][1];

    int step = 2; 
    for (int y = 0; y < height; y += step) {
        const uint16_t* rowPtr = depthMap.ptr<uint16_t>(y);
        for (int x = 0; x < width; x += step) {
            uint16_t d_raw = rowPtr[x];
            if (d_raw < 200 || d_raw > 4000) continue;

            float z = d_raw * 0.001f;
            // ... (Projection math same as before) ...
            float ndc_x = ((float)x / width) * 2.0f - 1.0f;
            float ndc_y = 1.0f - ((float)y / height) * 2.0f;
            glm::vec4 viewPos;
            viewPos.x = (ndc_x + p20) * z / p00;
            viewPos.y = (ndc_y + p21) * z / p11;
            viewPos.z = -z;
            viewPos.w = 1.0f;
            glm::vec4 worldPos = invView * viewPos;

            VoxelKey key;
            key.x = static_cast<int>(std::floor(worldPos.x / VOXEL_SIZE));
            key.y = static_cast<int>(std::floor(worldPos.y / VOXEL_SIZE));
            key.z = static_cast<int>(std::floor(worldPos.z / VOXEL_SIZE));

            auto it = mVoxelGrid.find(key);
            if (it != mVoxelGrid.end()) {
                SplatGaussian& g = mRenderGaussians[it->second];
                g.opacity = std::min(1.0f, g.opacity + CONFIDENCE_INCREMENT);
                // Simple running average for position refinement
                g.position = glm::mix(g.position, glm::vec3(worldPos), 0.1f);
            } else if (mRenderGaussians.size() < MAX_POINTS) {
                SplatGaussian g;
                g.position = glm::vec3(worldPos);
                g.scale = glm::vec3(VOXEL_SIZE * 1.8f); // Slightly overlapping
                g.opacity = CONFIDENCE_INCREMENT;
                g.color = glm::vec3(0.0f, 0.8f, 1.0f);
                mRenderGaussians.push_back(g);
                mVoxelGrid[key] = (int)(mRenderGaussians.size() - 1);
            }
        }
    }
}

void MobileGS::pruneMap() {
    // FIX: Invalidate sort when changing the vector layout
    mMapChanged = true;
    
    std::vector<SplatGaussian> survived;
    survived.reserve(mRenderGaussians.size());
    mVoxelGrid.clear();

    for (const auto& g : mRenderGaussians) {
        if (g.opacity >= CONFIDENCE_THRESHOLD) {
            survived.push_back(g);
            VoxelKey key = { 
                (int)std::floor(g.position.x/VOXEL_SIZE), 
                (int)std::floor(g.position.y/VOXEL_SIZE), 
                (int)std::floor(g.position.z/VOXEL_SIZE) 
            };
            mVoxelGrid[key] = (int)(survived.size() - 1);
        }
    }
    mRenderGaussians = std::move(survived);
    LOGI("Garbage Collection: Pruned to %zu points", mRenderGaussians.size());
}

// ... (processImage, setBackgroundFrame, compileShaders remain same) ...

void MobileGS::sortThreadLoop() {
    while (!mStopThread) {
        {
            std::unique_lock<std::mutex> lock(mSortMutex);
            mSortCV.wait(lock, [this] { return mStopThread || mSortRunning.load(); });
            if (mStopThread) return;
        }

        // ... (Sort logic same as before) ...
        std::vector<glm::vec3> positions;
        glm::mat4 view;
        {
            std::lock_guard<std::mutex> dataLock(mDataMutex);
            view = mSortViewMatrix;
            positions.reserve(mRenderGaussians.size());
            for(const auto& g : mRenderGaussians) positions.push_back(g.position);
        }

        if (!positions.empty()) {
            std::vector<Sortable> sorted;
            sorted.reserve(positions.size());
            for(size_t i=0; i<positions.size(); ++i) {
                const auto& p = positions[i];
                float depth = view[0][2] * p.x + view[1][2] * p.y + view[2][2] * p.z + view[3][2];
                sorted.push_back({(int)i, depth});
            }
            std::sort(sorted.begin(), sorted.end(), [](const Sortable& a, const Sortable& b){
                return a.depth > b.depth;
            });

            std::lock_guard<std::mutex> dataLock(mDataMutex);
            // If map changed while we were sorting, discard this result
            if (!mMapChanged) {
                mSortListBack = std::move(sorted);
                mSortResultReady = true;
            } else {
                mMapChanged = false; // Reset flag, try again next frame
            }
        }
        mSortRunning = false;
    }
}

// ... (draw, saveModel, loadModel, clear remain the same) ...
