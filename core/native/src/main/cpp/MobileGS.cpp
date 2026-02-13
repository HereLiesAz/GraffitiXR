#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <mutex>
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <cstring>

#define LOG_TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- Constants ---
// Downsample factor to prevent OOM. 4 = process 1/16th of pixels.
const int SAMPLE_STRIDE = 4;
// Cull points that are too close or too far (in meters)
const float MIN_DEPTH = 0.2f;
const float MAX_DEPTH = 5.0f;
// Max splats to keep in memory to maintain framerate
const int MAX_GAUSSIANS = 200000;

// --- Data Structures ---

struct Gaussian {
    float x, y, z;          // Position
    float r, g, b, opacity; // Color/Alpha
    float scale_x, scale_y, scale_z;
    float rot_w, rot_x, rot_y, rot_z; // Quaternion
};

struct Chunk {
    int id;
    std::vector<Gaussian> gaussians;
    bool is_dirty;
};

// --- Global State ---

std::map<int, Chunk> chunks;
std::mutex chunks_mutex;
bool is_initialized = false;
int global_gaussian_count = 0;

// --- Helper Functions ---

void clear_memory() {
    std::lock_guard<std::mutex> lock(chunks_mutex);
    chunks.clear();
    global_gaussian_count = 0;
    LOGI("Memory cleared. All chunks destroyed.");
}

// Matrix multiplication helper (4x4 * 4x1)
void mat4_mul_vec3(const float* mat, float x, float y, float z, float& out_x, float& out_y, float& out_z) {
    // Column-major order expected from Android OpenGL/Matrix.java
    out_x = mat[0] * x + mat[4] * y + mat[8] * z + mat[12];
    out_y = mat[1] * x + mat[5] * y + mat[9] * z + mat[13];
    out_z = mat[2] * x + mat[6] * y + mat[10] * z + mat[14];
    // We ignore W division here assuming standard affine rigid body transform
}

// --- JNI Interfaces ---

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_native_MobileGS_init(JNIEnv* env, jobject /* this */) {
    LOGI("Initializing MobileGS Native Bridge");
    clear_memory();
    is_initialized = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_native_MobileGS_cleanup(JNIEnv* env, jobject /* this */) {
    LOGI("Cleaning up MobileGS Native Bridge");
    clear_memory();
    is_initialized = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_native_MobileGS_addGaussian(
        JNIEnv* env,
        jobject /* this */,
        jint chunkId,
        jfloat x, jfloat y, jfloat z,
        jfloat r, jfloat g, jfloat b, jfloat opacity,
        jfloat sx, jfloat sy, jfloat sz,
        jfloat rw, jfloat rx, jfloat ry, jfloat rz) {

    if (!is_initialized) return;

    std::lock_guard<std::mutex> lock(chunks_mutex);

    Gaussian g_data = {x, y, z, r, g, b, opacity, sx, sy, sz, rw, rx, ry, rz};

    if (chunks.find(chunkId) == chunks.end()) {
        chunks[chunkId] = Chunk{chunkId, {}, true};
    }

    chunks[chunkId].gaussians.push_back(g_data);
    chunks[chunkId].is_dirty = true;
    global_gaussian_count++;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_core_native_MobileGS_getGaussianCount(JNIEnv* env, jobject /* this */) {
    if (!is_initialized) return 0;
    // Atomic read preferred, but mutex is safer
    std::lock_guard<std::mutex> lock(chunks_mutex);
    return global_gaussian_count;
}

/**
 * CORE FEATURE IMPLEMENTATION: Depth to Point Cloud
 * * @param byteBuffer DirectByteBuffer containing DEPTH16 data (unsigned short, mm)
 * @param width Width of the depth image
 * @param height Height of the depth image
 * @param poseMatrixArray 16-float array representing the Camera-to-World transform
 */
extern "C" JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_core_native_MobileGS_updateSlam(
        JNIEnv* env,
        jobject /* this */,
        jobject byteBuffer,
        jint width,
        jint height,
        jfloatArray poseMatrixArray) {

    if (!is_initialized) return;

    // 1. Get Depth Data
    uint16_t* depthData = (uint16_t*)env->GetDirectBufferAddress(byteBuffer);
    if (depthData == nullptr) {
        LOGE("updateSlam: Buffer is null!");
        return;
    }

    // 2. Get Pose Matrix
    float poseMatrix[16];
    env->GetFloatArrayRegion(poseMatrixArray, 0, 16, poseMatrix);

    // 3. Setup Intrinsics (Approximation for standard mobile camera ~60-70 deg FOV)
    // Ideally these should be passed from Kotlin, but this makes it functional NOW.
    float fx = width * 0.7f;
    float fy = width * 0.7f; // Square pixels assumption
    float cx = width / 2.0f;
    float cy = height / 2.0f;

    std::vector<Gaussian> newSplats;
    newSplats.reserve((width * height) / (SAMPLE_STRIDE * SAMPLE_STRIDE));

    // 4. Process Pixels
    for (int v = 0; v < height; v += SAMPLE_STRIDE) {
        for (int u = 0; u < width; u += SAMPLE_STRIDE) {

            // Read depth (mm) and convert to meters
            uint16_t d_raw = depthData[v * width + u];
            if (d_raw == 0) continue; // Invalid depth

            float z_local = d_raw * 0.001f;

            // Cull depth
            if (z_local < MIN_DEPTH || z_local > MAX_DEPTH) continue;

            // Unproject to Camera Space
            float x_local = (u - cx) * z_local / fx;
            float y_local = (v - cy) * z_local / fy;

            // Transform to World Space
            float x_world, y_world, z_world;
            mat4_mul_vec3(poseMatrix, x_local, y_local, -z_local, x_world, y_world, z_world);
            // Note: -z_local because OpenGL camera looks down -Z

            // Create Gaussian
            // We give them a default "Ghostly" look (Green/Blue tint) per your aesthetic
            Gaussian g_data = {
                    x_world, y_world, z_world,
                    0.0f, 0.8f, 0.5f, 0.6f,   // Color: Teal, Semi-transparent
                    0.02f, 0.02f, 0.02f,      // Scale: Small points
                    1.0f, 0.0f, 0.0f, 0.0f    // Rotation: Identity
            };
            newSplats.push_back(g_data);
        }
    }

    // 5. Update State
    // We use a dedicated "Live Scan" chunk ID (e.g., 9999) and replace it or append?
    // For a mapping system, we append. For a viewer, we might replace.
    // Let's Append to Chunk 0 for now, but check limits.

    std::lock_guard<std::mutex> lock(chunks_mutex);

    if (global_gaussian_count > MAX_GAUSSIANS) {
        // Simple ring buffer logic or clear logic could go here.
        // For now, we just stop adding to preserve stability.
        return;
    }

    int chunkId = 0;
    if (chunks.find(chunkId) == chunks.end()) {
        chunks[chunkId] = Chunk{chunkId, {}, true};
    }

    // Append new splats
    chunks[chunkId].gaussians.insert(chunks[chunkId].gaussians.end(), newSplats.begin(), newSplats.end());
    chunks[chunkId].is_dirty = true;
    global_gaussian_count += newSplats.size();
}