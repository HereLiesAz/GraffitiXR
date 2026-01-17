#include "include/MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <opencv2/imgproc.hpp>
#include <fstream>
#include <random>

#define TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// --- UPDATED SHADERS FOR QUADS (BILLBOARDS) ---

const char* VS_SRC = R"(#version 300 es
// Instanced Attributes (Per Splat)
layout(location = 0) in vec3 aInstancePos;
layout(location = 1) in vec3 aInstanceColor;
layout(location = 2) in float aInstanceScale;
layout(location = 3) in float aInstanceOpacity;

// Vertex Attributes (Per Quad Vertex)
layout(location = 4) in vec2 aQuadVert;

uniform mat4 uView;
uniform mat4 uProj;
uniform vec3 uCamPos; // Needed for billboarding

out vec3 vColor;
out float vAlpha;
out vec2 vUv;

void main() {
    vColor = aInstanceColor;
    vAlpha = aInstanceOpacity;
    vUv = aQuadVert; // -0.5 to 0.5

    // Billboarding Math:
    // 1. Calculate direction from camera to instance center
    vec3 forward = normalize(aInstancePos - uCamPos);

    // 2. Up vector (approximate) and Right vector
    vec3 up = vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(up, forward));
    up = cross(forward, right);

    // 3. Expand vertex
    // aQuadVert is (-0.5, -0.5) to (0.5, 0.5)
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
    // Circular Falloff
    float r2 = dot(vUv, vUv); // Range 0.0 to 0.5 approx (since UV is -0.5 to 0.5)
    if (r2 > 0.25) discard;

    // Soft Gaussian edge
    float alpha = exp(-r2 * 8.0) * vAlpha;
    FragColor = vec4(vColor, alpha);
})";

// Background Shaders remain the same...
const char* BG_VS_SRC = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTexCoord = aTexCoord;
})";

const char* BG_FS_SRC = R"(#version 300 es
precision mediump float;
in vec2 vTexCoord;
uniform sampler2D uTexture;
out vec4 FragColor;
void main() {
    FragColor = texture(uTexture, vTexCoord);
})";

MobileGS::MobileGS() :
        mIsInitialized(false),
        mSortRunning(false),
        mStopThread(false),
        mSortResultReady(false),
        mNewBgAvailable(false),
        mBgTexture(0)
{
    mViewMatrix = glm::mat4(1.0f);
    mProjMatrix = glm::mat4(1.0f);
    mLastUpdateTime = std::chrono::steady_clock::now();
    mSortThread = std::thread(&MobileGS::sortThreadLoop, this);
}

MobileGS::~MobileGS() {
    {
        std::lock_guard<std::mutex> lock(mSortMutex);
        mStopThread = true;
    }
    mSortCV.notify_one();
    if(mSortThread.joinable()) mSortThread.join();

    if (mIsInitialized) {
        glDeleteProgram(mProgram);
        glDeleteProgram(mBgProgram);
        glDeleteBuffers(1, &mVBO);
        glDeleteVertexArrays(1, &mVAO);
        // Clean up Quad buffers
        glDeleteBuffers(1, &mQuadVBO);

        glDeleteBuffers(1, &mBgVBO);
        glDeleteVertexArrays(1, &mBgVAO);
        glDeleteTextures(1, &mBgTexture);
    }
}

void MobileGS::initialize() {
    if (mIsInitialized) return;
    compileShaders();
    mIsInitialized = true;
    LOGI("MobileGS Initialized");
}

void MobileGS::updateCamera(const float* viewMtx, const float* projMtx) {
    memcpy(&mViewMatrix[0][0], viewMtx, 16 * sizeof(float));
    memcpy(&mProjMatrix[0][0], projMtx, 16 * sizeof(float));
}

void MobileGS::processDepthFrame(const cv::Mat& depthMap, int width, int height) {
    auto now = std::chrono::steady_clock::now();
    long elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - mLastUpdateTime).count();
    if (elapsed < 66) return;
    mLastUpdateTime = now;

    std::lock_guard<std::mutex> lock(mDataMutex);

    glm::mat4 invView = glm::inverse(mViewMatrix);
    float fy = mProjMatrix[1][1];
    float fx = mProjMatrix[0][0];
    int step = 16;

    cv::Mat bgFrame;
    {
        std::lock_guard<std::mutex> bgLock(mBgMutex);
        if (!mPendingBgFrame.empty()) bgFrame = mPendingBgFrame.clone();
    }

    for (int y = 0; y < height; y += step) {
        const unsigned short* rowPtr = depthMap.ptr<unsigned short>(y);

        for (int x = 0; x < width; x += step) {
            unsigned short d_raw = rowPtr[x];
            if (d_raw == 0 || d_raw > 4000) continue;

            float z = d_raw * 0.001f;

            float ndc_x = ((float)x / width) * 2.0f - 1.0f;
            float ndc_y = 1.0f - ((float)y / height) * 2.0f;

            glm::vec4 viewPos;
            viewPos.x = -z * (ndc_x / fx);
            viewPos.y = -z * (ndc_y / fy);
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
                SplatGaussian& g = mRenderGaussians[idx];
                g.opacity = std::min(1.0f, g.opacity + 0.1f);
                g.position = glm::mix(g.position, glm::vec3(worldPos), 0.1f);

                if (!bgFrame.empty()) {
                    int imgX = (int)((float)x / width * bgFrame.cols);
                    int imgY = (int)((float)y / height * bgFrame.rows);
                    if (imgX >=0 && imgX < bgFrame.cols && imgY >=0 && imgY < bgFrame.rows) {
                        const cv::Vec4b& col = bgFrame.at<cv::Vec4b>(imgY, imgX);
                        glm::vec3 newCol = glm::vec3(col[0]/255.0f, col[1]/255.0f, col[2]/255.0f);
                        g.color = glm::mix(g.color, newCol, 0.1f);
                    }
                }
            } else {
                if (mRenderGaussians.size() >= MAX_POINTS) continue;

                SplatGaussian g;
                g.position = glm::vec3(worldPos);
                g.scale = glm::vec3(VOXEL_SIZE * 1.5f);
                g.opacity = 0.2f;

                if (!bgFrame.empty()) {
                    int imgX = (int)((float)x / width * bgFrame.cols);
                    int imgY = (int)((float)y / height * bgFrame.rows);
                    if (imgX >=0 && imgX < bgFrame.cols && imgY >=0 && imgY < bgFrame.rows) {
                        const cv::Vec4b& col = bgFrame.at<cv::Vec4b>(imgY, imgX);
                        g.color = glm::vec3(col[0]/255.0f, col[1]/255.0f, col[2]/255.0f);
                    } else {
                        g.color = glm::vec3(0.5f);
                    }
                } else {
                    g.color = glm::vec3(0.0f, 1.0f, 0.5f);
                }

                mRenderGaussians.push_back(g);
                mVoxelGrid[key] = mRenderGaussians.size() - 1;
            }
        }
    }
}

void MobileGS::setBackgroundFrame(const cv::Mat& frame) {
    std::lock_guard<std::mutex> lock(mBgMutex);
    frame.copyTo(mPendingBgFrame);
    mNewBgAvailable = true;
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

    // 1. Instance Buffer (Splat Data)
    glGenVertexArrays(1, &mVAO);
    glGenBuffers(1, &mVBO);

    // 2. Geometry Buffer (The single Quad)
    glGenBuffers(1, &mQuadVBO);

    // Define the Quad Vertices (-0.5 to 0.5)
    float quadVerts[] = {
            -0.5f, -0.5f,
            0.5f, -0.5f,
            -0.5f,  0.5f,
            0.5f,  0.5f
    };

    glBindVertexArray(mVAO);

    // Bind Quad VBO to Attribute 4
    glBindBuffer(GL_ARRAY_BUFFER, mQuadVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quadVerts), quadVerts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 2, GL_FLOAT, GL_FALSE, 0, (void*)0);

    // Bind Instance VBO to Attributes 0,1,2,3
    glBindBuffer(GL_ARRAY_BUFFER, mVBO);

    // Stride is 8 floats: Pos(3), Color(3), Scale(1), Opacity(1)
    int stride = 8 * sizeof(float);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, (void*)0);
    glVertexAttribDivisor(0, 1); // Step once per instance

    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, stride, (void*)(3*sizeof(float)));
    glVertexAttribDivisor(1, 1);

    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, stride, (void*)(6*sizeof(float)));
    glVertexAttribDivisor(2, 1);

    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, stride, (void*)(7*sizeof(float)));
    glVertexAttribDivisor(3, 1);

    glBindVertexArray(0); // Unbind

    // Background Setup
    mBgProgram = createProg(BG_VS_SRC, BG_FS_SRC);
    glGenVertexArrays(1, &mBgVAO);
    glGenBuffers(1, &mBgVBO);

    float bgVerts[] = {
            -1, -1, 0, 1,
            1, -1, 1, 1,
            -1,  1, 0, 0,
            1,  1, 1, 0
    };
    glBindVertexArray(mBgVAO);
    glBindBuffer(GL_ARRAY_BUFFER, mBgVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(bgVerts), bgVerts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4*sizeof(float), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4*sizeof(float), (void*)(2*sizeof(float)));

    glGenTextures(1, &mBgTexture);
    glBindTexture(GL_TEXTURE_2D, mBgTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

void MobileGS::uploadBgTexture() {
    std::lock_guard<std::mutex> lock(mBgMutex);
    if (!mNewBgAvailable || mPendingBgFrame.empty()) return;

    glBindTexture(GL_TEXTURE_2D, mBgTexture);
    GLint format = GL_RGB;
    if (mPendingBgFrame.channels() == 4) format = GL_RGBA;
    else if (mPendingBgFrame.channels() == 1) format = GL_LUMINANCE;

    glTexImage2D(GL_TEXTURE_2D, 0, format, mPendingBgFrame.cols, mPendingBgFrame.rows, 0, format, GL_UNSIGNED_BYTE, mPendingBgFrame.data);
    mNewBgAvailable = false;
}

void MobileGS::drawBackground() {
    glDisable(GL_DEPTH_TEST);
    glDepthMask(GL_FALSE);
    glUseProgram(mBgProgram);
    glBindVertexArray(mBgVAO);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, mBgTexture);
    glUniform1i(glGetUniformLocation(mBgProgram, "uTexture"), 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDepthMask(GL_TRUE);
}

void MobileGS::sortThreadLoop() {
    while (true) {
        {
            std::unique_lock<std::mutex> lock(mSortMutex);
            mSortCV.wait(lock, [this] { return mStopThread || mSortRunning; });
            if (mStopThread) return;
        }

        {
            std::lock_guard<std::mutex> dataLock(mDataMutex);
            if (mSortListBack.size() != mRenderGaussians.size()) {
                mSortListBack.resize(mRenderGaussians.size());
                for(size_t i=0; i<mSortListBack.size(); ++i) mSortListBack[i].index = i;
            }
        }

        glm::mat4 view = mSortViewMatrix;
        std::vector<float> depths;
        {
            std::lock_guard<std::mutex> dataLock(mDataMutex);
            depths.reserve(mSortListBack.size());
            for(size_t i=0; i<mSortListBack.size(); ++i) {
                int idx = mSortListBack[i].index;
                if (idx >= mRenderGaussians.size()) { depths.push_back(0); continue; }
                const auto& p = mRenderGaussians[idx].position;
                float z = view[0][2] * p.x + view[1][2] * p.y + view[2][2] * p.z + view[3][2];
                depths.push_back(z);
            }
        }

        for(size_t i=0; i<mSortListBack.size(); ++i) {
            mSortListBack[i].depth = depths[i];
        }

        std::sort(mSortListBack.begin(), mSortListBack.end(), [](const Sortable& a, const Sortable& b){
            return a.depth > b.depth;
        });

        mSortResultReady = true;
        mSortRunning = false;
    }
}

void MobileGS::draw() {
    if (!mIsInitialized) initialize();

    uploadBgTexture();
    drawBackground();

    std::vector<float> data;
    bool useSort = false;
    size_t count = 0;

    {
        std::lock_guard<std::mutex> lock(mDataMutex);
        if (mRenderGaussians.empty()) return;

        count = mRenderGaussians.size();
        if (mSortResultReady) {
            std::swap(mSortListFront, mSortListBack);
            mSortResultReady = false;
        }

        if (!mSortRunning) {
            {
                std::lock_guard<std::mutex> sortLock(mSortMutex);
                mSortViewMatrix = mViewMatrix;
                mSortRunning = true;
            }
            mSortCV.notify_one();
        }

        data.reserve(count * 8);
        useSort = (mSortListFront.size() == count);

        for(size_t i = 0; i < count; ++i) {
            const auto& g = mRenderGaussians[useSort ? mSortListFront[i].index : i];
            data.push_back(g.position.x);
            data.push_back(g.position.y);
            data.push_back(g.position.z);
            data.push_back(g.color.x);
            data.push_back(g.color.y);
            data.push_back(g.color.z);
            data.push_back(g.scale.x);
            data.push_back(g.opacity);
        }
    }

    glEnable(GL_DEPTH_TEST);
    glUseProgram(mProgram);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uView"), 1, GL_FALSE, &mViewMatrix[0][0]);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uProj"), 1, GL_FALSE, &mProjMatrix[0][0]);

    // We need Camera Position for billboarding
    glm::mat4 invView = glm::inverse(mViewMatrix);
    glm::vec3 camPos = glm::vec3(invView[3]);
    glUniform3fv(glGetUniformLocation(mProgram, "uCamPos"), 1, &camPos[0]);

    glBindVertexArray(mVAO);
    glBindBuffer(GL_ARRAY_BUFFER, mVBO);
    glBufferData(GL_ARRAY_BUFFER, data.size() * sizeof(float), data.data(), GL_DYNAMIC_DRAW);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // INSTANCED DRAWING: 4 vertices (the quad) drawn N times
    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);

    glDisable(GL_BLEND);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);
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
    if (count > 0) {
        out.write(reinterpret_cast<const char*>(mRenderGaussians.data()), count * sizeof(SplatGaussian));
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

    mRenderGaussians.resize(count);
    mVoxelGrid.clear();

    if (count > 0) {
        in.read(reinterpret_cast<char*>(mRenderGaussians.data()), count * sizeof(SplatGaussian));

        for (int i=0; i<count; ++i) {
            const auto& g = mRenderGaussians[i];
            VoxelKey key;
            key.x = static_cast<int>(std::floor(g.position.x / VOXEL_SIZE));
            key.y = static_cast<int>(std::floor(g.position.y / VOXEL_SIZE));
            key.z = static_cast<int>(std::floor(g.position.z / VOXEL_SIZE));
            mVoxelGrid[key] = i;
        }
    }
    return true;
}