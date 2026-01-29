#include "MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <opencv2/imgproc.hpp>
#include <fstream>
#include <random>
#include <future>
#include <glm/gtc/type_ptr.hpp>

#define TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

const float CONFIDENCE_THRESHOLD = 0.6f;
const float CONFIDENCE_INCREMENT = 0.05f;
const float PRUNE_THRESHOLD = 0.3f;
const int MIN_AGE_MS = 2000;

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

    vec3 vertexOffset = (right * aQuadVert.x + up * aQuadVert.y) * aInstanceScale;
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
    float alpha = exp(-r2 * 8.0) * vAlpha;
    FragColor = vec4(vColor, alpha);
})";

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
    mSortThread = std::thread(&MobileGS::sortThreadLoop, this);
    // Reserve memory to prevent reallocations
    mGaussians.reserve(MAX_POINTS);
    mRenderBuffer.reserve(MAX_POINTS);
}

MobileGS::~MobileGS() {
    // 1. Stop the sort thread safely
    mStopThread = true;
    mSortCV.notify_all();
    if(mSortThread.joinable()) mSortThread.join();

    // 2. CRITICAL FIX: Do NOT call glDelete* here.
    // This destructor is called on the Main Thread (via SlamManager.destroyNative),
    // but the GL context belongs to the Render Thread.
    // The EGLContext destruction will automatically clean up these resources.
    // Explicitly deleting them here causes "call to OpenGL ES API with no current context" error.

    mIsInitialized = false;
}

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

    // Rebuild voxel grid after transform
    mVoxelGrid.clear();

    for (auto& splat : mGaussians) {
        glm::vec4 pos(splat.renderData.position, 1.0f);
        pos = transform * pos;
        splat.renderData.position = glm::vec3(pos);

        // Re-hash
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

    mFrameCount++;
    if (mFrameCount % 100 == 0 || mGaussians.size() > MAX_POINTS * 0.95) {
        pruneMap();
    }

    glm::mat4 invView = glm::inverse(mViewMatrix);
    float p00 = mProjMatrix[0][0];
    float p11 = mProjMatrix[1][1];
    float p20 = mProjMatrix[2][0];
    float p21 = mProjMatrix[2][1];

    int step = 2;
    if (mGaussians.size() > 20000) step = 3;
    if (mGaussians.size() > 40000) step = 4;

    bool added = false;

    for (int y = 0; y < height; y += step) {
        const uint16_t* rowPtr = depthMap.ptr<uint16_t>(y);
        for (int x = 0; x < width; x += step) {
            uint16_t d_raw = rowPtr[x];
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

            auto it = mVoxelGrid.find(key);
            if (it != mVoxelGrid.end()) {
                int idx = it->second;
                if(idx >= 0 && idx < mGaussians.size()){
                    SplatMetadata& g = mGaussians[idx];
                    g.renderData.opacity = std::min(1.0f, g.renderData.opacity + CONFIDENCE_INCREMENT);
                    g.renderData.position = glm::mix(g.renderData.position, glm::vec3(worldPos), 0.1f);
                    added = true;
                }
            } else if (mGaussians.size() < MAX_POINTS) {
                SplatMetadata g;
                g.renderData.position = glm::vec3(worldPos);
                g.renderData.scale = VOXEL_SIZE * 1.8f;
                g.renderData.opacity = CONFIDENCE_INCREMENT;
                g.renderData.color = glm::vec3(0.0f, 0.8f, 1.0f);
                g.creationTime = now;
                mGaussians.push_back(g);
                mVoxelGrid[key] = (int)(mGaussians.size() - 1);
                added = true;
            }
        }
    }
    if(added) {
        mRenderBufferDirty = true;
    }
}

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

        bool goodConfidence = g.renderData.opacity >= PRUNE_THRESHOLD;
        bool isYoung = age < MIN_AGE_MS;

        if (hittingLimit && isYoung && g.renderData.opacity < (PRUNE_THRESHOLD/2.0f)) {
            continue;
        }

        if (goodConfidence || isYoung) {
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
    if (removed > 0) LOGI("GC: Pruned %zu noise points. Total: %zu", removed, mGaussians.size());
}

void MobileGS::setBackgroundFrame(const cv::Mat& frame) {
    std::lock_guard<std::mutex> lock(mBgMutex);
    frame.copyTo(mPendingBgFrame);
    mNewBgAvailable = true;
    mHasBgData = true;
}

void MobileGS::processImage(const cv::Mat& image, int width, int height, int64_t timestamp) {
    setBackgroundFrame(image);
}

void MobileGS::compileShaders() {
    auto createProg = [](const char* vsSrc, const char* fsSrc) {
        GLuint vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, 1, &vsSrc, 0);
        glCompileShader(vs);

        GLint compiled;
        glGetShaderiv(vs, GL_COMPILE_STATUS, &compiled);
        if(!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(vs, GL_INFO_LOG_LENGTH, &infoLen);
            if(infoLen > 1) {
                char* infoLog = new char[infoLen];
                glGetShaderInfoLog(vs, infoLen, NULL, infoLog);
                LOGE("VS Compile Error: %s", infoLog);
                delete[] infoLog;
            }
        }

        GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, 1, &fsSrc, 0);
        glCompileShader(fs);

        glGetShaderiv(fs, GL_COMPILE_STATUS, &compiled);
        if(!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(fs, GL_INFO_LOG_LENGTH, &infoLen);
            if(infoLen > 1) {
                char* infoLog = new char[infoLen];
                glGetShaderInfoLog(fs, infoLen, NULL, infoLog);
                LOGE("FS Compile Error: %s", infoLog);
                delete[] infoLog;
            }
        }

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
    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, (GLsizei)renderSize);
    glBindVertexArray(0);
}

void MobileGS::clear() {
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
    if (!out) {
        LOGE("Failed to open file for saving: %s", path.c_str());
        return false;
    }
    size_t count = dataToSave.size();
    out.write(reinterpret_cast<const char*>(&count), sizeof(count));
    if (count > 0) {
        out.write(reinterpret_cast<const char*>(dataToSave.data()), (std::streamsize)(count * sizeof(SplatRenderData)));
    }
    LOGI("Saved %zu points to %s", count, path.c_str());

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