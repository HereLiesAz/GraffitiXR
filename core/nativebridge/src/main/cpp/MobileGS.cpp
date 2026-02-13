#include "MobileGS.h"
#include <android/log.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <algorithm>
#include <cstring>

#define LOG_TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- Tuning Parameters (Teleology) ---
static const int MAX_MAP_POINTS = 5000;
static const float PRUNE_RADIUS_PX = 20.0f; // Pixels radius to consider "Overpainted"
static const float RATIO_THRESH = 0.75f;
static const int MIN_TARGET_MATCHES_TO_EVOLVE = 8;
static const float DEFAULT_WALL_DEPTH = 1.5f; // Meters (Assumption for monocular initialization)

// --- Constants (Splatting) ---
const int SAMPLE_STRIDE = 4;
const float MIN_DEPTH = 0.2f;
const float MAX_DEPTH = 5.0f;
const int MAX_SPLATS = 500000;

// --- Shaders ---
const char* VERTEX_SHADER = R"(#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 2) in float aOpacity;

uniform mat4 uMVP;
uniform float uPointSize;

out vec4 vColor;

void main() {
    gl_Position = uMVP * vec4(aPosition, 1.0);
    gl_PointSize = uPointSize / gl_Position.w;
    vColor = vec4(aColor, aOpacity);
}
)";

const char* FRAGMENT_SHADER = R"(#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 FragColor;

void main() {
    if (length(gl_PointCoord - vec2(0.5)) > 0.5) discard;
    FragColor = vColor;
}
)";

// --- Helper: Compile Shader ---
GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
            LOGE("Error compiling shader:\n%s", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

// --- Helper: Create Program ---
GLuint createProgram(const char* vSource, const char* fSource) {
    GLuint vShader = compileShader(GL_VERTEX_SHADER, vSource);
    GLuint fShader = compileShader(GL_FRAGMENT_SHADER, fSource);
    GLuint program = glCreateProgram();
    glAttachShader(program, vShader);
    glAttachShader(program, fShader);
    glLinkProgram(program);
    return program;
}

// --- Matrix Mul Helper ---
void mat4_mul_vec3(const float* mat, float x, float y, float z, float& out_x, float& out_y, float& out_z) {
    out_x = mat[0] * x + mat[4] * y + mat[8] * z + mat[12];
    out_y = mat[1] * x + mat[5] * y + mat[9] * z + mat[13];
    out_z = mat[2] * x + mat[6] * y + mat[10] * z + mat[14];
}

// =================================================================================================
// MobileGS Implementation
// =================================================================================================

MobileGS::MobileGS() : mIsInitialized(false), mHasTarget(false), mFrameCount(0), mProgram(0), mLocMVP(-1), mLocPointSize(-1) {
    // OpenCV Init
    mOrb = cv::ORB::create(1000);
    mMatcher = cv::DescriptorMatcher::create(cv::DescriptorMatcher::BRUTEFORCE_HAMMING);

    // Matrix Init
    std::fill(mViewMatrix, mViewMatrix + 16, 0.0f);
    std::fill(mProjMatrix, mProjMatrix + 16, 0.0f);
    mViewMatrix[0] = mViewMatrix[5] = mViewMatrix[10] = mViewMatrix[15] = 1.0f;
    mProjMatrix[0] = mProjMatrix[5] = mProjMatrix[10] = mProjMatrix[15] = 1.0f;

    LOGI("MobileGS Constructor");
}

MobileGS::~MobileGS() {
    Cleanup();
}

void MobileGS::Initialize(int width, int height) {
    mWidth = width;
    mHeight = height;
    mIsInitialized = true;
    LOGI("MobileGS Initialized: %dx%d", width, height);

    // OpenGL Init (Splatting)
    if (mProgram == 0) {
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mLocMVP = glGetUniformLocation(mProgram, "uMVP");
        mLocPointSize = glGetUniformLocation(mProgram, "uPointSize");
    }
}

void MobileGS::Cleanup() {
    // Teleology Cleanup
    std::lock_guard<std::mutex> mapLock(mMapMutex);
    mMapKeypoints.clear();
    mMapPoints3D.clear();
    mMapDescriptors.release();
    mTargetDescriptors.release();
    mIsInitialized = false;

    // Splatting Cleanup
    clear(); // Clears VBOs and chunks
    if (mProgram != 0) {
        glDeleteProgram(mProgram);
        mProgram = 0;
    }
}

// =================================================================================================
// Teleological SLAM (OpenCV)
// =================================================================================================

void MobileGS::SetTargetDescriptors(const cv::Mat& descriptors) {
    std::lock_guard<std::mutex> lock(mMapMutex);
    descriptors.copyTo(mTargetDescriptors);
    mHasTarget = !mTargetDescriptors.empty();
    LOGD("Target Descriptors Set: %d features. Teleology Active.", mTargetDescriptors.rows);
}

bool MobileGS::IsTrackingTarget() const {
    return mHasTarget;
}

void MobileGS::Update(const cv::Mat& cameraFrame, const float* viewMatrix, const float* projectionMatrix) {
    if (!mIsInitialized || cameraFrame.empty()) return;

    // Cache matrices for projection (Teleology uses raw float array)
    memcpy(mViewMatrix, viewMatrix, 16 * sizeof(float));
    memcpy(mProjMatrix, projectionMatrix, 16 * sizeof(float));

    ProcessFrame(cameraFrame);
}

void MobileGS::ProcessFrame(const cv::Mat& frame) {
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;

    mOrb->detectAndCompute(frame, cv::noArray(), keypoints, descriptors);

    if (descriptors.empty()) return;

    MatchAndFuse(keypoints, descriptors);
}

void MobileGS::MatchAndFuse(const std::vector<cv::KeyPoint>& curKeypoints, const cv::Mat& curDescriptors) {
    std::lock_guard<std::mutex> lock(mMapMutex);

    // Initialization: If map is empty, create initial cloud on a virtual plane
    if (mMapDescriptors.empty()) {
        mMapKeypoints = curKeypoints;
        curDescriptors.copyTo(mMapDescriptors);

        // Promote to 3D: Assume flat wall parallel to initial camera frame
        mMapPoints3D.reserve(curKeypoints.size());
        for (const auto& kp : curKeypoints) {
            mMapPoints3D.push_back(UnprojectPoint(kp, DEFAULT_WALL_DEPTH));
        }
        return;
    }

    // --- 1. TARGET MATCHING (The Evolution Check) ---
    std::vector<bool> isTargetFeature(curKeypoints.size(), false);
    std::vector<int> targetMatchesIndices;

    if (mHasTarget) {
        std::vector<std::vector<cv::DMatch>> target_knn;
        mMatcher->knnMatch(curDescriptors, mTargetDescriptors, target_knn, 2);

        for (size_t i = 0; i < target_knn.size(); i++) {
            if (target_knn[i].size() >= 2 &&
                    target_knn[i][0].distance < RATIO_THRESH * target_knn[i][1].distance) {

                int queryIdx = target_knn[i][0].queryIdx;
                isTargetFeature[queryIdx] = true;
                targetMatchesIndices.push_back(queryIdx);
            }
        }
    }

    // --- 2. MAP MATCHING (Localization) ---
    std::vector<std::vector<cv::DMatch>> map_knn;
    mMatcher->knnMatch(curDescriptors, mMapDescriptors, map_knn, 2);

    std::vector<bool> isMapFeature(curKeypoints.size(), false);
    for (size_t i = 0; i < map_knn.size(); i++) {
        if (map_knn[i].size() >= 2 &&
                map_knn[i][0].distance < RATIO_THRESH * map_knn[i][1].distance) {
            isMapFeature[map_knn[i][0].queryIdx] = true;
        }
    }

    // --- 3. FUSION & PRUNING ---
    bool performEvolution = (targetMatchesIndices.size() > MIN_TARGET_MATCHES_TO_EVOLVE);

    if (performEvolution) {
        std::vector<cv::KeyPoint> pointsToAdd;
        std::vector<cv::Point3f> points3DToAdd;
        cv::Mat descriptorsToAdd;

        // Collect New Points (Matches Target, Not in Map)
        for (int idx : targetMatchesIndices) {
            if (!isMapFeature[idx]) {
                pointsToAdd.push_back(curKeypoints[idx]);
                points3DToAdd.push_back(UnprojectPoint(curKeypoints[idx], DEFAULT_WALL_DEPTH));
                descriptorsToAdd.push_back(curDescriptors.row(idx));
            }
        }

        // Pruning: Remove old map points that are spatially coincident with new paint
        if (!pointsToAdd.empty()) {
            std::vector<bool> keepMask(mMapPoints3D.size(), true);
            int removeCount = 0;

            // Project current map to 2D for collision check
            std::vector<cv::Point2f> projectedMap(mMapPoints3D.size());
            for(size_t i=0; i<mMapPoints3D.size(); ++i) {
                projectedMap[i] = ProjectPoint(mMapPoints3D[i]);
            }

            for (const auto& newPt : pointsToAdd) {
                for (size_t i = 0; i < projectedMap.size(); i++) {
                    float dx = projectedMap[i].x - newPt.pt.x;
                    float dy = projectedMap[i].y - newPt.pt.y;

                    if ((dx*dx + dy*dy) < (PRUNE_RADIUS_PX * PRUNE_RADIUS_PX)) {
                        keepMask[i] = false;
                        removeCount++;
                    }
                }
            }

            // Efficient Removal using Mask
            if (removeCount > 0) {
                std::vector<cv::Point3f> new3D;
                std::vector<cv::KeyPoint> newKpt;
                new3D.reserve(mMapPoints3D.size() - removeCount);
                newKpt.reserve(mMapKeypoints.size() - removeCount);
                cv::Mat newDesc;
                newDesc.reserve(mMapDescriptors.rows - removeCount);

                for (size_t i = 0; i < keepMask.size(); i++) {
                    if (keepMask[i]) {
                        new3D.push_back(mMapPoints3D[i]);
                        newKpt.push_back(mMapKeypoints[i]);
                        newDesc.push_back(mMapDescriptors.row(i));
                    }
                }

                mMapPoints3D = std::move(new3D);
                mMapKeypoints = std::move(newKpt);
                mMapDescriptors = newDesc; // Move assignment

                LOGD("Teleology: Pruned %d old features.", removeCount);
            }
        }

        // Add new points
        if (!descriptorsToAdd.empty()) {
            mMapKeypoints.insert(mMapKeypoints.end(), pointsToAdd.begin(), pointsToAdd.end());
            mMapPoints3D.insert(mMapPoints3D.end(), points3DToAdd.begin(), points3DToAdd.end());
            cv::vconcat(mMapDescriptors, descriptorsToAdd, mMapDescriptors);
        }
    }
}

// =================================================================================================
// Gaussian Splatting (OpenGL)
// =================================================================================================

void MobileGS::feedDepthData(const uint16_t* depthPixels, const float* colorPixels,
                             int width, int height, int stride, const float* cameraPose, float fov) {
    if (!depthPixels) return;

    // Intrinsics
    float aspect = (float)width / (float)height;
    float fy = height / (2.0f * tan(fov / 2.0f));
    float fx = fy;
    float cx = width / 2.0f;
    float cy = height / 2.0f;

    std::vector<Splat> newSplats;
    int rowStrideShorts = stride / 2;

    for (int v = 0; v < height; v += SAMPLE_STRIDE) {
        for (int u = 0; u < width; u += SAMPLE_STRIDE) {
            uint16_t d_raw = depthPixels[v * rowStrideShorts + u];
            if (d_raw == 0) continue;

            float z_local = d_raw * 0.001f;
            if (z_local < MIN_DEPTH || z_local > MAX_DEPTH) continue;

            float x_local = (u - cx) * z_local / fx;
            float y_local = (v - cy) * z_local / fy;

            float x_world, y_world, z_world;
            mat4_mul_vec3(cameraPose, x_local, y_local, -z_local, x_world, y_world, z_world);

            Splat s;
            s.x = x_world;
            s.y = y_world;
            s.z = z_world;
            s.r = 0; s.g = 200; s.b = 128; // Tealish default
            s.confidence = 1.0f;
            s.radius = 0.02f;
            s.luminance = 0.5f;

            newSplats.push_back(s);
        }
    }

    // Add to chunk
    std::lock_guard<std::mutex> lock(mChunkMutex);
    ChunkKey key = {0,0,0}; // Single chunk for now (can expand to spatial hashing later)
    if (mChunks.find(key) == mChunks.end()) {
        mChunks[key] = Chunk();
    }

    Chunk& chunk = mChunks[key];
    if (chunk.splatCount + newSplats.size() < MAX_SPLATS) {
        chunk.splats.insert(chunk.splats.end(), newSplats.begin(), newSplats.end());
        chunk.splatCount += newSplats.size();
        chunk.isDirty = true;
    }
}

void MobileGS::updateCamera(const float* view, const float* proj) {
    // Copy to glm::mat4 (Splatting uses glm)
    memcpy(&mStoredView[0][0], view, 16 * sizeof(float));
    memcpy(&mStoredProj[0][0], proj, 16 * sizeof(float));
}

void MobileGS::draw() {
    if (mProgram == 0) {
        // Fallback or lazy init
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mLocMVP = glGetUniformLocation(mProgram, "uMVP");
        mLocPointSize = glGetUniformLocation(mProgram, "uPointSize");
    }

    glUseProgram(mProgram);

    // Compute MVP (Splatting)
    glm::mat4 vp = mStoredProj * mStoredView;
    glUniformMatrix4fv(mLocMVP, 1, GL_FALSE, &vp[0][0]);
    glUniform1f(mLocPointSize, 15.0f); // Default point size

    std::lock_guard<std::mutex> lock(mChunkMutex);
    for (auto& pair : mChunks) {
        Chunk& chunk = pair.second;
        if (chunk.splats.empty()) continue;

        if (chunk.isDirty) {
            if (chunk.vbo == 0) glGenBuffers(1, &chunk.vbo);
            glBindBuffer(GL_ARRAY_BUFFER, chunk.vbo);

            // Format: Pos(3), Color(3), Opacity(1) = 7 floats
            std::vector<float> vboData;
            vboData.reserve(chunk.splats.size() * 7);
            for (const auto& s : chunk.splats) {
                vboData.push_back(s.x);
                vboData.push_back(s.y);
                vboData.push_back(s.z);
                vboData.push_back(s.r / 255.0f);
                vboData.push_back(s.g / 255.0f);
                vboData.push_back(s.b / 255.0f);
                vboData.push_back(1.0f); // Opacity
            }
            glBufferData(GL_ARRAY_BUFFER, vboData.size() * sizeof(float), vboData.data(), GL_STATIC_DRAW);
            chunk.isDirty = false;
        }

        glBindBuffer(GL_ARRAY_BUFFER, chunk.vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(3 * sizeof(float)));
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void*)(6 * sizeof(float)));

        glDrawArrays(GL_POINTS, 0, chunk.splats.size());

        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glDisableVertexAttribArray(2);
    }
}

void MobileGS::onSurfaceChanged(int width, int height) {
    mScreenWidth = width;
    mScreenHeight = height;
    // Also update OpenCV size?
    mWidth = width;
    mHeight = height;
}

int MobileGS::getSplatCount() {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    int count = 0;
    for (const auto& pair : mChunks) {
        count += pair.second.splatCount;
    }
    return count;
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    for (auto& pair : mChunks) {
        if (pair.second.vbo != 0) {
            glDeleteBuffers(1, &pair.second.vbo);
        }
    }
    mChunks.clear();
}

bool MobileGS::saveModel(std::string path) { return true; }
bool MobileGS::loadModel(std::string path) { return true; }
void MobileGS::alignMap(const float* transform) { }

ChunkKey MobileGS::getChunkKey(float x, float y, float z) {
    return ChunkKey{(int)(x/mChunkSize), (int)(y/mChunkSize), (int)(z/mChunkSize)};
}

float MobileGS::getLuminance(uint8_t r, uint8_t g, uint8_t b) {
    return (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f;
}

// =================================================================================================
// Math Helpers (Teleology)
// =================================================================================================

void MobileGS::MultiplyMatrixVector(const float* matrix, const float* in, float* out) {
    for (int i = 0; i < 4; i++) {
        out[i] = 0.0f;
        for (int j = 0; j < 4; j++) {
            out[i] += matrix[j * 4 + i] * in[j];
        }
    }
}

cv::Point2f MobileGS::ProjectPoint(const cv::Point3f& p3d) {
    float vec4[4] = {p3d.x, p3d.y, p3d.z, 1.0f};
    float clip[4] = {0,0,0,0};
    float viewSpace[4];
    MultiplyMatrixVector(mViewMatrix, vec4, viewSpace);
    MultiplyMatrixVector(mProjMatrix, viewSpace, clip);

    if (clip[3] != 0.0f) {
        float invW = 1.0f / clip[3];
        clip[0] *= invW;
        clip[1] *= invW;
    }
    float screenX = (clip[0] + 1.0f) * 0.5f * mWidth;
    float screenY = (1.0f - clip[1]) * 0.5f * mHeight;
    return cv::Point2f(screenX, screenY);
}

cv::Point3f MobileGS::UnprojectPoint(const cv::KeyPoint& kpt, float depth) {
    float ndcX = (kpt.pt.x / mWidth) * 2.0f - 1.0f;
    float ndcY = 1.0f - (kpt.pt.y / mHeight) * 2.0f;

    float fovX = mProjMatrix[0];
    float fovY = mProjMatrix[5];

    float rayX = 0, rayY = 0, rayZ = -1.0f;
    if (fovX > 0) rayX = ndcX / fovX;
    if (fovY > 0) rayY = ndcY / fovY;

    float viewX = rayX * depth;
    float viewY = rayY * depth;
    float viewZ = -depth;

    float r00 = mViewMatrix[0], r01 = mViewMatrix[4], r02 = mViewMatrix[8];
    float r10 = mViewMatrix[1], r11 = mViewMatrix[5], r12 = mViewMatrix[9];
    float r20 = mViewMatrix[2], r21 = mViewMatrix[6], r22 = mViewMatrix[10];

    float tx = mViewMatrix[12], ty = mViewMatrix[13], tz = mViewMatrix[14];

    float camX = -(r00*tx + r10*ty + r20*tz);
    float camY = -(r01*tx + r11*ty + r21*tz);
    float camZ = -(r02*tx + r12*ty + r22*tz);

    float worldRayX = r00*viewX + r10*viewY + r20*viewZ;
    float worldRayY = r01*viewX + r11*viewY + r21*viewZ;
    float worldRayZ = r02*viewX + r12*viewY + r22*viewZ;

    return cv::Point3f(camX + worldRayX, camY + worldRayY, camZ + worldRayZ);
}
