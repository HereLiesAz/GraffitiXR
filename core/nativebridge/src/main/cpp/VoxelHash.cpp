#include "include/VoxelHash.h"
#include "include/NativeUtil.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VoxelHash", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VoxelHash", __VA_ARGS__)

static const char* kVertexShader =
    "#version 300 es\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "layout(location = 2) in float aConfidence;\n"
    "layout(location = 3) in vec3 aNormal;\n"
    "layout(location = 4) in float aRadius;\n"
    "uniform mat4 uMvp;\n"
    "uniform float uFocalY;\n"
    "out vec4 vColor;\n"
    "void main() {\n"
    "  vec4 clip = uMvp * vec4(aPosition, 1.0);\n"
    "  gl_Position = clip;\n"
    "  float sz = (aRadius * 2.0 * 1.414) * uFocalY / clip.w;\n"
    "  gl_PointSize = clamp(sz, 2.0, 256.0);\n"
    "  vColor = aColor;\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec4 vColor;\n"
    "out vec4 oColor;\n"
    "void main() {\n"
    "  oColor = vec4(vColor.rgb, 1.0);\n"
    "}\n";

VoxelHash::VoxelHash() {
    mSplatData.reserve(500000);
}

VoxelHash::~VoxelHash() {
    if (mProgram) glDeleteProgram(mProgram);
    if (mPointVbo) glDeleteBuffers(1, &mPointVbo);
}

void VoxelHash::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram) return;

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vs && fs) {
        mProgram = glCreateProgram();
        glAttachShader(mProgram, vs);
        glAttachShader(mProgram, fs);
        glLinkProgram(mProgram);
        glDeleteShader(vs);
        glDeleteShader(fs);

        glGenBuffers(1, &mPointVbo);
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferData(GL_ARRAY_BUFFER, MAX_SPLATS * sizeof(Splat), nullptr, GL_DYNAMIC_DRAW);
    }
}

void VoxelHash::update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize) {
    if (depth.empty() || color.empty()) return;

    const float halfW = depth.cols / 2.0f;
    const float halfH = depth.rows / 2.0f;
    float fx = projMat[0] * halfW;
    float fy = projMat[5] * halfH;
    float cx = (projMat[8]  + 1.0f) * halfW;
    float cy = (-projMat[9] + 1.0f) * halfH;

    const float scaleX = (float)color.cols / depth.cols;
    const float scaleY = (float)color.rows / depth.rows;

    std::vector<std::pair<VoxelKey, Splat>> updates;
    int step = 8;

    for (int r = 0; r < depth.rows - step; r += step) {
        for (int c = 0; c < depth.cols - step; c += step) {
            float d = depth.at<float>(r, c);
            if (d > 0.1f && d < 5.0f) {
                float xc = (static_cast<float>(c) - cx) * d / fx;
                float yc = -(static_cast<float>(r) - cy) * d / fy;
                float zc = -d;
                float xw, yw, zw;
                camToWorld(viewMat, xc, yc, zc, xw, yw, zw);

                VoxelKey key{
                    static_cast<int>(std::floor(xw / voxelSize)),
                    static_cast<int>(std::floor(yw / voxelSize)),
                    static_cast<int>(std::floor(zw / voxelSize))
                };

                int colorR = static_cast<int>(r * scaleY);
                int colorC = static_cast<int>(c * scaleX);
                cv::Vec3b col = color.at<cv::Vec3b>(colorR, colorC);
                float r_f = col[0]/255.0f, g_f = col[1]/255.0f, b_f = col[2]/255.0f;

                // Radius reduced to 0.004f and initial confidence to 0.1f
                updates.push_back({key, {xw, yw, zw, r_f, g_f, b_f, 1.0f, 0.1f, 0.0f, 0.0f, 1.0f, 0.004f}});
            }
        }
    }

    if (!updates.empty()) {
        std::lock_guard<std::mutex> lock(mMutex);
        for (const auto& up : updates) {
            auto it = mVoxelGrid.find(up.first);
            if (it != mVoxelGrid.end()) {
                Splat& s = mSplatData[it->second];
                const Splat& nu = up.second;
                float alpha = 0.15f;
                s.x = s.x * (1.0f-alpha) + nu.x * alpha;
                s.y = s.y * (1.0f-alpha) + nu.y * alpha;
                s.z = s.z * (1.0f-alpha) + nu.z * alpha;
                s.r = s.r * (1.0f-alpha) + nu.r * alpha;
                s.g = s.g * (1.0f-alpha) + nu.g * alpha;
                s.b = s.b * (1.0f-alpha) + nu.b * alpha;
                // Confidence increment reduced to 0.1f (requires 10 hits for max confidence)
                s.confidence = std::min(1.0f, s.confidence + 0.1f);
            } else {
                mSplatData.push_back(up.second);
                mVoxelGrid[up.first] = mSplatData.size() - 1;
            }
        }
        if (mSplatData.size() >= MAX_SPLATS) pruneMap();
    }
}

void VoxelHash::draw(const glm::mat4& mvp, float focalY, int screenHeight) {
    std::lock_guard<std::mutex> lock(mMutex);
    int count = (int)mSplatData.size();
    if (!mProgram || count == 0) return;

    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    glBufferData(GL_ARRAY_BUFFER, count * sizeof(Splat), mSplatData.data(), GL_DYNAMIC_DRAW);

    glUseProgram(mProgram);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uMvp"), 1, GL_FALSE, glm::value_ptr(mvp));
    glUniform1f(glGetUniformLocation(mProgram, "uFocalY"), focalY);

    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);

    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28));
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(32));
    glEnableVertexAttribArray(4); glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(44));

    glDrawArrays(GL_POINTS, 0, count);

    glDisableVertexAttribArray(0); glDisableVertexAttribArray(1); glDisableVertexAttribArray(2); glDisableVertexAttribArray(3); glDisableVertexAttribArray(4);
}

void VoxelHash::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mSplatData.clear();
    mVoxelGrid.clear();
}

void VoxelHash::prune(float threshold) {
    std::lock_guard<std::mutex> lock(mMutex);
    mSplatData.erase(std::remove_if(mSplatData.begin(), mSplatData.end(),
        [threshold](const Splat& s) { return s.confidence < threshold; }), mSplatData.end());
    mVoxelGrid.clear();
    for (int i = 0; i < (int)mSplatData.size(); ++i) {
        VoxelKey key{
            static_cast<int>(std::floor(mSplatData[i].x / 0.005f)), // assuming default size
            static_cast<int>(std::floor(mSplatData[i].y / 0.005f)),
            static_cast<int>(std::floor(mSplatData[i].z / 0.005f))
        };
        mVoxelGrid[key] = i;
    }
}

int VoxelHash::getSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return (int)mSplatData.size();
}

void VoxelHash::pruneMap() {
    // Basic pruning to stay under limit - threshold increased to 0.5f for higher stability requirement
    if (mSplatData.size() > MAX_SPLATS * 0.9) {
        prune(0.5f);
    }
}
