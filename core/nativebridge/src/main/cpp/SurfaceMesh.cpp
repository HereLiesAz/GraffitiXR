#include "include/SurfaceMesh.h"
#include <android/log.h>
#include <glm/gtc/matrix_transform.hpp>
#include <algorithm>
#include <opencv2/imgproc.hpp>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SurfaceMesh", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SurfaceMesh", __VA_ARGS__)

static const char* kVertexShader =
    "#version 300 es\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "layout(location = 1) in vec2 aTexCoord;\n"
    "uniform mat4 uMvp;\n"
    "out vec2 vTexCoord;\n"
    "void main() {\n"
    "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
    "  vTexCoord = aTexCoord;\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 vTexCoord;\n"
    "uniform sampler2D uTexture;\n"
    "uniform float uAlpha;\n"
    "uniform int uDrawMode;\n"
    "out vec4 oColor;\n"
    "void main() {\n"
    "  if (uDrawMode == 1) {\n"
    "    oColor = vec4(0.0, 1.0, 1.0, uAlpha);\n"
    "    return;\n"
    "  }\n"
    "  vec4 tex = texture(uTexture, vTexCoord);\n"
    "  if (length(tex.rgb) < 0.01) discard;\n"
    "  oColor = vec4(tex.rgb, uAlpha);\n"
    "}\n";

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLogLength;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLogLength);
        GLchar* strInfoLog = new GLchar[infoLogLength + 1];
        glGetShaderInfoLog(shader, infoLogLength, NULL, strInfoLog);
        LOGE("Shader compile error: %s", strInfoLog);
        delete[] strInfoLog;
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

SurfaceMesh::SurfaceMesh() {}
SurfaceMesh::~SurfaceMesh() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mProgram) glDeleteProgram(mProgram);
    if (mVbo) glDeleteBuffers(1, &mVbo);
    if (mIbo) glDeleteBuffers(1, &mIbo);
    if (mTextureId) glDeleteTextures(1, &mTextureId);
}

void SurfaceMesh::initGl() {
    std::lock_guard<std::mutex> lock(mMutex);

    // Check if current handles are valid in this GL context
    if (mProgram != 0 && glIsProgram(mProgram)) {
        return;
    }

    LOGI("Initializing SurfaceMesh GL handles (new context)");
    mProgram = 0;
    mVbo = 0;
    mIbo = 0;
    mWireIbo = 0;
    mTextureId = 0;

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vs && fs) {
        mProgram = glCreateProgram();
        glAttachShader(mProgram, vs);
        glAttachShader(mProgram, fs);
        glLinkProgram(mProgram);
        glDeleteShader(vs);
        glDeleteShader(fs);
        
        glGenBuffers(1, &mVbo);
        glGenBuffers(1, &mIbo);
        glGenBuffers(1, &mWireIbo);

        glGenTextures(1, &mTextureId);
        glBindTexture(GL_TEXTURE_2D, mTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        mTextureDirty = true;
        mMeshDirty = true;
        mIndicesUploaded = false;
    }
}

void SurfaceMesh::update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* anchorMatrix, float lightLevel) {
    if (depth.empty() || color.empty()) return;

    std::lock_guard<std::mutex> lock(mMutex);
    mMeshDirty = true;
    if (!mInitialized) {
        float extent = 5.0f;
        float step = (extent * 2.0f) / (MESH_GRID_DIM - 1);
        mPersistentMesh.reserve(MESH_GRID_DIM * MESH_GRID_DIM);
        for (int i = 0; i < MESH_GRID_DIM; ++i) {
            for (int j = 0; j < MESH_GRID_DIM; ++j) {
                float u = (float)j / (MESH_GRID_DIM - 1);
                float v = (float)i / (MESH_GRID_DIM - 1);
                mPersistentMesh.push_back({-extent + j * step, -extent + i * step, 0.0f, u, v, 0.0f});
            }
        }
        mPersistentMeshIndices.reserve((MESH_GRID_DIM - 1) * (MESH_GRID_DIM - 1) * 6);
        mWireframeIndices.reserve((MESH_GRID_DIM - 1) * (MESH_GRID_DIM - 1) * 4);
        for (int i = 0; i < MESH_GRID_DIM - 1; ++i) {
            for (int j = 0; j < MESH_GRID_DIM - 1; ++j) {
                int bl = i * MESH_GRID_DIM + j;
                int br = bl + 1;
                int tl = (i + 1) * MESH_GRID_DIM + j;
                int tr = tl + 1;
                mPersistentMeshIndices.push_back(bl); mPersistentMeshIndices.push_back(br); mPersistentMeshIndices.push_back(tl);
                mPersistentMeshIndices.push_back(br); mPersistentMeshIndices.push_back(tr); mPersistentMeshIndices.push_back(tl);

                // Wireframe Grid
                mWireframeIndices.push_back(bl); mWireframeIndices.push_back(br);
                mWireframeIndices.push_back(bl); mWireframeIndices.push_back(tl);
            }
        }
        mMuralTexture = cv::Mat::zeros(TEXTURE_SIZE, TEXTURE_SIZE, CV_8UC3);
        mInitialized = true;
        mIndicesUploaded = false; // Reset to ensure upload on next draw
    }

    glm::mat4 V = glm::make_mat4(viewMat);
    glm::mat4 A = glm::make_mat4(anchorMatrix);
    glm::mat4 VA = V * A;
    glm::mat4 invVA = glm::inverse(VA);

    float fx = projMat[0] * (depth.cols / 2.0f);
    float fy = projMat[5] * (depth.rows / 2.0f);
    float cx = (projMat[8] + 1.0f) * (depth.cols / 2.0f);
    float cy = (-projMat[9] + 1.0f) * (depth.rows / 2.0f);

    // Calculate current distance range for relative scrutiny
    double minD = 0.3, maxD = 5.0;
    cv::Mat mask = (depth > 0.1f);
    if (cv::countNonZero(mask) > 100) {
        cv::minMaxLoc(depth, &minD, &maxD, NULL, NULL, mask);
    }
    float rangeD = (float)(maxD - minD);
    if (rangeD < 0.1f) rangeD = 1.0f;

    std::vector<bool> vertexHits(mPersistentMesh.size(), false);
    std::vector<bool> inView(mPersistentMesh.size(), false);
    std::vector<glm::vec2> camPoints(mPersistentMesh.size(), glm::vec2(-1.0f));
    std::vector<float> vertexScrutiny(mPersistentMesh.size(), 0.0f);
    std::vector<float> vertexColorSim(mPersistentMesh.size(), 1.0f);

    for (int i = 0; i < (int)mPersistentMesh.size(); ++i) {
        auto& v = mPersistentMesh[i];
        glm::vec4 p_cam = VA * glm::vec4(v.x, v.y, v.z, 1.0f);
        if (p_cam.z >= -0.1f) continue;
        
        float u_cam = (p_cam.x * fx / -p_cam.z) + cx;
        float v_cam = (p_cam.y * -fy / -p_cam.z) + cy;
        
        if (u_cam >= 0 && u_cam < depth.cols && v_cam >= 0 && v_cam < depth.rows) {
            inView[i] = true;

            float d = depth.at<float>((int)v_cam, (int)u_cam);
            if (d > 0.1f && d < 10.0f) {
                float current_d = -p_cam.z;

                // Relative Scrutiny
                float rel_d = std::max(0.0f, std::min(1.0f, (current_d - (float)minD) / rangeD));
                vertexScrutiny[i] = 1.0f - rel_d;

                // Color Influence: least influential factor
                int texX = (int)(v.u * TEXTURE_SIZE);
                int texY = (int)(v.v * TEXTURE_SIZE);
                if (texX >= 0 && texX < TEXTURE_SIZE && texY >= 0 && texY < TEXTURE_SIZE) {
                    cv::Vec3b world_col = mMuralTexture.at<cv::Vec3b>(texY, texX);
                    cv::Vec3b cam_col = color.at<cv::Vec3b>((int)(v_cam * ((float)color.rows / depth.rows)),
                                                            (int)(u_cam * ((float)color.cols / depth.cols)));

                    float color_diff = std::sqrt(std::pow((cam_col[2] - world_col[2])/255.0f, 2) +
                                                 std::pow((cam_col[1] - world_col[1])/255.0f, 2) +
                                                 std::pow((cam_col[0] - world_col[0])/255.0f, 2));
                    vertexColorSim[i] = 1.0f - std::max(0.0f, std::min(1.0f, color_diff / 1.732f));
                }

                // Adaptive tolerance: tighter when scrutinized (close)
                float tolerance = 0.05f + (rel_d * 0.10f) + (vertexColorSim[i] * 0.02f);
                if (v.confidence > 0.6f) tolerance -= 0.02f;

                if (std::abs(current_d - d) < tolerance) {
                    glm::vec4 p_target_cam = p_cam * (d / current_d);
                    p_target_cam.w = 1.0f;
                    glm::vec4 p_target_anchor = invVA * p_target_cam;

                    if (v.confidence < 0.8f) {
                        float alpha = 0.10f;
                        v.x += (p_target_anchor.x - v.x) * alpha;
                        v.y += (p_target_anchor.y - v.y) * alpha;
                        v.z += (p_target_anchor.z - v.z) * alpha;
                    }

                    vertexHits[i] = true;
                    camPoints[i] = glm::vec2(u_cam * ((float)color.cols / depth.cols), v_cam * ((float)color.rows / depth.rows));
                }
            }
        }
    }

    for (int i = 0; i < (int)mPersistentMesh.size(); ++i) {
        if (mPersistentMesh[i].confidence >= 0.98f) continue; // Real Immutability

        if (vertexHits[i]) {
            // Reinforced established vertex: lock slower if scrutinized
            float gain = (0.15f + (1.0f - vertexScrutiny[i]) * 0.2f) * (0.9f + 0.1f * vertexColorSim[i]);
            mPersistentMesh[i].confidence = std::min(1.0f, mPersistentMesh[i].confidence + gain);
        } else if (inView[i]) {
            // Miss: decay faster if scrutinized
            float decay = 0.03f + (vertexScrutiny[i] * 0.07f) + (1.0f - vertexColorSim[i]) * 0.01f;
            mPersistentMesh[i].confidence = std::max(0.0f, mPersistentMesh[i].confidence - decay);
            float resetAlpha = 0.05f + (vertexScrutiny[i] * 0.15f);
            mPersistentMesh[i].z *= (1.0f - resetAlpha);
        }
        // If not in view, confidence and position are preserved (No global decay)
    }

    updateTexture(color, camPoints);

    // Laplacian Smoothing (only for established vertices)
    std::vector<float> nextZ(mPersistentMesh.size());
    for (int i = 1; i < MESH_GRID_DIM - 1; ++i) {
        for (int j = 1; j < MESH_GRID_DIM - 1; ++j) {
            int idx = i * MESH_GRID_DIM + j;
            if (mPersistentMesh[idx].confidence < 0.1f) {
                nextZ[idx] = mPersistentMesh[idx].z;
                continue;
            }
            float sum = mPersistentMesh[idx - 1].z + mPersistentMesh[idx + 1].z +
                        mPersistentMesh[idx - MESH_GRID_DIM].z + mPersistentMesh[idx + MESH_GRID_DIM].z;
            nextZ[idx] = mPersistentMesh[idx].z * 0.8f + (sum / 4.0f) * 0.2f;
        }
    }
    for (int i = 1; i < MESH_GRID_DIM - 1; ++i) {
        for (int j = 1; j < MESH_GRID_DIM - 1; ++j) {
            mPersistentMesh[i * MESH_GRID_DIM + j].z = nextZ[i * MESH_GRID_DIM + j];
        }
    }
}

void SurfaceMesh::draw(const glm::mat4& mvp) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProgram || mPersistentMesh.empty()) return;

    if (mTextureDirty) {
        glBindTexture(GL_TEXTURE_2D, mTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, mMuralTexture.cols, mMuralTexture.rows, 0, GL_RGB, GL_UNSIGNED_BYTE, mMuralTexture.data);
        mTextureDirty = false;
    }

    if (mMeshDirty) {
        std::vector<float> data;
        data.reserve(mPersistentMesh.size() * 5);
        for(auto& v : mPersistentMesh) {
            data.push_back(v.x); data.push_back(v.y); data.push_back(v.z);
            data.push_back(v.u); data.push_back(v.v);
        }

        glBindBuffer(GL_ARRAY_BUFFER, mVbo);
        glBufferData(GL_ARRAY_BUFFER, data.size() * sizeof(float), data.data(), GL_DYNAMIC_DRAW);
        mMeshDirty = false;
    }

    if (!mIndicesUploaded) {
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, mPersistentMeshIndices.size() * sizeof(uint32_t), mPersistentMeshIndices.data(), GL_STATIC_DRAW);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mWireIbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, mWireframeIndices.size() * sizeof(uint32_t), mWireframeIndices.data(), GL_STATIC_DRAW);

        mIndicesUploaded = true;
    }

    glUseProgram(mProgram);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uMvp"), 1, GL_FALSE, glm::value_ptr(mvp));
    glUniform1i(glGetUniformLocation(mProgram, "uTexture"), 0);

    glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void*)(3 * sizeof(float)));

    // Pass 1: Textured Triangles
    glUniform1i(glGetUniformLocation(mProgram, "uDrawMode"), 0);
    glUniform1f(glGetUniformLocation(mProgram, "uAlpha"), 0.8f);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, mTextureId);

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIbo);
    glDrawElements(GL_TRIANGLES, mPersistentMeshIndices.size(), GL_UNSIGNED_INT, (void*)0);

    // Pass 2: Wireframe Grid
    glUniform1i(glGetUniformLocation(mProgram, "uDrawMode"), 1);
    glUniform1f(glGetUniformLocation(mProgram, "uAlpha"), 0.3f); // Fainter wireframe

    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mWireIbo);
    glDrawElements(GL_LINES, mWireframeIndices.size(), GL_UNSIGNED_INT, (void*)0);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
}

void SurfaceMesh::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mPersistentMesh.clear();
    mInitialized = false;
    if (mMuralTexture.data) mMuralTexture.setTo(cv::Scalar(0,0,0));
}

void SurfaceMesh::getMesh(std::vector<float>& outVertices, std::vector<float>& outWeights) {
    std::lock_guard<std::mutex> lock(mMutex);
    outVertices.clear(); outWeights.clear();
    for(const auto& v : mPersistentMesh) {
        outVertices.push_back(v.x); outVertices.push_back(v.y); outVertices.push_back(v.z);
        outWeights.push_back(v.confidence);
    }
}

void SurfaceMesh::updateTexture(const cv::Mat& color, const std::vector<glm::vec2>& camPoints) {
    // Piecewise warp: Optimized to warp a SUBSET of patches directly into mMuralTexture to save battery.
    int totalPatches = (MESH_GRID_DIM - 1) * (MESH_GRID_DIM - 1);
    int patchesToProcess = 32;

    for (int p = 0; p < patchesToProcess; ++p) {
        int patchIdx = mNextTexturePatchIndex % totalPatches;
        mNextTexturePatchIndex++;

        int i = patchIdx / (MESH_GRID_DIM - 1);
        int j = patchIdx % (MESH_GRID_DIM - 1);

        int i00 = i * MESH_GRID_DIM + j;
        int i10 = i00 + 1;
        int i01 = (i + 1) * MESH_GRID_DIM + j;
        int i11 = i01 + 1;

        if (camPoints[i00].x < 0 || camPoints[i10].x < 0 || camPoints[i01].x < 0 || camPoints[i11].x < 0) continue;

        cv::Point2f src[4] = {
            {camPoints[i00].x, camPoints[i00].y},
            {camPoints[i10].x, camPoints[i10].y},
            {camPoints[i11].x, camPoints[i11].y},
            {camPoints[i01].x, camPoints[i01].y}
        };

        cv::Point2f dst[4] = {
            {mPersistentMesh[i00].u * TEXTURE_SIZE, mPersistentMesh[i00].v * TEXTURE_SIZE},
            {mPersistentMesh[i10].u * TEXTURE_SIZE, mPersistentMesh[i10].v * TEXTURE_SIZE},
            {mPersistentMesh[i11].u * TEXTURE_SIZE, mPersistentMesh[i11].v * TEXTURE_SIZE},
            {mPersistentMesh[i01].u * TEXTURE_SIZE, mPersistentMesh[i01].v * TEXTURE_SIZE}
        };

        // Only update if the quad is not yet fully established
        if (mPersistentMesh[i00].confidence < 0.2f) continue;

        cv::Mat H = cv::getPerspectiveTransform(src, dst);

        float minX = std::min({dst[0].x, dst[1].x, dst[2].x, dst[3].x});
        float minY = std::min({dst[0].y, dst[1].y, dst[2].y, dst[3].y});
        float maxX = std::max({dst[0].x, dst[1].x, dst[2].x, dst[3].x});
        float maxY = std::max({dst[0].y, dst[1].y, dst[2].y, dst[3].y});

        cv::Rect roi((int)minX, (int)minY, (int)(maxX - minX + 1), (int)(maxY - minY + 1));
        roi &= cv::Rect(0, 0, TEXTURE_SIZE, TEXTURE_SIZE);
        if (roi.width <= 0 || roi.height <= 0) continue;

        // Offset H to warp directly into the ROI of the mural
        cv::Mat H_roi = cv::Mat::eye(3, 3, CV_64F);
        H_roi.at<double>(0, 2) = -roi.x;
        H_roi.at<double>(1, 2) = -roi.y;
        H = H_roi * H;

        cv::Mat patch;
        cv::warpPerspective(color, patch, H, roi.size(), cv::INTER_LINEAR, cv::BORDER_CONSTANT);

        float alpha = 0.30f; // Fast Birth for textures
        cv::addWeighted(mMuralTexture(roi), 1.0 - alpha, patch, alpha, 0, mMuralTexture(roi));
        mTextureDirty = true;
    }
}
