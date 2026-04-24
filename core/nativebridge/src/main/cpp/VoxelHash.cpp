#include "include/VoxelHash.h"
#include "include/NativeUtil.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VoxelHash", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VoxelHash", __VA_ARGS__)

static const char* kVertexShader =
    "#version 300 es\n"
    "precision highp float;\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec4 aColor;\n"
    "layout(location = 2) in float aConfidence;\n"
    "layout(location = 3) in vec3 aNormal;\n"
    "layout(location = 4) in float aRadius;\n"
    "uniform mat4 uMvp;\n"
    "uniform float uFocalY;\n"
    "out lowp vec4 vColor;\n"
    "out mediump float vAlpha;\n"
    "void main() {\n"
    "  vec4 clip = uMvp * vec4(aPosition, 1.0);\n"
    "  gl_Position = clip;\n"
    "  // Robust point sizing for Mali GPUs and high-DPI screens\n"
    "  float sz = (aRadius * 2.0 * 1.732) * uFocalY / max(clip.w, 0.001);\n"
    "  gl_PointSize = clamp(sz, 4.0, 256.0);\n"
    "  vColor = aColor;\n"
    "  vAlpha = clamp(aConfidence, 0.1, 1.0);\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in lowp vec4 vColor;\n"
    "in mediump float vAlpha;\n"
    "out vec4 oColor;\n"
    "void main() {\n"
    "  oColor = vec4(vColor.rgb, vAlpha);\n"
    "}\n";

VoxelHash::VoxelHash() {
    mSplatData.reserve(200000);
}

VoxelHash::~VoxelHash() {
    // Explicitly don't delete here as context might be gone; cleanup handled by GL lifecycle
}

void VoxelHash::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);

    // Check if current handles are valid in this GL context
    if (mProgram != 0 && glIsProgram(mProgram)) {
        return;
    }

    LOGI("Initializing VoxelHash GL handles (new context)");
    mProgram = 0;
    mPointVbo = 0;

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
        // Initial pre-allocation
        glBufferData(GL_ARRAY_BUFFER, 100000 * sizeof(Splat), nullptr, GL_DYNAMIC_DRAW);
    }

    // Force re-upload of any existing data into the new VBO
    mDataDirty = true;
}

void VoxelHash::update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize, float /*lightLevel*/) {
    if (depth.empty() || color.empty()) return;

    mLastVoxelSize = voxelSize;
    const float halfW = depth.cols / 2.0f;
    const float halfH = depth.rows / 2.0f;
    float fx = projMat[0] * halfW;
    float fy = projMat[5] * halfH;
    float cx = (projMat[8]  + 1.0f) * halfW;
    float cy = (-projMat[9] + 1.0f) * halfH;

    const float scaleX = (float)color.cols / depth.cols;
    const float scaleY = (float)color.rows / depth.rows;

    glm::mat4 V = glm::make_mat4(viewMat);
    glm::mat4 invV = glm::inverse(V);
    bool needsPruning = false;
    bool dataChanged = false;

    {
        std::lock_guard<std::mutex> lock(mMutex);

        // 1. Refinement & Decay Loop: Reproject a SUBSET of existing splats to save battery
        int totalSplats = (int)mSplatData.size();
        if (totalSplats > 0) {
            int itemsToProcess = std::max(100, totalSplats / 4); // 25% per update
            for (int i = 0; i < itemsToProcess; ++i) {
                int idx = mNextRefineIndex % totalSplats;
                mNextRefineIndex++;

                if (mSplatData[idx].confidence >= 0.98f) continue;

                glm::vec4 p_cam = V * glm::vec4(mSplatData[idx].x, mSplatData[idx].y, mSplatData[idx].z, 1.0f);
                if (p_cam.z >= -0.1f) continue;

                float u_cam = (p_cam.x * fx / -p_cam.z) + cx;
                float v_cam = (p_cam.y * -fy / -p_cam.z) + cy;

                if (u_cam >= 0 && u_cam < depth.cols && v_cam >= 0 && v_cam < depth.rows) {
                    float d = depth.at<float>((int)v_cam, (int)u_cam);
                    if (d > 0.1f) {
                        float current_d = -p_cam.z;
                        // Use a wider tolerance (12cm) during mapping to allow splats to be born while moving
                        // but keep a tighter refinement (6cm) for established points.
                        float tolerance = (mSplatData[idx].confidence > 0.6f) ? 0.06f : 0.12f;

                        if (std::abs(current_d - d) < tolerance) {
                            mSplatData[idx].confidence = std::min(1.0f, mSplatData[idx].confidence + 0.15f);

                            if (mSplatData[idx].confidence < 0.9f) {
                                glm::vec4 p_target_cam = p_cam * (d / current_d);
                                p_target_cam.w = 1.0f;
                                glm::vec4 p_target_world = invV * p_target_cam;

                                float alpha = 0.15f;
                                mSplatData[idx].x += (p_target_world.x - mSplatData[idx].x) * alpha;
                                mSplatData[idx].y += (p_target_world.y - mSplatData[idx].y) * alpha;
                                mSplatData[idx].z += (p_target_world.z - mSplatData[idx].z) * alpha;
                            }
                            dataChanged = true;
                            continue;
                        }
                    }
                    // Slower decay during motion to prevent pruning legitimate points during quick sweeps
                    mSplatData[idx].confidence -= 0.02f;
                    dataChanged = true;
                    if (mSplatData[idx].confidence <= 0.0f) needsPruning = true;
                }
            }
        }

        // 2. Discovery Loop: Sample depth map to add NEW voxels
        int step = 8; // Slightly larger step for performance at higher frequency
        for (int r = step; r < depth.rows - step; r += step) {
            for (int c = step; c < depth.cols - step; c += step) {
                float d = depth.at<float>(r, c);
                if (d > 0.1f && d < 7.0f) {
                    // Accuracy Fix: Neighborhood stability check.
                    // Only add a voxel if neighbors agree on depth (avoids edges and noise).
                    float d_left = depth.at<float>(r, c - 2);
                    float d_right = depth.at<float>(r, c + 2);
                    if (std::abs(d - d_left) > 0.08f || std::abs(d - d_right) > 0.08f) continue;

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

                    if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
                        if (mSplatData.size() < MAX_SPLATS) {
                            int colorR = static_cast<int>(r * scaleY);
                            int colorC = static_cast<int>(c * scaleX);
                            cv::Vec3b col = color.at<cv::Vec3b>(colorR, colorC);
                            float r_f = col[2]/255.0f, g_f = col[1]/255.0f, b_f = col[0]/255.0f;

                            // Birth confidence: start lower but allow faster growth with motion-friendly tolerance
                            mSplatData.push_back({xw, yw, zw, r_f, g_f, b_f, 1.0f, 0.2f, 0.0f, 0.0f, 1.0f, 0.012f});
                            mVoxelGrid[key] = (int)mSplatData.size() - 1;
                            dataChanged = true;
                        }
                    }
                }
            }
        }

        if (needsPruning || mSplatData.size() >= MAX_SPLATS * 0.95) {
            pruneInternal(0.01f, voxelSize);
            dataChanged = true;
        }

        if (dataChanged) mDataDirty = true;
    }
}

void VoxelHash::draw(const glm::mat4& mvp, float focalY, int screenHeight) {
    std::lock_guard<std::mutex> lock(mMutex);
    int count = (int)mSplatData.size();
    if (!mProgram || count == 0) return;

    glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
    if (mDataDirty) {
        // Mali optimization: use glBufferSubData if size is same, or orphaning if different
        size_t currentSize = count * sizeof(Splat);
        glBufferData(GL_ARRAY_BUFFER, currentSize, mSplatData.data(), GL_DYNAMIC_DRAW);
        mDataDirty = false;
    }

    glUseProgram(mProgram);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uMvp"), 1, GL_FALSE, glm::value_ptr(mvp));
    glUniform1f(glGetUniformLocation(mProgram, "uFocalY"), focalY);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    glEnable(GL_DEPTH_TEST);

    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28));
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(32));
    glEnableVertexAttribArray(4); glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(44));

    glDrawArrays(GL_POINTS, 0, count);

    glDisableVertexAttribArray(0); glDisableVertexAttribArray(1); glDisableVertexAttribArray(2); glDisableVertexAttribArray(3); glDisableVertexAttribArray(4);
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
}

void VoxelHash::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mSplatData.clear();
    mVoxelGrid.clear();
    mDataDirty = true;
}

void VoxelHash::pruneInternal(float threshold, float voxelSize) {
    mSplatData.erase(std::remove_if(mSplatData.begin(), mSplatData.end(),
        [threshold](const Splat& s) { return s.confidence < threshold; }), mSplatData.end());
    mVoxelGrid.clear();
    for (int i = 0; i < (int)mSplatData.size(); ++i) {
        VoxelKey key{
            static_cast<int>(std::floor(mSplatData[i].x / voxelSize)),
            static_cast<int>(std::floor(mSplatData[i].y / voxelSize)),
            static_cast<int>(std::floor(mSplatData[i].z / voxelSize))
        };
        mVoxelGrid[key] = i;
    }
}

void VoxelHash::prune(float threshold) {
    std::lock_guard<std::mutex> lock(mMutex);
    pruneInternal(threshold, mLastVoxelSize);
    mDataDirty = true;
}

int VoxelHash::getSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return (int)mSplatData.size();
}

int VoxelHash::getImmutableSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    int count = 0;
    for (const auto& s : mSplatData) {
        if (s.confidence >= 0.98f) count++;
    }
    return count;
}
