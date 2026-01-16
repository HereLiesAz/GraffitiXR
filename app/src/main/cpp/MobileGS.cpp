#include "include/MobileGS.h"
#include <algorithm>
#include <android/log.h>
#include <opencv2/imgproc.hpp>
#include <fstream>
#include <random>

#define TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Vertex Shader: Projects 3D Gaussians to 2D Quads (via PointSprites)
const char* VS_SRC = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aColor;
layout(location = 2) in float aScale;
uniform mat4 uView;
uniform mat4 uProj;
out vec3 vColor;
void main() {
    vec4 viewPos = uView * vec4(aPos, 1.0);
    gl_Position = uProj * viewPos;

    // Perspective correct scaling
    float dist = length(viewPos.xyz);
    // 800.0 is an empirical screen-height factor for splat size
    gl_PointSize = (aScale * 800.0) / clamp(dist, 0.1, 100.0);

    vColor = aColor;
})";

// Fragment Shader: Soft circular falloff
const char* FS_SRC = R"(#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 FragColor;
void main() {
    vec2 coord = gl_PointCoord - vec2(0.5);
    float r2 = dot(coord, coord);
    if (r2 > 0.25) discard; // Clip to circle

    // Gaussian-like falloff for soft edges
    float alpha = exp(-r2 * 8.0);
    FragColor = vec4(vColor, alpha);
})";

// Background Vertex Shader
const char* BG_VS_SRC = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTexCoord = aTexCoord;
})";

// Background Fragment Shader
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
        mNewDataAvailable(false),
        mSortRunning(false),
        mStopThread(false),
        mSortResultReady(false),
        mNewBgAvailable(false),
        mBgTexture(0),
        mFrameCounter(0)
{
    mViewMatrix = glm::mat4(1.0f);
    mProjMatrix = glm::mat4(1.0f);
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

void MobileGS::addGaussians(const std::vector<SplatGaussian>& gaussians) {
    std::lock_guard<std::mutex> lock(mDataMutex);
    mIncomingGaussians.insert(mIncomingGaussians.end(), gaussians.begin(), gaussians.end());
    mNewDataAvailable = true;
}

void MobileGS::processDepthFrame(const cv::Mat& depthMap, int width, int height) {
    // Throttle: Process every 10th frame to keep UI smooth
    if (++mFrameCounter % 10 != 0) return;

    std::vector<SplatGaussian> newSplats;

    // Pre-calculate projection constants
    glm::mat4 invView = glm::inverse(mViewMatrix);
    float fy = mProjMatrix[1][1];
    float fx = mProjMatrix[0][0];

    // Subsample depth map (Grid density)
    // 16px step = 15x20 grid for 240x320 depth image (~300 points per frame)
    int step = 16;

    // Lock background frame to sample colors
    cv::Mat bgFrame;
    {
        std::lock_guard<std::mutex> lock(mBgMutex);
        if (!mPendingBgFrame.empty()) bgFrame = mPendingBgFrame.clone();
    }

    // --- OPTIMIZED LOOP ---
    // Using direct pointer access instead of .at()
    for (int y = 0; y < height; y += step) {
        const unsigned short* rowPtr = depthMap.ptr<unsigned short>(y);

        for (int x = 0; x < width; x += step) {
            unsigned short d_raw = rowPtr[x];

            // Filter invalid or too distant points (>5m)
            if (d_raw == 0 || d_raw > 5000) continue;

            // Depth is in millimeters, convert to meters
            float z = d_raw * 0.001f;

            // Unproject from Screen Space to View Space
            // NDC coordinates (-1 to 1)
            float ndc_x = ((float)x / width) * 2.0f - 1.0f;
            float ndc_y = 1.0f - ((float)y / height) * 2.0f; // Flip Y for GL

            glm::vec4 viewPos;
            viewPos.x = -z * (ndc_x / fx);
            viewPos.y = -z * (ndc_y / fy);
            viewPos.z = -z; // Camera looks down -Z
            viewPos.w = 1.0f;

            // Transform to World Space
            glm::vec4 worldPos = invView * viewPos;

            SplatGaussian g;
            g.position = glm::vec3(worldPos);
            g.scale = glm::vec3(0.03f); // 3cm radius splat
            g.opacity = 0.8f; // High opacity for solid feel

            // Colorize from Camera Image
            if (!bgFrame.empty()) {
                // Map depth UV to Image UV
                int imgX = (int)((float)x / width * bgFrame.cols);
                int imgY = (int)((float)y / height * bgFrame.rows);

                // Boundary check
                if (imgX >=0 && imgX < bgFrame.cols && imgY >=0 && imgY < bgFrame.rows) {
                    // Assuming RGBA (CV_8UC4) from JNI
                    const cv::Vec4b& col = bgFrame.at<cv::Vec4b>(imgY, imgX);
                    g.color = glm::vec3(col[0]/255.0f, col[1]/255.0f, col[2]/255.0f);
                } else {
                    g.color = glm::vec3(0.5f); // Grey fallback
                }
            } else {
                g.color = glm::vec3(0.0f, 1.0f, 0.5f); // Hacker Green fallback
            }

            newSplats.push_back(g);
        }
    }

    addGaussians(newSplats);
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
    glGenVertexArrays(1, &mVAO);
    glGenBuffers(1, &mVBO);

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
    if (mPendingBgFrame.channels() == 4) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, mPendingBgFrame.cols, mPendingBgFrame.rows, 0, GL_RGBA, GL_UNSIGNED_BYTE, mPendingBgFrame.data);
    } else if (mPendingBgFrame.channels() == 3) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, mPendingBgFrame.cols, mPendingBgFrame.rows, 0, GL_RGB, GL_UNSIGNED_BYTE, mPendingBgFrame.data);
    } else if (mPendingBgFrame.channels() == 1) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, mPendingBgFrame.cols, mPendingBgFrame.rows, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, mPendingBgFrame.data);
    }
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

        if (mSortListBack.size() != mRenderGaussians.size()) {
            mSortListBack.resize(mRenderGaussians.size());
            for(size_t i=0; i<mSortListBack.size(); ++i) mSortListBack[i].index = i;
        }

        glm::mat4 view = mSortViewMatrix;

        for(size_t i=0; i<mSortListBack.size(); ++i) {
            int idx = mSortListBack[i].index;
            if (idx >= mRenderGaussians.size()) continue;
            const auto& p = mRenderGaussians[idx].position;
            // Project Z depth
            float z = view[0][2] * p.x + view[1][2] * p.y + view[2][2] * p.z + view[3][2];
            mSortListBack[i].depth = z;
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

    {
        std::lock_guard<std::mutex> lock(mDataMutex);
        if (mNewDataAvailable) {
            if (!mIncomingGaussians.empty()) {
                mRenderGaussians.insert(mRenderGaussians.end(), mIncomingGaussians.begin(), mIncomingGaussians.end());
                mIncomingGaussians.clear();
            }
            mNewDataAvailable = false;
        }
    }

    drawBackground();

    if (mRenderGaussians.empty()) return;

    if (mSortResultReady) {
        std::swap(mSortListFront, mSortListBack);
        mSortResultReady = false;
    }

    if (!mSortRunning) {
        {
            std::lock_guard<std::mutex> lock(mSortMutex);
            mSortViewMatrix = mViewMatrix;
            mSortRunning = true;
        }
        mSortCV.notify_one();
    }

    std::vector<float> data;
    data.reserve(mRenderGaussians.size() * 7);

    const std::vector<Sortable>& sortList = (mSortListFront.size() == mRenderGaussians.size()) ? mSortListFront : std::vector<Sortable>();
    bool useSort = !sortList.empty();

    for(size_t i = 0; i < mRenderGaussians.size(); ++i) {
        const auto& g = mRenderGaussians[useSort ? sortList[i].index : i];

        data.push_back(g.position.x);
        data.push_back(g.position.y);
        data.push_back(g.position.z);

        data.push_back(g.color.x);
        data.push_back(g.color.y);
        data.push_back(g.color.z);

        data.push_back(g.scale.x);
    }

    glEnable(GL_DEPTH_TEST);
    glUseProgram(mProgram);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uView"), 1, GL_FALSE, &mViewMatrix[0][0]);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uProj"), 1, GL_FALSE, &mProjMatrix[0][0]);

    glBindVertexArray(mVAO);
    glBindBuffer(GL_ARRAY_BUFFER, mVBO);
    glBufferData(GL_ARRAY_BUFFER, data.size() * sizeof(float), data.data(), GL_DYNAMIC_DRAW);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7*sizeof(float), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 7*sizeof(float), (void*)(3*sizeof(float)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, 7*sizeof(float), (void*)(6*sizeof(float)));

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glDrawArrays(GL_POINTS, 0, mRenderGaussians.size());

    glDisable(GL_BLEND);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(mDataMutex);
    mRenderGaussians.clear();
    mIncomingGaussians.clear();
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
    mRenderGaussians.resize(count);
    if (count > 0) {
        in.read(reinterpret_cast<char*>(mRenderGaussians.data()), count * sizeof(SplatGaussian));
    }
    mNewDataAvailable = true;
    return true;
}