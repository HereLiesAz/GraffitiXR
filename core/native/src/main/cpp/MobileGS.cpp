#include "MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <fstream>
#include <cmath>
#include <vector>
#include <opencv2/imgproc.hpp>
#include <fstream>
#include <random>
#include <future>
#include <glm/gtc/type_ptr.hpp>

#define TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Shader sources
const char* VS_SRC =
    "attribute vec4 vPosition;\n"
    "attribute vec4 vColor;\n"
    "varying vec4 fColor;\n"
    "uniform mat4 uMVP;\n"
    "void main() {\n"
    "  gl_Position = uMVP * vPosition;\n"
    "  gl_PointSize = 15.0;\n"
    "  fColor = vColor;\n"
    "}\n";

const char* FS_SRC =
    "precision mediump float;\n"
    "varying vec4 fColor;\n"
    "void main() {\n"
    "  gl_FragColor = fColor;\n"
    "}\n";

MobileGS::MobileGS() {
    m_Splats.reserve(MAX_SPLATS);
    m_DrawBuffer.reserve(MAX_SPLATS * 7);
    LOGI("MobileGS Created");
}

MobileGS::~MobileGS() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_Splats.clear();
    mVoxelGrid.clear();
    m_DrawBuffer.clear();
    LOGI("MobileGS Destroyed");
}

bool MobileGS::initialize() {
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // Compile Shaders
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &VS_SRC, NULL);
    glCompileShader(vs);

    GLint compiled;
    glGetShaderiv(vs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        LOGI("Vertex shader compilation failed");
        return false;
    }

    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &FS_SRC, NULL);
    glCompileShader(fs);

    glGetShaderiv(fs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        LOGI("Fragment shader compilation failed");
        return false;
    }

    m_Program = glCreateProgram();
    glAttachShader(m_Program, vs);
    glAttachShader(m_Program, fs);

    // Bind attributes BEFORE linking
    glBindAttribLocation(m_Program, 0, "vPosition");
    glBindAttribLocation(m_Program, 1, "vColor");

    glLinkProgram(m_Program);

    GLint linked;
    glGetProgramiv(m_Program, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGI("Shader linking failed");
        return false;
    }

    m_LocMVP = glGetUniformLocation(m_Program, "uMVP");

    LOGI("MobileGS Initialized with Shader. Program: %d", m_Program);
    return true;
// TUNING: Stability over Density
const float CONFIDENCE_THRESHOLD = 0.6f;
const float CONFIDENCE_INCREMENT = 0.15f; // Very sticky
const float PRUNE_THRESHOLD = 0.1f;       // Keep almost everything
const int MIN_AGE_MS = 2000;
const int MAX_UNSEEN_AGE_MS = 10000;      // Expire points not seen for 10s

const char* VS_SRC = R"(#version 300 es
layout(location = 0) in vec3 aInstancePos;
layout(location = 1) in vec3 aInstanceColor;
layout(location = 2) in float aInstanceScale;
layout(location = 3) in float aInstanceOpacity;
layout(location = 4) in vec2 aQuadVert;

uniform mat4 uView;
uniform mat4 uProj;
uniform vec3 uCamPos;

out vec3 vColor;
out float vAlpha;
out vec2 vUv;

void main() {
    vColor = aInstanceColor;
    vAlpha = aInstanceOpacity;
    vUv = aQuadVert;

    vec3 forward = normalize(aInstancePos - uCamPos);
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(up, forward));
    up = cross(forward, right);

    // DEBUG: Huge points (4x voxel size) to verify rendering
    vec3 vertexOffset = (right * aQuadVert.x + up * aQuadVert.y) * (aInstanceScale * 4.0);
    vec3 finalPos = aInstancePos + vertexOffset;

    gl_Position = uProj * uView * vec4(finalPos, 1.0);
})";

const char* FS_SRC = R"(#version 300 es
precision mediump float;
in vec3 vColor;
in float vAlpha;
in vec2 vUv;
out vec4 FragColor;
void main() {
    float r2 = dot(vUv, vUv);
    if (r2 > 0.25) discard;
    // DEBUG: Solid Alpha (No soft edges)
    FragColor = vec4(vColor, 1.0);
})";


/**
 * Constructor: Initializes the voxel grid and default state.
 */
MobileGS::MobileGS() :
        mProgram(0), mVAO(0), mVBO(0), mQuadVBO(0),
        mBgProgram(0), mBgVAO(0), mBgVBO(0), mBgTexture(0),
        mViewMatrix(1.0f), mProjMatrix(1.0f), mSortViewMatrix(1.0f),
        mRenderBufferDirty(false),
        mSortRunning(false), mStopThread(false), mSortResultReady(false),
        mMapChanged(false), mNewBgAvailable(false), mHasBgData(false), mIsInitialized(false),
        mFrameCount(0)
{
    mLastUpdateTime = std::chrono::steady_clock::now();

/**
 * Background Thread: Sorts the splats back-to-front for correct alpha blending.
 * Runs continuously to decouple sorting from the render loop.
 */
    mSortThread = std::thread(&MobileGS::sortThreadLoop, this);
    mGaussians.reserve(MAX_POINTS);
    mRenderBuffer.reserve(MAX_POINTS);
}

MobileGS::~MobileGS() {
    mStopThread = true;
    mSortCV.notify_all();
    if(mSortThread.joinable()) {
        mSortThread.join();
    }
    mGaussians.clear();
    mRenderBuffer.clear();
    mVoxelGrid.clear();
    mIsInitialized = false;
}


/**
 * Initializes OpenGL resources (Shaders, VBOs) and starts the sorting thread.
 * Must be called on the GL thread.
 */
void MobileGS::initialize() {
    if (mIsInitialized) return;
    compileShaders();
    mIsInitialized = true;
    LOGI("MobileGS Initialized (GLES 3.0 Native)");
}

int MobileGS::getPointCount() {
    std::lock_guard<std::mutex> lock(mDataMutex);
    return (int)mGaussians.size();
}

void MobileGS::updateCamera(const float* viewMtx, const float* projMtx) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    memcpy(&mViewMatrix[0][0], viewMtx, 16 * sizeof(float));
    memcpy(&mProjMatrix[0][0], projMtx, 16 * sizeof(float));
}

void MobileGS::applyTransform(const float* transformMtx) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    glm::mat4 transform;
    memcpy(glm::value_ptr(transform), transformMtx, 16 * sizeof(float));

    // 1. Invert Matrices
    cv::Mat viewMat(4, 4, CV_32F, m_ViewMatrix);
    viewMat = viewMat.t();

    cv::Mat projMat(4, 4, CV_32F, m_ProjMatrix);
    projMat = projMat.t();

    cv::Mat invView, invProj;

    if (cv::determinant(viewMat) == 0 || cv::determinant(projMat) == 0) return;

    cv::invert(viewMat, invView);
    cv::invert(projMat, invProj);

    const int STRIDE = 8; // Process 1 out of every 8x8 pixels

    for (int y = 0; y < height; y += STRIDE) {
        const uint16_t* rowPtr = depth.ptr<uint16_t>(y);
        for (int x = 0; x < width; x += STRIDE) {
            uint16_t d_mm = rowPtr[x];

            if (d_mm == 0 || d_mm > MAX_DEPTH_MM) continue;

            float d_meters = d_mm * 0.001f;
            if (d_meters > CULL_DISTANCE) continue;

            // 2. Unproject to View Space
            float ndc_x = (2.0f * x / width) - 1.0f;
            float ndc_y = 1.0f - (2.0f * y / height); // Flip Y

            // Clip space vector
            cv::Mat clipVec = (cv::Mat_<float>(4, 1) << ndc_x, ndc_y, 0.0f, 1.0f);
            cv::Mat viewVec = invProj * clipVec;

            // Optimized access using pointers
            const float* viewVecData = viewVec.ptr<float>();
            float w = viewVecData[3];
            if (std::abs(w) < 1e-5) continue;

            float vx = viewVecData[0] / w;
            float vy = viewVecData[1] / w;
            float vz = viewVecData[2] / w;

            if (std::abs(vz) < 1e-5) continue;
            float scale = -d_meters / vz;

            float px_view = vx * scale;
            float py_view = vy * scale;
            float pz_view = vz * scale;

            // 3. Transform to World Space
            cv::Mat pView = (cv::Mat_<float>(4, 1) << px_view, py_view, pz_view, 1.0f);
            cv::Mat pWorld = invView * pView;

            const float* pWorldData = pWorld.ptr<float>();
            float wx = pWorldData[0];
            float wy = pWorldData[1];
            float wz = pWorldData[2];

            // 4. Voxel Hashing
            VoxelKey key;
            key.x = (int)std::floor(wx / VOXEL_SIZE);
            key.y = (int)std::floor(wy / VOXEL_SIZE);
            key.z = (int)std::floor(wz / VOXEL_SIZE);

            auto it = mVoxelGrid.find(key);
            if (it != mVoxelGrid.end()) {
                // Update existing splat
                int idx = it->second;
                Splat& s = m_Splats[idx];

                float alpha = SPLAT_LEARNING_RATE;
                s.x = s.x * (1.0f - alpha) + wx * alpha;
                s.y = s.y * (1.0f - alpha) + wy * alpha;
                s.z = s.z * (1.0f - alpha) + wz * alpha;

                if (s.confidence < CONFIDENCE_THRESHOLD + CONFIDENCE_BOOST_THRESHOLD) {
                    s.confidence += CONFIDENCE_INCREMENT;
                }
            } else {
                // Create new splat
                if (m_Splats.size() < MAX_SPLATS) {
                    Splat s;
                    s.x = wx;
                    s.y = wy;
                    s.z = wz;

                    // Color based on height (Y)
                    s.r = 0.5f + (wy * 0.2f);
                    s.g = 0.8f;
                    s.b = 0.9f;
                    s.a = 0.0f;
                    s.confidence = 1.0f;

                    m_Splats.push_back(s);
                    mVoxelGrid[key] = m_Splats.size() - 1;
                }
            }
        }
    }
}

void MobileGS::draw() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    if (m_Splats.empty() || m_Program == 0) return;

    glUseProgram(m_Program);

    // Compute MVP using Corrected Matrices
    cv::Mat viewMat(4, 4, CV_32F, m_ViewMatrix);
    viewMat = viewMat.t();

    cv::Mat projMat(4, 4, CV_32F, m_ProjMatrix);
    projMat = projMat.t();

    cv::Mat mvp = projMat * viewMat;

    // Transpose back for OpenGL Column-Major order
    cv::Mat mvpGL = mvp.t();

    glUniformMatrix4fv(m_LocMVP, 1, GL_FALSE, (float*)mvpGL.data);
    mVoxelGrid.clear();

    for (auto& splat : mGaussians) {
        glm::vec4 pos(splat.renderData.position, 1.0f);
        pos = transform * pos;
        splat.renderData.position = glm::vec3(pos);

        VoxelKey key;
        key.x = static_cast<int>(std::floor(pos.x / VOXEL_SIZE));
        key.y = static_cast<int>(std::floor(pos.y / VOXEL_SIZE));
        key.z = static_cast<int>(std::floor(pos.z / VOXEL_SIZE));

        mVoxelGrid[key] = (int)(&splat - &mGaussians[0]);
    }
    mMapChanged = true;
    mRenderBufferDirty = true;
}

void MobileGS::processDepthFrame(const cv::Mat& depthMap, int width, int height) {
    auto now = std::chrono::steady_clock::now();
    if (std::chrono::duration_cast<std::chrono::milliseconds>(now - mLastUpdateTime).count() < 33) return;
    mLastUpdateTime = now;

    std::lock_guard<std::mutex> lock(mDataMutex);
    if (mProjMatrix[0][0] == 0) return;

    // Reuse m_DrawBuffer
    m_DrawBuffer.clear();

    for (const auto& s : m_Splats) {
        float alpha = (s.confidence >= CONFIDENCE_THRESHOLD) ? 1.0f : (s.confidence / CONFIDENCE_THRESHOLD);
        if (alpha < 0.1f) continue;

        m_DrawBuffer.push_back(s.x);
        m_DrawBuffer.push_back(s.y);
        m_DrawBuffer.push_back(s.z);

        // Use stored color
        m_DrawBuffer.push_back(s.r);
        m_DrawBuffer.push_back(s.g);
        m_DrawBuffer.push_back(s.b);
        m_DrawBuffer.push_back(alpha);
    mFrameCount++;
    if (mFrameCount % 100 == 0 || mGaussians.size() > MAX_POINTS * 0.95) {
        pruneMap();
    }

    glm::mat4 invView = glm::inverse(mViewMatrix);
    float p00 = mProjMatrix[0][0];
    float p11 = mProjMatrix[1][1];
    float p20 = mProjMatrix[2][0];
    float p21 = mProjMatrix[2][1];

    // TUNING: Extremely Sparse Sampling to prevent flood
    int step = 8;

    bool added = false;

    if (depthMap.isContinuous()) {
        const uint16_t* ptr = depthMap.ptr<uint16_t>(0);
        for (int y = 0; y < height; y += step) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x += step) {
                uint16_t d_raw = ptr[rowOffset + x];
                if (d_raw < 200 || d_raw > 4000) continue;

                float z = d_raw * 0.001f;
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

                // Check voxel occupancy.
                auto it = mVoxelGrid.find(key);
                if (it != mVoxelGrid.end()) {
                    int idx = it->second;
                    if(idx >= 0 && idx < mGaussians.size()){
                        SplatMetadata& g = mGaussians[idx];
                        g.renderData.opacity = std::min(1.0f, g.renderData.opacity + CONFIDENCE_INCREMENT);
                        g.renderData.position = glm::mix(g.renderData.position, glm::vec3(worldPos), 0.1f);
                        g.lastSeenTime = now;
                        added = true;
                    }
                } else if (mGaussians.size() < MAX_POINTS) {
                    SplatMetadata g;
                    g.renderData.position = glm::vec3(worldPos);
                    g.renderData.scale = VOXEL_SIZE * 1.8f;
                    g.renderData.opacity = CONFIDENCE_INCREMENT;
                    g.renderData.color = glm::vec3(0.0f, 0.8f, 1.0f);
                    g.creationTime = now;
                    g.lastSeenTime = now;
                    mGaussians.push_back(g);
                    mVoxelGrid[key] = (int)(mGaussians.size() - 1);
                    added = true;
                }
            }
        }
    }

    if(added) {
        mRenderBufferDirty = true;
    }
}


    if (m_DrawBuffer.empty()) return;

    glEnableVertexAttribArray(0); // vPosition
    glEnableVertexAttribArray(1); // vColor

    int stride = 7 * sizeof(float);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, m_DrawBuffer.data());
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, m_DrawBuffer.data() + 3);

    glDrawArrays(GL_POINTS, 0, m_DrawBuffer.size() / 7);
/**
 * Garbage Collection: Removes points that have low confidence (opacity)
 * or are too old, keeping the map sparse and efficient.
 */
void MobileGS::pruneMap() {
    mMapChanged = true;
    mRenderBufferDirty = true;
    auto now = std::chrono::steady_clock::now();

    std::vector<SplatMetadata> survived;
    survived.reserve(mGaussians.size());
    mVoxelGrid.clear();

    bool hittingLimit = mGaussians.size() > (MAX_POINTS * 0.9);

    for (const auto& g : mGaussians) {
        long age = std::chrono::duration_cast<std::chrono::milliseconds>(now - g.creationTime).count();
        long unseenAge = std::chrono::duration_cast<std::chrono::milliseconds>(now - g.lastSeenTime).count();
        bool goodConfidence = g.renderData.opacity >= PRUNE_THRESHOLD;
        bool isYoung = age < MIN_AGE_MS;
        bool isRecentlySeen = unseenAge < MAX_UNSEEN_AGE_MS;

        // Only prune young points if we are literally out of memory
        if (hittingLimit && isYoung && g.renderData.opacity < 0.05f) {
            continue;
        }

        if ((goodConfidence || isYoung) && isRecentlySeen) {
            survived.push_back(g);
            VoxelKey key = {
                    (int)std::floor(g.renderData.position.x/VOXEL_SIZE),
                    (int)std::floor(g.renderData.position.y/VOXEL_SIZE),
                    (int)std::floor(g.renderData.position.z/VOXEL_SIZE)
            };
            mVoxelGrid[key] = (int)(survived.size() - 1);
        }
    }
    size_t removed = mGaussians.size() - survived.size();
    mGaussians = std::move(survived);
    // Log only if significant pruning happens
    if (removed > 1000) LOGI("GC: Pruned %zu. Count: %zu", removed, mGaussians.size());
}

void MobileGS::setBackgroundFrame(const cv::Mat& frame) {
    std::lock_guard<std::mutex> lock(mBgMutex);
    frame.copyTo(mPendingBgFrame);
    mNewBgAvailable = true;
    mHasBgData = true;
}

bool MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) {
        LOGI("Failed to open file for saving: %s", path.c_str());
        return false;
    }

    uint64_t count = m_Splats.size();
    out.write(reinterpret_cast<const char*>(&count), sizeof(count));
    out.write(reinterpret_cast<const char*>(m_Splats.data()), count * sizeof(Splat));
    out.close();

    if (out.fail()) {
        LOGI("Failed to write model data to: %s", path.c_str());
        return false;
    }

    LOGI("Saved model to %s with %llu splats", path.c_str(), (unsigned long long)count);
    return true;
}

bool MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        LOGI("Failed to open file for loading: %s", path.c_str());
        return false;
    }

    uint64_t count = 0;
    in.read(reinterpret_cast<char*>(&count), sizeof(count));

    if (in.fail()) {
        LOGI("Failed to read splat count from: %s", path.c_str());
        return false;
    }

    if (count > MAX_SPLATS) {
        LOGI("Model contains %llu splats, exceeding limit of %d", (unsigned long long)count, MAX_SPLATS);
        return false;
    }

    m_Splats.resize(count);
    in.read(reinterpret_cast<char*>(m_Splats.data()), count * sizeof(Splat));

    if (in.fail()) {
        LOGI("Failed to read splat data from: %s (read %ld bytes)", path.c_str(), (long)in.gcount());
        return false;
    }

    in.close();

    mVoxelGrid.clear();
    for (size_t i = 0; i < m_Splats.size(); ++i) {
        const auto& s = m_Splats[i];
        VoxelKey key;
        key.x = (int)std::floor(s.x / VOXEL_SIZE);
        key.y = (int)std::floor(s.y / VOXEL_SIZE);
        key.z = (int)std::floor(s.z / VOXEL_SIZE);
        mVoxelGrid[key] = i;
    }

    LOGI("Loaded model from %s with %llu splats", path.c_str(), (unsigned long long)count);
    return true;
void MobileGS::processImage(const cv::Mat& image, int width, int height, int64_t timestamp) {
    setBackgroundFrame(image);
}

void MobileGS::compileShaders() {
    auto createProg = [](const char* vsSrc, const char* fsSrc) {
        GLuint vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, 1, &vsSrc, 0);
        glCompileShader(vs);
        GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, 1, &fsSrc, 0);
        glCompileShader(fs);
        GLuint prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        glDeleteShader(vs);
        glDeleteShader(fs);
        return prog;
    };

    mProgram = createProg(VS_SRC, FS_SRC);

    glGenVertexArrays(1, &mVAO);
    glGenBuffers(1, &mVBO);
    glGenBuffers(1, &mQuadVBO);

    float quadVerts[] = { -0.5f,-0.5f, 0.5f,-0.5f, -0.5f,0.5f, 0.5f,0.5f };

    glBindVertexArray(mVAO);
    glBindBuffer(GL_ARRAY_BUFFER, mQuadVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quadVerts), quadVerts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    glBindBuffer(GL_ARRAY_BUFFER, mVBO);

    int stride = sizeof(SplatRenderData);

    for(int i=0; i<4; ++i) {
        glEnableVertexAttribArray(i);
        int size = (i == 0 || i == 1) ? 3 : 1;
        int offset = (i == 0) ? 0 : (i == 1) ? 3 : (i == 2) ? 6 : 7;
        glVertexAttribPointer(i, size, GL_FLOAT, GL_FALSE, stride, (void*)(uintptr_t)(offset * sizeof(float)));
        glVertexAttribDivisor(i, 1);
    }
    glBindVertexArray(0);
}


/**
 * Background Thread: Sorts the splats back-to-front for correct alpha blending.
 * Runs continuously to decouple sorting from the render loop.
 */
void MobileGS::sortThreadLoop() {
    glm::vec3 lastSortPos(0.0f);
    glm::vec3 lastSortDir(0.0f);

    while (!mStopThread) {
        {
            std::unique_lock<std::mutex> lock(mSortMutex);
            mSortCV.wait(lock, [this] { return mStopThread || mSortRunning.load(); });
            if (mStopThread) return;
        }

        glm::mat4 view;
        std::vector<glm::vec3> positions;

        {
            std::lock_guard<std::mutex> dataLock(mDataMutex);
            view = mSortViewMatrix;
            positions.reserve(mGaussians.size());
            for(const auto& g : mGaussians) positions.push_back(g.renderData.position);
        }

        glm::vec3 camPos = glm::vec3(glm::inverse(view)[3]);
        glm::vec3 camDir = glm::vec3(view[0][2], view[1][2], view[2][2]);

        float distDelta = glm::distance(camPos, lastSortPos);
        float dirDelta = glm::dot(camDir, lastSortDir);

        if (distDelta < 0.05f && dirDelta > 0.99f && !mMapChanged) {
            mSortRunning = false;
            continue;
        }

        lastSortPos = camPos;
        lastSortDir = camDir;

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
            if (!mMapChanged) {
                mSortListBack = std::move(sorted);
                mSortResultReady = true;
            } else {
                mMapChanged = false;
            }
        }
        mSortRunning = false;
    }
}


/**
 * Renders the point cloud using Instanced Rendering.
 * Uploads the sorted/culled buffer to the GPU and issues the draw call.
 */
void MobileGS::draw() {
    if (!mIsInitialized) initialize();

    size_t renderSize = 0;
    {
        std::lock_guard<std::mutex> lock(mDataMutex);
        if (mGaussians.empty()) return;

        if (mRenderBufferDirty || mGaussians.size() != mRenderBuffer.size()) {
            mRenderBuffer.clear();
            mRenderBuffer.reserve(mGaussians.size());
            for(const auto& meta : mGaussians) {
                mRenderBuffer.push_back(meta.renderData);
            }
            mRenderBufferDirty = false;
        }
        renderSize = mRenderBuffer.size();
    }

    glEnable(GL_DEPTH_TEST);
    glDepthMask(GL_TRUE);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glUseProgram(mProgram);

    glm::vec3 camPos = glm::vec3(glm::inverse(mViewMatrix)[3]);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uView"), 1, GL_FALSE, &mViewMatrix[0][0]);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uProj"), 1, GL_FALSE, &mProjMatrix[0][0]);
    glUniform3fv(glGetUniformLocation(mProgram, "uCamPos"), 1, &camPos[0]);

    glBindBuffer(GL_ARRAY_BUFFER, mVBO);
    {
        std::lock_guard<std::mutex> lock(mDataMutex);
        glBufferData(GL_ARRAY_BUFFER, renderSize * sizeof(SplatRenderData), mRenderBuffer.data(), GL_DYNAMIC_DRAW);
    }

    glBindVertexArray(mVAO);
    // Instanced draw call.
    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, (GLsizei)renderSize);
    glBindVertexArray(0);

    glDisable(GL_BLEND);
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_Splats.clear();
    mVoxelGrid.clear();
    m_DrawBuffer.clear();
}
    std::lock_guard<std::mutex> lock(mDataMutex);
    mGaussians.clear();
    mRenderBuffer.clear();
    mVoxelGrid.clear();
    mMapChanged = true;
    mRenderBufferDirty = true;
}

bool MobileGS::saveModel(const std::string& path) {
    std::vector<SplatRenderData> dataToSave;
    {
        std::lock_guard<std::mutex> lock(mDataMutex);
        dataToSave.reserve(mGaussians.size());
        for(const auto& g : mGaussians) {
            dataToSave.push_back(g.renderData);
        }
    }
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    size_t count = dataToSave.size();
    out.write(reinterpret_cast<const char*>(&count), sizeof(count));
    if (count > 0) {
        out.write(reinterpret_cast<const char*>(dataToSave.data()), (std::streamsize)(count * sizeof(SplatRenderData)));
    }
    return true;
}

bool MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    size_t count = 0;
    in.read(reinterpret_cast<char*>(&count), sizeof(count));
    if (count > MAX_POINTS) count = MAX_POINTS;
    mGaussians.clear();
    mGaussians.resize(count);
    mVoxelGrid.clear();
    if (count > 0) {
        std::vector<SplatRenderData> loadedData(count);
        in.read(reinterpret_cast<char*>(loadedData.data()), (std::streamsize)(count * sizeof(SplatRenderData)));
        auto now = std::chrono::steady_clock::now();
        for (int i=0; i<(int)count; ++i) {
            auto& g = mGaussians[i];
            g.renderData = loadedData[i];
            g.creationTime = now;
            g.lastSeenTime = now;
            VoxelKey key = {
                    (int)std::floor(g.renderData.position.x/VOXEL_SIZE),
                    (int)std::floor(g.renderData.position.y/VOXEL_SIZE),
                    (int)std::floor(g.renderData.position.z/VOXEL_SIZE)
            };
            mVoxelGrid[key] = i;
        }
    }
    mMapChanged = true;
    mRenderBufferDirty = true;
    return true;
}
