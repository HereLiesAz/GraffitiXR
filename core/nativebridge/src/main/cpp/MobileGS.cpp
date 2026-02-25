#include "MobileGS.h"
#include <android/log.h>
#include <fstream>
#include <ctime>
#include <opencv2/imgproc.hpp>
#include <GLES3/gl3.h>

#define TAG "MobileGS"
#if defined(NDEBUG)
#define LOGI(...)
#define LOGE(...)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

static void multiplyMatricesInternal(const float* a, const float* b, float* result) {
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float sum = 0.0f;
            for (int k = 0; k < 4; ++k) {
                sum += a[row + k * 4] * b[k + col * 4];
            }
            result[row + col * 4] = sum;
        }
    }
}

MobileGS::MobileGS() {
    vulkanRenderer = new VulkanBackend();
    std::fill(std::begin(viewMtx), std::end(viewMtx), 0.0f);
    std::fill(std::begin(projMtx), std::end(projMtx), 0.0f);
    std::fill(std::begin(alignmentMtx), std::end(alignmentMtx), 0.0f);
    viewMtx[0] = viewMtx[5] = viewMtx[10] = viewMtx[15] = 1.0f;
    projMtx[0] = projMtx[5] = projMtx[10] = projMtx[15] = 1.0f;
    alignmentMtx[0] = alignmentMtx[5] = alignmentMtx[10] = alignmentMtx[15] = 1.0f;
}

MobileGS::~MobileGS() {
    if (vulkanRenderer) { delete vulkanRenderer; vulkanRenderer = nullptr; }
}

void MobileGS::initialize() { isInitialized = true; }

void MobileGS::reset() {
    isInitialized = false;
    mVoxelGrid.clear();
    if (vulkanRenderer) vulkanRenderer->destroy();
}

void MobileGS::onSurfaceChanged(int width, int height) {
    viewportWidth = width;
    viewportHeight = height;
    glViewport(0, 0, width, height);
    if (vulkanRenderer) vulkanRenderer->resize(width, height);
}

void MobileGS::draw() {
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    if (!isInitialized) return;
    if (vulkanRenderer) {
        vulkanRenderer->setLighting(lightIntensity, lightColor);
        vulkanRenderer->renderFrame();
    }
}

bool MobileGS::initVulkan(ANativeWindow* window, AAssetManager* mgr) { return vulkanRenderer ? vulkanRenderer->initialize(window, mgr) : false; }
void MobileGS::resizeVulkan(int width, int height) { if (vulkanRenderer) vulkanRenderer->resize(width, height); }
void MobileGS::destroyVulkan() { if (vulkanRenderer) vulkanRenderer->destroy(); }

void MobileGS::updateCamera(float* view, float* proj) {
    if (view && proj) {
        float finalView[16];
        {
            std::lock_guard<std::mutex> lock(alignMutex);
            multiplyMatricesInternal(view, alignmentMtx, finalView);
        }
        std::copy(finalView, finalView + 16, viewMtx);
        std::copy(proj, proj + 16, projMtx);
        if (vulkanRenderer) vulkanRenderer->updateCamera(viewMtx, projMtx);
    }
}

void MobileGS::alignMap(float* transform) {
    if (transform) {
        std::lock_guard<std::mutex> lock(alignMutex);
        std::copy(transform, transform + 16, alignmentMtx);
    }
}

void MobileGS::updateLight(float intensity, float* colorCorrection) {
    lightIntensity = intensity;
    if (colorCorrection) {
        lightColor[0] = colorCorrection[0]; lightColor[1] = colorCorrection[1]; lightColor[2] = colorCorrection[2];
    }
}

// FIX: Actually unprojects the depth buffer and maps it to the Voxel Grid
void MobileGS::processDepthData(uint8_t* depthBuffer, int width, int height) {
    if (!depthBuffer || width <= 0 || height <= 0) return;

    std::lock_guard<std::mutex> lock(pointMutex);

    // Approximate intrinsics from projection matrix
    float fx = projMtx[0] * (width / 2.0f);
    float fy = projMtx[5] * (height / 2.0f);
    float cx = width / 2.0f;
    float cy = height / 2.0f;

    // Subsample to preserve battery and CPU
    int subsample = 8;
    for (int y = 0; y < height; y += subsample) {
        for (int x = 0; x < width; x += subsample) {
            int index = (y * width + x) * 2; // 16-bit depth
            uint16_t d16 = depthBuffer[index] | (depthBuffer[index + 1] << 8);
            if (d16 == 0 || d16 > 6500) continue; // Out of bounds

            float z = d16 / 1000.0f;
            float px = (x - cx) * z / fx;
            float py = (y - cy) * z / fy;

            // Camera space to World Space (Simplified inverse approximation for speed)
            float wx = px * viewMtx[0] + py * viewMtx[1] + z * viewMtx[2] + viewMtx[3];
            float wy = px * viewMtx[4] + py * viewMtx[5] + z * viewMtx[6] + viewMtx[7];
            float wz = px * viewMtx[8] + py * viewMtx[9] + z * viewMtx[10] + viewMtx[11];

            VoxelKey key = { (int)(wx / VOXEL_SIZE), (int)(wy / VOXEL_SIZE), (int)(wz / VOXEL_SIZE) };

            if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
                mVoxelGrid[key] = { wx, wy, wz, 1.0f, 1.0f, 1.0f, 1.0f, 0.1f };
            } else {
                mVoxelGrid[key].confidence = std::min(1.0f, mVoxelGrid[key].confidence + 0.05f);
            }
        }
    }
}

void MobileGS::addStereoPoints(const std::vector<cv::Point3f>& points) {
    std::lock_guard<std::mutex> lock(pointMutex);
    for (const auto& p : points) {
        VoxelKey key = { (int)(p.x / VOXEL_SIZE), (int)(p.y / VOXEL_SIZE), (int)(p.z / VOXEL_SIZE) };
        if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
            mVoxelGrid[key] = { p.x, p.y, p.z, 0.0f, 1.0f, 1.0f, 1.0f, 0.1f };
        }
    }
}

void MobileGS::setVisualizationMode(int mode) { visMode = mode; }

// FIX: Implement correct Binary Serialization corresponding to docs/data_formats.md
bool MobileGS::saveMap(const char* path) {
    std::lock_guard<std::mutex> lock(pointMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;

    // Header
    out.write("GXRM", 4);
    int version = 1;
    out.write((char*)&version, 4);

    // Splat Count
    int splatCount = mVoxelGrid.size();
    out.write((char*)&splatCount, 4);

    // Keyframe Count (Fixed at 1 for now to store active tracking offset)
    int keyframeCount = 1;
    out.write((char*)&keyframeCount, 4);

    // Data payload
    for (const auto& pair : mVoxelGrid) {
        out.write((char*)&pair.second, sizeof(SplatPoint)); // 32 bytes aligned
    }

    // Write alignment matrix
    out.write((char*)alignmentMtx, 16 * sizeof(float));

    out.close();
    LOGI("Successfully saved map with %d splats.", splatCount);
    return true;
}

bool MobileGS::loadMap(const char* path) {
    std::lock_guard<std::mutex> lock(pointMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    char magic[4];
    in.read(magic, 4);
    if (strncmp(magic, "GXRM", 4) != 0) return false;

    int version, splatCount, keyframeCount;
    in.read((char*)&version, 4);
    in.read((char*)&splatCount, 4);
    in.read((char*)&keyframeCount, 4);

    mVoxelGrid.clear();
    for (int i = 0; i < splatCount; i++) {
        SplatPoint p;
        in.read((char*)&p, sizeof(SplatPoint));
        VoxelKey key = { (int)(p.x / VOXEL_SIZE), (int)(p.y / VOXEL_SIZE), (int)(p.z / VOXEL_SIZE) };
        mVoxelGrid[key] = p;
    }

    if (keyframeCount > 0) {
        in.read((char*)alignmentMtx, 16 * sizeof(float));
    }

    in.close();
    LOGI("Successfully loaded map with %d splats.", splatCount);
    return true;
}

bool MobileGS::saveKeyframe(const char* path) { return true; }