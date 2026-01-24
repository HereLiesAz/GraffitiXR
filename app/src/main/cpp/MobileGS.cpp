#include "include/MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <opencv2/imgproc.hpp>
#include <fstream>
#include <random>

#define TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

const float CONFIDENCE_THRESHOLD = 0.6f;
const float CONFIDENCE_INCREMENT = 0.05f;

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
        mSortRunning(false), mStopThread(false), mSortResultReady(false),
        mNewBgAvailable(false), mHasBgData(false), mIsInitialized(false)
{
    mLastUpdateTime = std::chrono::steady_clock::now();
    mSortThread = std::thread(&MobileGS::sortThreadLoop, this);
}

MobileGS::~MobileGS() {
    mStopThread = true;
    mSortCV.notify_all();
    if(mSortThread.joinable()) mSortThread.join();

    if (mIsInitialized) {
        glDeleteProgram(mProgram);
        glDeleteBuffers(1, &mVBO);
        glDeleteVertexArrays(1, &mVAO);
        glDeleteBuffers(1, &mQuadVBO);
    }
}

void MobileGS::initialize() {
    if (mIsInitialized) return;
    compileShaders();
    mIsInitialized = true;
    LOGI("MobileGS Initialized");
}

void MobileGS::updateCamera(const float* viewMtx, const float* projMtx) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    memcpy(&mViewMatrix[0][0], viewMtx, 16 * sizeof(float));
    memcpy(&mProjMatrix[0][0], projMtx, 16 * sizeof(float));
}

void MobileGS::processDepthFrame(const cv::Mat& depthMap, int width, int height) {
    auto now = std::chrono::steady_clock::now();
    if (std::chrono::duration_cast<std::chrono::milliseconds>(now - mLastUpdateTime).count() < 100) return;
    mLastUpdateTime = now;

    std::lock_guard<std::mutex> lock(mDataMutex);
    if (mProjMatrix[0][0] == 0) return;

    glm::mat4 invView = glm::inverse(mViewMatrix);
    // Standard GL Projection Matrix Principal Point Layout (p[2][0], p[2][1])
    float p00 = mProjMatrix[0][0];
    float p11 = mProjMatrix[1][1];
    float p20 = mProjMatrix[2][0];
    float p21 = mProjMatrix[2][1];

    int step = 20; // Drastically increased step to reduce load and "random points"
    for (int y = 0; y < height; y += step) {
        const uint16_t* rowPtr = depthMap.ptr<uint16_t>(y);
        for (int x = 0; x < width; x += step) {
            uint16_t d_raw = rowPtr[x];
            if (d_raw < 200 || d_raw > 2500) continue; // Tighter filter: 20cm to 2.5m

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
                SplatGaussian& g = mRenderGaussians[it->second];
                g.opacity = std::min(1.0f, g.opacity + CONFIDENCE_INCREMENT);
                g.position = glm::mix(g.position, glm::vec3(worldPos), 0.1f);
            } else if (mRenderGaussians.size() < MAX_POINTS) {
                SplatGaussian g;
                g.position = glm::vec3(worldPos);
                g.scale = glm::vec3(VOXEL_SIZE * 1.8f);
                g.opacity = CONFIDENCE_INCREMENT;
                g.color = glm::vec3(0.0f, 0.8f, 1.0f);
                mRenderGaussians.push_back(g);
                mVoxelGrid[key] = (int)(mRenderGaussians.size() - 1);
            }
        }
    }
}

void MobileGS::setBackgroundFrame(const cv::Mat& frame) {
    std::lock_guard<std::mutex> lock(mBgMutex);
    frame.copyTo(mPendingBgFrame);
    mNewBgAvailable = true;
    mHasBgData = true;
}

void MobileGS::processImage(const cv::Mat& image, int width, int height, int64_t timestamp) {
    std::lock_guard<std::mutex> lock(mBgMutex);
    image.copyTo(mPendingBgFrame);
    mPendingTimestamp = timestamp;
    mNewBgAvailable = true;
    mHasBgData = true;
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
    int stride = 8 * sizeof(float);
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
    while (!mStopThread) {
        {
            std::unique_lock<std::mutex> lock(mSortMutex);
            mSortCV.wait(lock, [this] { return mStopThread || mSortRunning.load(); });
            if (mStopThread) return;
        }

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
            mSortListBack = std::move(sorted);
            mSortResultReady = true;
        }
        mSortRunning = false;
    }
}

void MobileGS::draw() {
    if (!mIsInitialized) initialize();

    std::vector<float> data;
    glm::mat4 view, proj;
    size_t count = 0;

    {
        std::lock_guard<std::mutex> lock(mDataMutex);
        if (mRenderGaussians.empty()) return;

        view = mViewMatrix;
        proj = mProjMatrix;

        if (mSortResultReady.exchange(false)) {
            mSortListFront = std::move(mSortListBack);
        }

        bool canUseSort = (mSortListFront.size() == mRenderGaussians.size());
        data.reserve(mRenderGaussians.size() * 8);
        for(size_t i = 0; i < mRenderGaussians.size(); ++i) {
            int idx = canUseSort ? mSortListFront[i].index : (int)i;
            if (idx >= mRenderGaussians.size()) continue;
            const auto& g = mRenderGaussians[idx];
            if (g.opacity < CONFIDENCE_THRESHOLD) continue;

            data.push_back(g.position.x); data.push_back(g.position.y); data.push_back(g.position.z);
            data.push_back(g.color.x); data.push_back(g.color.y); data.push_back(g.color.z);
            data.push_back(g.scale.x); data.push_back(g.opacity);
            count++;
        }

        if (!mSortRunning) {
            {
                std::lock_guard<std::mutex> sortLock(mSortMutex);
                mSortViewMatrix = mViewMatrix;
                mSortRunning = true;
            }
            mSortCV.notify_one();
        }
    }

    if (count == 0) return;

    // Save ALL relevant GL State to prevent interfering with ARCore Background
    GLint prevProgram, prevVAO, prevVBO, prevBlendSrc, prevBlendDst;
    GLboolean prevDepthTest, prevBlend, prevCull;
    glGetIntegerv(GL_CURRENT_PROGRAM, &prevProgram);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &prevVAO);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prevVBO);
    glGetBooleanv(GL_DEPTH_TEST, &prevDepthTest);
    glGetBooleanv(GL_BLEND, &prevBlend);
    glGetBooleanv(GL_CULL_FACE, &prevCull);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &prevBlendSrc);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &prevBlendDst);

    glEnable(GL_DEPTH_TEST);
    glDepthMask(GL_TRUE);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glUseProgram(mProgram);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uView"), 1, GL_FALSE, &view[0][0]);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uProj"), 1, GL_FALSE, &proj[0][0]);

    glm::vec3 camPos = glm::vec3(glm::inverse(view)[3]);
    glUniform3fv(glGetUniformLocation(mProgram, "uCamPos"), 1, &camPos[0]);

    glBindVertexArray(mVAO);
    glBindBuffer(GL_ARRAY_BUFFER, mVBO);
    glBufferData(GL_ARRAY_BUFFER, data.size() * sizeof(float), data.data(), GL_STREAM_DRAW);

    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, (GLsizei)count);

    // RESTORE GL State perfectly
    glUseProgram(prevProgram);
    glBindVertexArray(prevVAO);
    glBindBuffer(GL_ARRAY_BUFFER, prevVBO);
    if (!prevDepthTest) glDisable(GL_DEPTH_TEST);
    if (!prevBlend) glDisable(GL_BLEND);
    else glBlendFunc(prevBlendSrc, prevBlendDst);
    if (prevCull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(mDataMutex);
    mRenderGaussians.clear();
    mVoxelGrid.clear();
}

bool MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    size_t count = mRenderGaussians.size();
    out.write(reinterpret_cast<const char*>(&count), sizeof(count));
    if (count > 0) out.write(reinterpret_cast<const char*>(mRenderGaussians.data()), (std::streamsize)(count * sizeof(SplatGaussian)));
    return true;
}

bool MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    size_t count = 0;
    in.read(reinterpret_cast<char*>(&count), sizeof(count));
    if (count > MAX_POINTS) count = MAX_POINTS;
    mRenderGaussians.resize(count);
    mVoxelGrid.clear();
    if (count > 0) {
        in.read(reinterpret_cast<char*>(mRenderGaussians.data()), (std::streamsize)(count * sizeof(SplatGaussian)));
        for (int i=0; i<(int)count; ++i) {
            const auto& g = mRenderGaussians[i];
            VoxelKey key = { (int)std::floor(g.position.x/VOXEL_SIZE), (int)std::floor(g.position.y/VOXEL_SIZE), (int)std::floor(g.position.z/VOXEL_SIZE) };
            mVoxelGrid[key] = i;
        }
    }
    return true;
}
