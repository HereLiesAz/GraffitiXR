#include "include/VoxelHash.h"
#include "include/NativeUtil.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <cstdlib>
#define GLM_ENABLE_EXPERIMENTAL
#include <glm/gtc/matrix_inverse.hpp>
#include <glm/gtc/quaternion.hpp>
#include <glm/gtx/quaternion.hpp>
#include <glm/gtx/norm.hpp>
#include <glm/gtx/component_wise.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GaussianEngine", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GaussianEngine", __VA_ARGS__)

// ~~~ Advanced Gaussian Splatting Implementation ~~~

static const char* kVertexShader =
    "#version 300 es\n"
    "precision highp float;\n"
    "layout(location = 0) in vec2 aQuadPos;\n" // (-0.5, -0.5) to (0.5, 0.5)
    "layout(location = 1) in vec3 aPosition;\n"
    "layout(location = 2) in vec4 aColor;\n"
    "layout(location = 3) in vec3 aScale;\n"
    "layout(location = 4) in vec4 aRot;\n"
    "layout(location = 5) in float aConfidence;\n"
    "layout(location = 6) in vec3 aVelocity;\n"
    "layout(location = 7) in vec3 aSH_R;\n"
    "layout(location = 8) in vec3 aSH_G;\n"
    "layout(location = 9) in vec3 aSH_B;\n"
    "uniform mat4 uMvp;\n"
    "uniform mat4 uView;\n"
    "uniform float uFocalY;\n"
    "uniform float uTimeStep;\n"
    "uniform vec3 uCamPos;\n"
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
    "  vec3 integratedPos = aPosition + aVelocity * uTimeStep;\n"
    "  vec4 posCam = uView * vec4(integratedPos, 1.0);\n"
    "  if (posCam.z >= -0.1) { gl_Position = vec4(0,0,0,1); return; }\n"
    "\n"
    "  vec3 dir = normalize(integratedPos - uCamPos);\n"
    "  float SH_C1 = 0.4886025;\n"
    "  vec3 resColor = aColor.rgb;\n"
    "  resColor.r += SH_C1 * (aSH_R.x * dir.y + aSH_R.y * dir.z + aSH_R.z * dir.x);\n"
    "  resColor.g += SH_C1 * (aSH_G.x * dir.y + aSH_G.y * dir.z + aSH_G.z * dir.x);\n"
    "  resColor.b += SH_C1 * (aSH_B.x * dir.y + aSH_B.y * dir.z + aSH_B.z * dir.x);\n"
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
    "  Sigma2D[0][0] += 0.3; Sigma2D[1][1] += 0.3;\n" // Smoothing
    "\n"
    "  // Eigenvalue decomp for 2D ellipse\n"
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
    "  vec2 offset = rot2D * (aQuadPos * vec2(scale2D_x, scale2D_y) * 4.0);\n"
    "\n"
    "  gl_Position = uMvp * vec4(integratedPos, 1.0);\n"
    "  gl_Position.xy += offset * gl_Position.w / uFocalY;\n"
    "  vColor = vec4(max(vec3(0.0), resColor), aColor.a * clamp(aConfidence, 0.0, 1.0));\n"
    "  vLocalPos = aQuadPos * 4.0;\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec4 vColor;\n"
    "in vec2 vLocalPos;\n"
    "out vec4 oColor;\n"
    "void main() {\n"
    "  float power = -0.5 * dot(vLocalPos, vLocalPos);\n"
    "  float g = exp(power);\n"
    "  if (g < 0.1) discard;\n"
    "  oColor = vec4(vColor.rgb, vColor.a * g);\n"
    "}\n";

VoxelHash::VoxelHash() : mNextRefineIndex(0) {
    mSplatData.reserve(MAX_SPLATS);
}

VoxelHash::~VoxelHash() {}

void VoxelHash::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram != 0 && glIsProgram(mProgram)) return;
    LOGI("Initializing Advanced Gaussian Engine with Instanced Quads");
    mProgram = 0;
    mPointVbo = 0;
    mQuadVbo = 0;
    mVao = 0;

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

        // Quad positions
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (void*)0);

        // Instanced Splat data
        glBindBuffer(GL_ARRAY_BUFFER, mPointVbo);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)0);
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(12));
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(64));
        glEnableVertexAttribArray(4); glVertexAttribPointer(4, 4, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(76));
        glEnableVertexAttribArray(5); glVertexAttribPointer(5, 1, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(92));
        glEnableVertexAttribArray(6); glVertexAttribPointer(6, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(96));
        glEnableVertexAttribArray(7); glVertexAttribPointer(7, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(28));
        glEnableVertexAttribArray(8); glVertexAttribPointer(8, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(40));
        glEnableVertexAttribArray(9); glVertexAttribPointer(9, 3, GL_FLOAT, GL_FALSE, sizeof(Splat), (void*)(52));

        for(int i=1; i<=9; ++i) glVertexAttribDivisor(i, 1);
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

        // 1. Refinement Loop: Subset optimization for efficiency
        int totalSplats = static_cast<int>(mSplatData.size());
        if (totalSplats > 0) {
            int itemsToProcess = std::max(100, totalSplats / 4);
            for (int i = 0; i < itemsToProcess; ++i) {
                int idx = mNextRefineIndex % totalSplats;
                mNextRefineIndex++;

                if (mSplatData[idx].a >= 0.98f) continue;

                glm::vec4 p_cam = V * glm::vec4(mSplatData[idx].x, mSplatData[idx].y, mSplatData[idx].z, 1.0f);
                if (p_cam.z >= -0.1f) continue;

                float u_cam = (p_cam.x * fx / -p_cam.z) + cx;
                float v_cam = (p_cam.y * -fy / -p_cam.z) + cy;

                if (u_cam >= 0 && u_cam < (float)depth.cols && v_cam >= 0 && v_cam < (float)depth.rows) {
                    float d = depth.at<float>(static_cast<int>(v_cam), static_cast<int>(u_cam));
                    if (d > 0.1f) {
                        float current_d = -p_cam.z;
                        float err = d - current_d; // Positive = PUSH, Negative = PULL

                        if (std::abs(err) < 0.25f) {
                            // [PUSH/PULL] Refine position along the camera ray
                            float alpha_pos = 0.2f;
                            float new_d = current_d + err * alpha_pos;
                            glm::vec4 p_cam_new = p_cam * (new_d / current_d);
                            p_cam_new.w = 1.0f;
                            glm::vec4 p_world_new = invV * p_cam_new;

                            // Update voxel grid if moved significantly
                            VoxelKey oldKey{ static_cast<int>(std::floor(mSplatData[idx].x / voxelSize)), static_cast<int>(std::floor(mSplatData[idx].y / voxelSize)), static_cast<int>(std::floor(mSplatData[idx].z / voxelSize)) };
                            VoxelKey newKey{ static_cast<int>(std::floor(p_world_new.x / voxelSize)), static_cast<int>(std::floor(p_world_new.y / voxelSize)), static_cast<int>(std::floor(p_world_new.z / voxelSize)) };

                            mSplatData[idx].x = p_world_new.x;
                            mSplatData[idx].y = p_world_new.y;
                            mSplatData[idx].z = p_world_new.z;

                            if (!(oldKey == newKey)) {
                                mVoxelGrid.erase(oldKey);
                                mVoxelGrid[newKey] = idx;
                            }

                            if (std::abs(new_d - d) < 0.08f) {
                                mSplatData[idx].a = std::min(1.0f, mSplatData[idx].a + 0.10f);
                                mSplatData[idx].confidence = std::min(1.0f, mSplatData[idx].confidence + 0.15f);
                            }
                            dataChanged = true;
                            continue;
                        } else if (err < -0.25f) {
                            // Splat is in front of the observed depth, and doesn't match it -> degrade
                            mSplatData[idx].a -= 0.05f;
                            dataChanged = true;
                        }
                    }
                }
            }
        }

        // 2. Aggressive Discovery Loop (Direct Depth-to-Splat)
        int step = 8;
        for (int r = step; r < depth.rows - step; r += step) {
            for (int c = step; c < depth.cols - step; c += step) {
                float d = depth.at<float>(r, c);
                if (d > 0.1f && d < 7.0f) {
                    float xc = (static_cast<float>(c) - cx) * d / fx;
                    float yc = -(static_cast<float>(r) - cy) * d / fy;
                    float zc = -d;
                    glm::vec4 p_world = invV * glm::vec4(xc, yc, zc, 1.0f);

                    VoxelKey key{ static_cast<int>(std::floor(p_world.x / voxelSize)), static_cast<int>(std::floor(p_world.y / voxelSize)), static_cast<int>(std::floor(p_world.z / voxelSize)) };

                    if (mVoxelGrid.find(key) == mVoxelGrid.end() && mProtoGrid.find(key) == mProtoGrid.end()) {
                        if (mSplatData.size() < MAX_SPLATS) {
                            Splat s{};
                            s.x = p_world.x; s.y = p_world.y; s.z = p_world.z;

                            int colorR = static_cast<int>(r * scaleY);
                            int colorC = static_cast<int>(c * scaleX);
                            const auto& col = color.at<cv::Vec3b>(std::min(colorR, color.rows-1), std::min(colorC, color.cols-1));

                            // MANDATE: Correct RGB Mapping (col[0]=R, col[1]=G, col[2]=B)
                            s.r = col[0]/255.0f; s.g = col[1]/255.0f; s.b = col[2]/255.0f; s.a = initialConfidence;
                            for(int j=0; j<9; ++j) s.sh[j] = 0.0f;

                            // [ANISOTROPIC INIT] Orient splat to the physical surface
                            float dz_dx = (depth.at<float>(r, std::min(depth.cols-1, c+1)) - depth.at<float>(r, std::max(0, c-1))) / (2.0f / fx);
                            float dz_dy = (depth.at<float>(std::min(depth.rows-1, r+1), c) - depth.at<float>(std::max(0, r-1), c)) / (2.0f / fy);
                            glm::vec3 normal = glm::normalize(glm::vec3(-dz_dx, -dz_dy, 1.0f));
                            glm::vec3 normalW = glm::normalize(glm::mat3(invV) * normal);
                            glm::quat q = glm::rotation(glm::vec3(0, 0, 1), normalW);
                            s.rot[0] = q.x; s.rot[1] = q.y; s.rot[2] = q.z; s.rot[3] = q.w;

                            float s_val = d / fx * 1.5f;
                            s.scale[0] = s_val; s.scale[1] = s_val; s.scale[2] = s_val * 0.1f; // Flatten along normal
                            s.confidence = initialConfidence;
                            s.gradAccum = 0.0f;
                            s.gradCount = 0;

                            mSplatData.push_back(s);
                            mVoxelGrid[key] = static_cast<int>(mSplatData.size() - 1);
                            dataChanged = true;
                        }
                    }
                }
            }
        }

        // 3. Proto-Splat Promotion
        if (!mProtoSplats.empty()) {
            int itemsToProcess = std::max(200, (int)mProtoSplats.size() / 4);
            for (int i = 0; i < itemsToProcess; ++i) {
                if (mProtoSplats.empty()) break;
                int pIdx = rand() % mProtoSplats.size();
                const auto& ps = mProtoSplats[pIdx];

                glm::vec4 p_cam = V * glm::vec4(ps.x, ps.y, ps.z, 1.0f);
                if (p_cam.z >= -0.1f) continue;

                float u_cam = (p_cam.x * fx / -p_cam.z) + cx;
                float v_cam = (p_cam.y * -fy / -p_cam.z) + cy;

                if (u_cam >= 2.0f && u_cam < (float)depth.cols - 2.0f && v_cam >= 2.0f && v_cam < (float)depth.rows - 2.0f) {
                    // Try to find valid depth in a 5x5 window for robust SLAM promotion
                    float d = 0.0f;
                    bool foundDepth = false;
                    int finalU = 0, finalV = 0;
                    for (int dy = -2; dy <= 2 && !foundDepth; ++dy) {
                        for (int dx = -2; dx <= 2; ++dx) {
                            int cu = static_cast<int>(u_cam) + dx;
                            int cv = static_cast<int>(v_cam) + dy;
                            float val = depth.at<float>(cv, cu);
                            if (val > 0.1f) {
                                d = val;
                                foundDepth = true;
                                finalU = cu; finalV = cv;
                                break;
                            }
                        }
                    }

                    if (foundDepth && std::abs(-p_cam.z - d) < 0.35f) {
                        VoxelKey key{ static_cast<int>(std::floor(ps.x / voxelSize)), static_cast<int>(std::floor(ps.y / voxelSize)), static_cast<int>(std::floor(ps.z / voxelSize)) };
                        if (mVoxelGrid.find(key) == mVoxelGrid.end() && mSplatData.size() < MAX_SPLATS) {
                            Splat s{};
                            s.x = ps.x; s.y = ps.y; s.z = ps.z;

                            int colorR = static_cast<int>(finalV * scaleY);
                            int colorC = static_cast<int>(finalU * scaleX);
                            const auto& col = color.at<cv::Vec3b>(std::min(colorR, color.rows-1), std::min(colorC, color.cols-1));

                            s.r = col[0]/255.0f; s.g = col[1]/255.0f; s.b = col[2]/255.0f; s.a = initialConfidence;
                            for(int j=0; j<9; ++j) s.sh[j] = 0.0f;

                            // [ANISOTROPIC INIT] Orient splat to the physical surface
                            float dz_dx = (depth.at<float>(finalV, std::min(depth.cols-1, finalU+1)) - depth.at<float>(finalV, std::max(0, finalU-1))) / (2.0f / fx);
                            float dz_dy = (depth.at<float>(std::min(depth.rows-1, finalV+1), finalU) - depth.at<float>(std::max(0, finalV-1), finalU)) / (2.0f / fy);
                            glm::vec3 normal = glm::normalize(glm::vec3(-dz_dx, -dz_dy, 1.0f));
                            glm::vec3 normalW = glm::normalize(glm::mat3(invV) * normal);
                            glm::quat q = glm::rotation(glm::vec3(0, 0, 1), normalW);
                            s.rot[0] = q.x; s.rot[1] = q.y; s.rot[2] = q.z; s.rot[3] = q.w;

                            float s_val = d / fx * 1.5f;
                            s.scale[0] = s_val; s.scale[1] = s_val; s.scale[2] = s_val * 0.1f; // Flatten along normal
                            s.confidence = initialConfidence;
                            s.gradAccum = 0.0f;
                            s.gradCount = 0;

                            mSplatData.push_back(s);
                            mVoxelGrid[key] = static_cast<int>(mSplatData.size() - 1);

                            mProtoSplats.erase(mProtoSplats.begin() + pIdx);
                            mProtoGrid.erase(key);
                            dataChanged = true;
                        }
                    }
                }
            }
        }
        if (dataChanged) mDataDirty = true;
    }
}

void VoxelHash::addSparsePoints(const std::vector<float>& points, const float* viewMat, const float* projMat) {
    std::lock_guard<std::mutex> lock(mMutex);
    float voxelSize = mLastVoxelSize;
    for (size_t i = 0; i < points.size(); i += 4) {
        float xw = points[i], yw = points[i+1], zw = points[i+2];
        VoxelKey key{ static_cast<int>(std::floor(xw / voxelSize)), static_cast<int>(std::floor(yw / voxelSize)), static_cast<int>(std::floor(zw / voxelSize)) };

        // Disciplined Seeding: Discard if we already have a Splat or ProtoSplat in this 1cm voxel.
        if (mVoxelGrid.find(key) == mVoxelGrid.end() && mProtoGrid.find(key) == mProtoGrid.end()) {
            if (mProtoSplats.size() < MAX_SPLATS) {
                mProtoSplats.push_back({xw, yw, zw});
                mProtoGrid.insert(key);
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
    int splatCount = static_cast<int>(mSplatData.size());
    if (splatCount == 0) return;

    const Keyframe* kf = nullptr;
    if (!mKeyframes.empty()) { kf = &mKeyframes[rand() % mKeyframes.size()]; }

    glm::mat4 V = glm::make_mat4(viewMat);
    glm::vec3 camPos = glm::vec3(glm::inverse(V)[3]);
    const float halfW = (float)depth.cols / 2.0f;
    const float halfH = (float)depth.rows / 2.0f;
    float fx = projMat[0] * halfW;
    float fy = projMat[5] * halfH;
    float cx = (projMat[8]  + 1.0f) * halfW;
    float cy = (-projMat[9] + 1.0f) * halfH;

    int batchSize = std::max(500, splatCount / 8);
    for (int i = 0; i < batchSize; ++i) {
        int idx = rand() % splatCount;
        if (mSplatData[idx].a >= 0.99f && mSplatData[idx].confidence >= 0.99f) continue;

        if (kf) {
            for(int j=0; j<3; ++j) mSplatData[idx].velocity[j] += (kf->linearVelocity[j] - mSplatData[idx].velocity[j]) * 0.05f;
        }

        glm::vec4 p_cam = V * glm::vec4(mSplatData[idx].x, mSplatData[idx].y, mSplatData[idx].z, 1.0f);
        if (p_cam.z >= -0.1f) continue;

        float u_cam = (p_cam.x * fx / -p_cam.z) + cx;
        float v_cam = (p_cam.y * -fy / -p_cam.z) + cy;

        if (u_cam >= 0 && u_cam < depth.cols && v_cam >= 0 && v_cam < depth.rows) {
            float d = depth.at<float>(static_cast<int>(v_cam), static_cast<int>(u_cam));
            if (d > 0.1f) {
                float current_d = -p_cam.z;
                float err = d - current_d; // Positive = PUSH, Negative = PULL

                if (std::abs(err) < 0.35f) {
                    // [PUSH/PULL] Refine position along the camera ray
                    float alpha_pos = 0.15f;
                    float new_d = current_d + err * alpha_pos;
                    glm::vec4 p_cam_new = p_cam * (new_d / current_d);
                    p_cam_new.w = 1.0f;
                    glm::vec4 p_world_new = glm::inverse(V) * p_cam_new;

                    // Update voxel grid if moved significantly
                    VoxelKey oldKey{ static_cast<int>(std::floor(mSplatData[idx].x / mLastVoxelSize)), static_cast<int>(std::floor(mSplatData[idx].y / mLastVoxelSize)), static_cast<int>(std::floor(mSplatData[idx].z / mLastVoxelSize)) };
                    VoxelKey newKey{ static_cast<int>(std::floor(p_world_new.x / mLastVoxelSize)), static_cast<int>(std::floor(p_world_new.y / mLastVoxelSize)), static_cast<int>(std::floor(p_world_new.z / mLastVoxelSize)) };

                    mSplatData[idx].x = p_world_new.x;
                    mSplatData[idx].y = p_world_new.y;
                    mSplatData[idx].z = p_world_new.z;

                    if (!(oldKey == newKey)) {
                        mVoxelGrid.erase(oldKey);
                        mVoxelGrid[newKey] = idx;
                    }

                    if (std::abs(new_d - d) < 0.10f) {
                        mSplatData[idx].a = std::min(1.0f, mSplatData[idx].a + 0.05f);
                        mSplatData[idx].confidence = std::min(1.0f, mSplatData[idx].confidence + 0.08f);
                    }

                    // [ROTATION REFINEMENT] Slerp toward latest observed surface normal
                    int u = static_cast<int>(u_cam), v = static_cast<int>(v_cam);
                    float dz_dx = (depth.at<float>(v, std::min(depth.cols-1, u+1)) - depth.at<float>(v, std::max(0, u-1))) / (2.0f / fx);
                    float dz_dy = (depth.at<float>(std::min(depth.rows-1, v+1), u) - depth.at<float>(std::max(0, v-1), u)) / (2.0f / fy);
                    glm::vec3 normal = glm::normalize(glm::vec3(-dz_dx, -dz_dy, 1.0f));
                    glm::vec3 normalW = glm::normalize(glm::mat3(glm::inverse(V)) * normal);
                    glm::quat target_q = glm::rotation(glm::vec3(0, 0, 1), normalW);
                    glm::quat current_q(mSplatData[idx].rot[3], mSplatData[idx].rot[0], mSplatData[idx].rot[1], mSplatData[idx].rot[2]);
                    glm::quat refined_q = glm::slerp(current_q, target_q, 0.1f);
                    mSplatData[idx].rot[0] = refined_q.x; mSplatData[idx].rot[1] = refined_q.y; mSplatData[idx].rot[2] = refined_q.z; mSplatData[idx].rot[3] = refined_q.w;

                    // [SH OPTIMIZATION] Update view-dependent color coefficients (SH Level 1)
                    cv::Vec3b obs_col = color.at<cv::Vec3b>(static_cast<int>(v_cam), static_cast<int>(u_cam));
                    glm::vec3 obs_rgb = {obs_col[2]/255.0f, obs_col[1]/255.0f, obs_col[0]/255.0f};

                    glm::vec3 dir = glm::normalize(glm::vec3(mSplatData[idx].x, mSplatData[idx].y, mSplatData[idx].z) - camPos);
                    float SH_C1 = 0.4886025f;

                    // Current prediction
                    glm::vec3 pred_rgb = {mSplatData[idx].r, mSplatData[idx].g, mSplatData[idx].b};
                    pred_rgb.r += SH_C1 * (mSplatData[idx].sh[0] * dir.y + mSplatData[idx].sh[1] * dir.z + mSplatData[idx].sh[2] * dir.x);
                    pred_rgb.g += SH_C1 * (mSplatData[idx].sh[3] * dir.y + mSplatData[idx].sh[4] * dir.z + mSplatData[idx].sh[5] * dir.x);
                    pred_rgb.b += SH_C1 * (mSplatData[idx].sh[6] * dir.y + mSplatData[idx].sh[7] * dir.z + mSplatData[idx].sh[8] * dir.x);

                    glm::vec3 color_err = obs_rgb - pred_rgb;
                    float alpha_sh = 0.15f;

                    // Gradient descent on base color and SH
                    mSplatData[idx].r += color_err.r * 0.1f;
                    mSplatData[idx].g += color_err.g * 0.1f;
                    mSplatData[idx].b += color_err.b * 0.1f;

                    mSplatData[idx].sh[0] += color_err.r * SH_C1 * dir.y * alpha_sh;
                    mSplatData[idx].sh[1] += color_err.r * SH_C1 * dir.z * alpha_sh;
                    mSplatData[idx].sh[2] += color_err.r * SH_C1 * dir.x * alpha_sh;
                    mSplatData[idx].sh[3] += color_err.g * SH_C1 * dir.y * alpha_sh;
                    mSplatData[idx].sh[4] += color_err.g * SH_C1 * dir.z * alpha_sh;
                    mSplatData[idx].sh[5] += color_err.g * SH_C1 * dir.x * alpha_sh;
                    mSplatData[idx].sh[6] += color_err.b * SH_C1 * dir.y * alpha_sh;
                    mSplatData[idx].sh[7] += color_err.b * SH_C1 * dir.z * alpha_sh;
                    mSplatData[idx].sh[8] += color_err.b * SH_C1 * dir.x * alpha_sh;

                    // [GRADIENT TRACKING] Accumulate view-space error as a proxy for the 3DGS gradient
                    mSplatData[idx].gradAccum += glm::length(color_err) + std::abs(err);
                    mSplatData[idx].gradCount++;

                } else if (err < -0.35f) {
                    mSplatData[idx].a -= 0.04f;
                }
            }
        }
    }
    mDataDirty = true;
}

void VoxelHash::densify(float threshold, float scaleLimit) {
    std::lock_guard<std::mutex> lock(mMutex);
    int splatCount = static_cast<int>(mSplatData.size());
    if (splatCount >= MAX_SPLATS) return;

    std::vector<Splat> newSplats;
    for (int i = 0; i < splatCount; ++i) {
        auto& s = mSplatData[i];
        if (s.gradCount == 0) continue;

        float avgGrad = s.gradAccum / s.gradCount;
        s.gradAccum = 0; s.gradCount = 0; // Reset for next cycle

        if (avgGrad > threshold) {
            float s_max = std::max({s.scale[0], s.scale[1], s.scale[2]});

            if (s_max > scaleLimit) {
                // [SPLIT] Large Gaussian into two smaller ones
                s.scale[0] /= 1.6f; s.scale[1] /= 1.6f; s.scale[2] /= 1.6f;
                Splat s2 = s;
                // Shift positions slightly along the scale vector (simplification of Inria SPLIT)
                float shift = s_max * 0.5f;
                s.x += shift; s2.x -= shift;
                newSplats.push_back(s2);
            } else {
                // [CLONE] Small Gaussian to increase density
                Splat s2 = s;
                newSplats.push_back(s2);
            }
        }
        if (mSplatData.size() + newSplats.size() >= MAX_SPLATS) break;
    }

    if (!newSplats.empty()) {
        mSplatData.insert(mSplatData.end(), newSplats.begin(), newSplats.end());
        mDataDirty = true;
        // Grid will be rebuilt on the next Sort cycle or Update
        LOGI("3DGS: Densified map with %zu new splats", newSplats.size());
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
    glUniform1f(glGetUniformLocation(mProgram, "uTimeStep"), 0.016f);
    glm::vec3 camPos = glm::vec3(glm::inverse(view)[3]);
    glUniform3fv(glGetUniformLocation(mProgram, "uCamPos"), 1, glm::value_ptr(camPos));

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // Standard Alpha Blending for 3DGS
    glDepthMask(GL_FALSE);
    glDisable(GL_DEPTH_TEST);

    glBindVertexArray(mVao);
    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);
    glBindVertexArray(0);

    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);
}

void VoxelHash::sort(const glm::vec3& camPos) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mSplatData.empty()) return;

    // Standard back-to-front sorting for correct alpha blending
    std::sort(mSplatData.begin(), mSplatData.end(), [&camPos](const Splat& a, const Splat& b) {
        float da = glm::distance2(glm::vec3(a.x, a.y, a.z), camPos);
        float db = glm::distance2(glm::vec3(b.x, b.y, b.z), camPos);
        return da > db;
    });

    // Update voxel grid indices after sort
    mVoxelGrid.clear();
    float voxelSize = mLastVoxelSize;
    for (int i = 0; i < static_cast<int>(mSplatData.size()); ++i) {
        VoxelKey key{ static_cast<int>(std::floor(mSplatData[i].x / voxelSize)), static_cast<int>(std::floor(mSplatData[i].y / voxelSize)), static_cast<int>(std::floor(mSplatData[i].z / voxelSize)) };
        mVoxelGrid[key] = i;
    }
    mDataDirty = true;
}

void VoxelHash::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mSplatData.clear();
    mProtoSplats.clear();
    mProtoGrid.clear();
    mVoxelGrid.clear();
    mKeyframes.clear();
    mDataDirty = true;
}

void VoxelHash::pruneInternal(float threshold, float voxelSize) {
    mSplatData.erase(std::remove_if(mSplatData.begin(), mSplatData.end(),
        [threshold](const Splat& s) { return s.a < threshold; }), mSplatData.end());
    mVoxelGrid.clear();
    for (int i = 0; i < static_cast<int>(mSplatData.size()); ++i) {
        VoxelKey key{ static_cast<int>(std::floor(mSplatData[i].x / voxelSize)), static_cast<int>(std::floor(mSplatData[i].y / voxelSize)), static_cast<int>(std::floor(mSplatData[i].z / voxelSize)) };
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
    LOGI("Saved %d splats to %s", count, path.c_str());
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
        VoxelKey key{ static_cast<int>(std::floor(mSplatData[i].x / mLastVoxelSize)), static_cast<int>(std::floor(mSplatData[i].y / mLastVoxelSize)), static_cast<int>(std::floor(mSplatData[i].z / mLastVoxelSize)) };
        mVoxelGrid[key] = i;
    }
    mDataDirty = true;
    LOGI("Loaded %d splats from %s", count, path.c_str());
}

int VoxelHash::getSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return static_cast<int>(mSplatData.size());
}

int VoxelHash::getImmutableSplatCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    int count = 0;
    for (const auto& s : mSplatData) {
        if (s.confidence >= 0.98f) count++;
    }
    return count;
}

float VoxelHash::getVisibleConfidenceAvg() const {
    std::lock_guard<std::mutex> lock(mMutex);
    float sum = 0;
    int count = 0;
    for (const auto& s : mSplatData) {
        if (s.confidence > 0.15f) {
            sum += s.confidence;
            count++;
        }
    }
    return count > 0 ? sum / static_cast<float>(count) : 0.0f;
}

float VoxelHash::getGlobalConfidenceAvg() const {
    std::lock_guard<std::mutex> lock(mMutex);
    float sum = 0;
    if (mSplatData.empty()) return 0.0f;
    for (const auto& s : mSplatData) {
        sum += s.confidence;
    }
    return sum / static_cast<float>(mSplatData.size());
}
