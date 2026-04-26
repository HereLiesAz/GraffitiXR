#include "include/VoxelHash.h"
#include "include/NativeUtil.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <fstream>
#define GLM_ENABLE_EXPERIMENTAL
#include <glm/gtc/matrix_inverse.hpp>
#include <glm/gtc/quaternion.hpp>
#include <glm/gtx/quaternion.hpp>
#include <glm/gtx/norm.hpp>
#include <glm/gtx/component_wise.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MobileGS", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MobileGS", __VA_ARGS__)

// ~~~ Real Gaussian Splatting Implementation (Mobile-Appropriate) ~~~

static const char* kVertexShader =
    "#version 300 es\n"
    "precision highp float;\n"
    "layout(location = 0) in vec2 aQuadPos;\n"
    "layout(location = 1) in vec3 aPosition;\n"
    "layout(location = 2) in vec4 aColor;\n"
    "layout(location = 3) in vec3 aScale;\n"
    "layout(location = 4) in vec4 aRot;\n"
    "layout(location = 5) in float aConfidence;\n"
    "uniform mat4 uMvp;\n"
    "uniform mat4 uView;\n"
    "uniform float uFocalY;\n"
    "out vec4 vColor;\n"
    "out vec2 vLocalPos;\n"
    "\n"
    "mat3 quatToMat3(vec4 q) {\n"
    "  float x = q.x, y = q.y, z = q.z, w = q.w;\n"
    "  return mat3(\n"
    "    1.0-2.0*(y*y+z*z), 2.0*(x*y-w*z), 2.0*(x*z+w*y),\n"
    "    2.0*(x*y+w*z), 1.0-2.0*(x*x+z*z), 2.0*(y*z-w*x),\n"
    "    2.0*(x*z-w*y), 2.0*(y*z+w*x), 1.0-2.0*(x*x+y*y)\n"
    "  );\n"
    "}\n"
    "\n"
    "void main() {\n"
    "  vec4 posCam = uView * vec4(aPosition, 1.0);\n"
    "  if (posCam.z >= -0.1) { gl_Position = vec4(0,0,0,1); return; }\n"
    "\n"
    "  mat3 R = quatToMat3(aRot);\n"
    "  mat3 S = mat3(aScale.x, 0.0, 0.0, 0.0, aScale.y, 0.0, 0.0, 0.0, aScale.z);\n"
    "  mat3 M = R * S;\n"
    "  mat3 Sigma3D = M * transpose(M);\n"
    "\n"
    "  mat3 W = mat3(uView);\n"
    "  float J11 = uFocalY / posCam.z;\n"
    "  float J13 = - (posCam.x * uFocalY) / (posCam.z * posCam.z);\n"
    "  float J22 = uFocalY / posCam.z;\n"
    "  float J23 = - (posCam.y * uFocalY) / (posCam.z * posCam.z);\n"
    "  mat3 J = mat3(J11, 0.0, J13, 0.0, J22, J23, 0.0, 0.0, 0.0);\n"
    "  mat3 T = J * W;\n"
    "  mat3 Sigma2D = T * Sigma3D * transpose(T);\n"
    "  Sigma2D[0][0] += 0.3; Sigma2D[1][1] += 0.3;\n"
    "\n"
    "  float mid = 0.5 * (Sigma2D[0][0] + Sigma2D[1][1]);\n"
    "  float det = Sigma2D[0][0] * Sigma2D[1][1] - Sigma2D[0][1] * Sigma2D[0][1];\n"
    "  float delta = max(0.0, mid * mid - det);\n"
    "  float lambda1 = mid + sqrt(delta);\n"
    "  float lambda2 = mid - sqrt(delta);\n"
    "  float scale2D_x = sqrt(max(0.1, lambda1));\n"
    "  float scale2D_y = sqrt(max(0.1, lambda2));\n"
    "  float angle = 0.5 * atan(2.0 * Sigma2D[0][1], Sigma2D[0][0] - Sigma2D[1][1]);\n"
    "\n"
    "  vec2 cosSin = vec2(cos(angle), sin(angle));\n"
    "  mat2 rot2D = mat2(cosSin.x, cosSin.y, -cosSin.y, cosSin.x);\n"
    "  vec2 offset = rot2D * (aQuadPos * vec2(scale2D_x, scale2D_y) * 3.0);\n"
    "\n"
    "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
    "  gl_Position.xy += offset * gl_Position.w / uFocalY;\n"
    "  vColor = vec4(max(vec3(0.0), aColor.rgb), aColor.a);\n"
    "  vLocalPos = aQuadPos * 3.0;\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec4 vColor;\n"
    "in vec2 vLocalPos;\n"
    "layout(location = 0) out vec4 oColor;\n"
    "void main() {\n"
    "  float power = -0.5 * dot(vLocalPos, vLocalPos);\n"
    "  float g = exp(power);\n"
    "  if (g < 0.1) discard;\n"
    "  oColor = vec4(vColor.rgb, vColor.a * g);\n"
    "}\n";

VoxelHash::VoxelHash() : mNextRefineIndex(0) {
    mSplatData.reserve(MAX_SPLATS);
}

VoxelHash::~VoxelHash() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram) glDeleteProgram(mProgram);
    if (mPointVbo) glDeleteBuffers(1, &mPointVbo);
    if (mQuadVbo) glDeleteBuffers(1, &mQuadVbo);
    if (mVao) glDeleteVertexArrays(1, &mVao);
}

void VoxelHash::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram != 0 && glIsProgram(mProgram)) return;
    LOGI("Initializing Mobile Real-GS Engine");

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vs && fs) {
        mProgram = glCreateProgram();
        glAttachShader(mProgram, vs);
        glAttachShader(mProgram, fs);
        glLinkProgram(mProgram);
        glDeleteShader(vs);
        glDeleteShader(fs);

        glGenVertexArrays(1, &mVao);
        glBindVertexArray(mVao);

        glGenBuffers(1, &mPointVbo);
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(MAX_SPLATS * sizeof(Splat)), nullptr, GL_DYNAMIC_DRAW);

        glGenBuffers(1, &mQuadVbo);
        glBindBuffer(GL_ARRAY_BUFFER, mQuadVbo);
        float quad[] = { -0.5f,-0.5f, 0.5f,-0.5f, -0.5f,0.5f, 0.5f,0.5f };
        glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);

        // Quad positions (Attribute 0)
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (void*)0);

        // Instanced Splat data
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0); // pos
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12)); // color
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28)); // scale
        glEnableVertexAttribArray(4); glVertexAttribPointer(4, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(40)); // rot
        glEnableVertexAttribArray(5); glVertexAttribPointer(5, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(56)); // confidence

        for(int i=1; i<=5; ++i) glVertexAttribDivisor(i, 1);
        glBindVertexArray(0);
    }
    mDataDirty = true;
}

void VoxelHash::update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize, float initialConfidence) {
    if (depth.empty() || color.empty()) return;
    mLastVoxelSize = voxelSize;
    const float halfW = (float)depth.cols / 2.0f;
    const float halfH = (float)depth.rows / 2.0f;
    float fx = projMat[0] * halfW;
    float fy = projMat[5] * halfH;
    float cx = (projMat[8]  + 1.0f) * halfW;
    float cy = (-projMat[9] + 1.0f) * halfH;
    const float scaleX = (float)color.cols / depth.cols;
    const float scaleY = (float)color.rows / depth.rows;

    glm::mat4 V = glm::make_mat4(viewMat);
    glm::mat4 invV = glm::inverse(V);
    bool dataChanged = false;

    {
        std::lock_guard<std::mutex> lock(mMutex);

        // 1. Instant Discovery Loop (Depth-to-Gaussian)
        int step = 8;
        for (int r = step; r < depth.rows - step; r += step) {
            for (int c = step; c < depth.cols - step; c += step) {
                float d = depth.at<float>(r, c);
                if (d > 0.1f && d < 6.0f) {
                    float xc = (static_cast<float>(c) - cx) * d / fx;
                    float yc = -(static_cast<float>(r) - cy) * d / fy;
                    float zc = -d;
                    glm::vec4 p_world = invV * glm::vec4(xc, yc, zc, 1.0f);

                    VoxelKey key{ static_cast<int>(std::floor(p_world.x / voxelSize)),
                                 static_cast<int>(std::floor(p_world.y / voxelSize)),
                                 static_cast<int>(std::floor(p_world.z / voxelSize)) };

                    if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
                        if (mSplatData.size() < MAX_SPLATS) {
                            Splat s{};
                            s.x = p_world.x; s.y = p_world.y; s.z = p_world.z;

                            int colorR = static_cast<int>(r * scaleY);
                            int colorC = static_cast<int>(c * scaleX);
                            const auto& col = color.at<cv::Vec3b>(std::min(colorR, color.rows-1), std::min(colorC, color.cols-1));

                            // MANDATE: Correct RGB Mapping
                            s.r = col[0]/255.0f; s.g = col[1]/255.0f; s.b = col[2]/255.0f; s.a = initialConfidence;

                            // [REAL-GS INIT] Surface Normal Alignment
                            float dz_dx = (depth.at<float>(r, std::min(depth.cols-1, c+1)) - depth.at<float>(r, std::max(0, c-1))) / (2.0f / fx);
                            float dz_dy = (depth.at<float>(std::min(depth.rows-1, r+1), c) - depth.at<float>(std::max(0, r-1), c)) / (2.0f / fy);
                            glm::vec3 normal = glm::normalize(glm::vec3(-dz_dx, -dz_dy, 1.0f));
                            glm::vec3 normalW = glm::normalize(glm::mat3(invV) * normal);
                            glm::quat q = glm::rotation(glm::vec3(0, 0, 1), normalW);
                            s.rot[0] = q.x; s.rot[1] = q.y; s.rot[2] = q.z; s.rot[3] = q.w;

                            float s_val = d / fx * 1.5f;
                            s.scale[0] = s_val; s.scale[1] = s_val; s.scale[2] = s_val * 0.1f; // Anisotropic scale

                            s.confidence = initialConfidence;

                            mSplatData.push_back(s);
                            mVoxelGrid[key] = static_cast<int>(mSplatData.size() - 1);
                            dataChanged = true;
                        }
                    }
                }
            }
        }
        if (dataChanged) mDataDirty = true;
    }
}

void VoxelHash::addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat, float initialConfidence) {
    std::lock_guard<std::mutex> lock(mMutex);
    float voxelSize = mLastVoxelSize;
    glm::mat4 invV = glm::inverse(glm::make_mat4(viewMat));

    for (size_t i = 0; i < points.size(); i += 4) {
        float xw = points[i], yw = points[i+1], zw = points[i+2];
        VoxelKey key{ static_cast<int>(std::floor(xw / voxelSize)), static_cast<int>(std::floor(yw / voxelSize)), static_cast<int>(std::floor(zw / voxelSize)) };

        if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
            if (mSplatData.size() < MAX_SPLATS) {
                Splat s{};
                s.x = xw; s.y = yw; s.z = zw;
                // High-fidelity SLAM seeding
                s.r = 0.5f; s.g = 0.5f; s.b = 0.5f; s.a = initialConfidence * 0.5f;
                s.scale[0] = s.scale[1] = s.scale[2] = 0.01f;
                s.rot[3] = 1.0f;
                s.confidence = initialConfidence * 0.5f;
                mSplatData.push_back(s);
                mVoxelGrid[key] = static_cast<int>(mSplatData.size() - 1);
            }
        }
    }
}

void VoxelHash::addKeyframe(const Keyframe& kf) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mKeyframes.size() > 5) mKeyframes.erase(mKeyframes.begin());
    mKeyframes.push_back(kf);
}

void VoxelHash::optimize(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat) {
    if (depth.empty() || color.empty()) return;
    std::lock_guard<std::mutex> lock(mMutex);
    int count = static_cast<int>(mSplatData.size());
    if (count == 0) return;

    glm::mat4 V = glm::make_mat4(viewMat);
    const float halfW = (float)depth.cols / 2.0f;
    const float halfH = (float)depth.rows / 2.0f;
    float fx = projMat[0] * halfW;
    float fy = projMat[5] * halfH;
    float cx = (projMat[8]  + 1.0f) * halfW;
    float cy = (-projMat[9] + 1.0f) * halfH;

    int batch = std::max(200, count / 20);
    for (int i = 0; i < batch; ++i) {
        int idx = rand() % count;
        glm::vec4 p_cam = V * glm::vec4(mSplatData[idx].x, mSplatData[idx].y, mSplatData[idx].z, 1.0f);
        if (p_cam.z >= -0.1f) continue;

        float u = (p_cam.x * fx / -p_cam.z) + cx;
        float v = (p_cam.y * -fy / -p_cam.z) + cy;

        if (u >= 0 && u < depth.cols && v >= 0 && v < depth.rows) {
            float d = depth.at<float>(static_cast<int>(v), static_cast<int>(u));
            if (d > 0.1f) {
                float err = std::abs(-p_cam.z - d);
                if (err < 0.15f) {
                    mSplatData[idx].a = std::min(1.0f, mSplatData[idx].a + 0.05f);
                    mSplatData[idx].confidence = std::min(1.0f, mSplatData[idx].confidence + 0.1f);

                    // Refine color
                    cv::Vec3b obs_col = color.at<cv::Vec3b>(static_cast<int>(v), static_cast<int>(u));
                    float lr = 0.1f;
                    mSplatData[idx].r += (obs_col[2]/255.0f - mSplatData[idx].r) * lr;
                    mSplatData[idx].g += (obs_col[1]/255.0f - mSplatData[idx].g) * lr;
                    mSplatData[idx].b += (obs_col[0]/255.0f - mSplatData[idx].b) * lr;
                    mDataDirty = true;
                } else if (-p_cam.z < d - 0.25f) {
                    mSplatData[idx].a -= 0.04f;
                    mDataDirty = true;
                }
            }
        }
    }
}

void VoxelHash::draw(const glm::mat4& mvp, const glm::mat4& view, float focalY, int screenHeight) {
    std::lock_guard<std::mutex> lock(mMutex);
    int count = static_cast<int>(mSplatData.size());
    if (!mProgram || count == 0) return;

    if (mDataDirty) {
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(count * sizeof(Splat)), mSplatData.data(), GL_DYNAMIC_DRAW);
        mDataDirty = false;
    }

    glUseProgram(mProgram);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uMvp"), 1, GL_FALSE, glm::value_ptr(mvp));
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uView"), 1, GL_FALSE, glm::value_ptr(view));
    glUniform1f(glGetUniformLocation(mProgram, "uFocalY"), focalY);

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glEnable(GL_DEPTH_TEST);
    glDepthMask(GL_FALSE);
    glDepthFunc(GL_LEQUAL);

    glBindVertexArray(mVao);
    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);
    glBindVertexArray(0);

    glDepthMask(GL_TRUE);
}

void VoxelHash::sort(const glm::vec3& camPos) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mSplatData.empty()) return;

    // Back-to-front sorting for correct alpha blending
    std::sort(mSplatData.begin(), mSplatData.end(), [&camPos](const Splat& a, const Splat& b) {
        float da = glm::distance2(glm::vec3(a.x, a.y, a.z), camPos);
        float db = glm::distance2(glm::vec3(b.x, b.y, b.z), camPos);
        return da > db;
    });

    // Update voxel grid indices after sort
    mVoxelGrid.clear();
    float voxelSize = mLastVoxelSize;
    for (int i = 0; i < static_cast<int>(mSplatData.size()); ++i) {
        VoxelKey key{ static_cast<int>(std::floor(mSplatData[i].x / voxelSize)),
                     static_cast<int>(std::floor(mSplatData[i].y / voxelSize)),
                     static_cast<int>(std::floor(mSplatData[i].z / voxelSize)) };
        mVoxelGrid[key] = i;
    }
    mDataDirty = true;
}

void VoxelHash::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mSplatData.clear();
    mVoxelGrid.clear();
    mKeyframes.clear();
    mDataDirty = true;
}

void VoxelHash::pruneInternal(float threshold, float voxelSize) {
    mSplatData.erase(std::remove_if(mSplatData.begin(), mSplatData.end(),
        [threshold](const Splat& s) { return s.a < threshold; }), mSplatData.end());
    mVoxelGrid.clear();
    for (int i = 0; i < static_cast<int>(mSplatData.size()); ++i) {
        VoxelKey key{ static_cast<int>(std::floor(mSplatData[i].x / voxelSize)),
                     static_cast<int>(std::floor(mSplatData[i].y / voxelSize)),
                     static_cast<int>(std::floor(mSplatData[i].z / voxelSize)) };
        mVoxelGrid[key] = i;
    }
}

void VoxelHash::prune(float threshold) {
    std::lock_guard<std::mutex> lock(mMutex);
    pruneInternal(threshold, mLastVoxelSize);
    mDataDirty = true;
}

void VoxelHash::save(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return;
    int count = static_cast<int>(mSplatData.size());
    out.write(reinterpret_cast<const char*>(&count), sizeof(int));
    out.write(reinterpret_cast<const char*>(mSplatData.data()), count * sizeof(Splat));
}

void VoxelHash::load(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return;
    int count;
    in.read(reinterpret_cast<char*>(&count), sizeof(int));
    if (count < 0 || count > MAX_SPLATS) return;
    mSplatData.resize(count);
    in.read(reinterpret_cast<char*>(mSplatData.data()), count * sizeof(Splat));

    mVoxelGrid.clear();
    for (int i = 0; i < count; ++i) {
        VoxelKey key{ static_cast<int>(std::floor(mSplatData[i].x / mLastVoxelSize)),
                     static_cast<int>(std::floor(mSplatData[i].y / mLastVoxelSize)),
                     static_cast<int>(std::floor(mSplatData[i].z / mLastVoxelSize)) };
        mVoxelGrid[key] = i;
    }
    mDataDirty = true;
}

int VoxelHash::getSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return static_cast<int>(mSplatData.size());
}

int VoxelHash::getImmutableSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    int count = 0;
    for (const auto& s : mSplatData) {
        if (s.confidence >= 0.95f) count++;
    }
    return count;
}

float VoxelHash::getVisibleConfidenceAvg() const {
    std::lock_guard<std::mutex> lock(mMutex);
    float sum = 0;
    int count = 0;
    for (const auto& s : mSplatData) {
        sum += s.confidence;
        count++;
    }
    return count > 0 ? sum / static_cast<float>(count) : 0.0f;
}

float VoxelHash::getGlobalConfidenceAvg() const {
    return getVisibleConfidenceAvg();
}
