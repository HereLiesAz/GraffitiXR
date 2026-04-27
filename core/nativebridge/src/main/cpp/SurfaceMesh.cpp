#include "include/SurfaceMesh.h"
#include <android/log.h>
#include <glm/gtc/matrix_transform.hpp>
#include <algorithm>
#include <fstream>
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
        mPersistentMesh.assign(MESH_GRID_DIM * MESH_GRID_DIM, {0,0,0,0,0,0});
        for (int i = 0; i < MESH_GRID_DIM; ++i) {
            for (int j = 0; j < MESH_GRID_DIM; ++j) {
                float u = (float)j / (MESH_GRID_DIM - 1);
                float v = (float)i / (MESH_GRID_DIM - 1);
                int idx = i * MESH_GRID_DIM + j;
                mPersistentMesh[idx] = {-extent + j * step, -extent + i * step, 0.0f, u, v, 0.0f};
            }
        }
        mPersistentMeshIndices.reserve((MESH_GRID_DIM - 1) * (MESH_GRID_DIM - 1) * 6);
        mPersistentMeshIndices.clear();
        mWireframeIndices.reserve((MESH_GRID_DIM - 1) * (MESH_GRID_DIM - 1) * 4);
        mWireframeIndices.clear();
        for (int i = 0; i < MESH_GRID_DIM - 1; ++i) {
            for (int j = 0; j < MESH_GRID_DIM - 1; ++j) {
                int bl = i * MESH_GRID_DIM + j;
                int br = bl + 1;
                int tl = (i + 1) * MESH_GRID_DIM + j;
                int tr = tl + 1;
                mPersistentMeshIndices.push_back(bl); mPersistentMeshIndices.push_back(br); mPersistentMeshIndices.push_back(tl);
                mPersistentMeshIndices.push_back(br); mPersistentMeshIndices.push_back(tr); mPersistentMeshIndices.push_back(tl);

                mWireframeIndices.push_back(bl); mWireframeIndices.push_back(br);
                mWireframeIndices.push_back(bl); mWireframeIndices.push_back(tl);
            }
        }
        mMuralTexture = cv::Mat::zeros(TEXTURE_SIZE, TEXTURE_SIZE, CV_8UC3);
        mInitialized = true;
        mIndicesUploaded = false;
    }

    glm::mat4 V = glm::make_mat4(viewMat);
    glm::mat4 A = glm::make_mat4(anchorMatrix);
    glm::mat4 VA = V * A;
    glm::mat4 invVA = glm::inverse(VA);

    float fx = projMat[0] * (depth.cols / 2.0f);
    float fy = projMat[5] * (depth.rows / 2.0f);
    float cx = (projMat[8] + 1.0f) * (depth.cols / 2.0f);
    float cy = (-projMat[9] + 1.0f) * (depth.rows / 2.0f);

    const float* depthPtr = (const float*)depth.data;
    int dRows = depth.rows, dCols = depth.cols;

    std::vector<glm::vec2> camPoints(mPersistentMesh.size(), glm::vec2(-1.0f));

    // SLAMesh: Voxel-based surface averaging
    // We sample a subset of the depth map to update the mesh vertices
    for (int r = 0; r < dRows; r += 4) {
        for (int c = 0; c < dCols; c += 4) {
            float d = depthPtr[r * dCols + c];
            if (d < 0.3f || d > 5.0f) continue;

            float xc = (static_cast<float>(c) - cx) * d / fx;
            float yc = -(static_cast<float>(r) - cy) * d / fy;
            glm::vec4 p_cam = glm::vec4(xc, yc, -d, 1.0f);
            glm::vec4 p_anchor = invVA * p_cam;

            // Project anchor-space point to our grid
            float extent = 5.0f;
            float gridX = (p_anchor.x + extent) / (extent * 2.0f) * (MESH_GRID_DIM - 1);
            float gridY = (p_anchor.y + extent) / (extent * 2.0f) * (MESH_GRID_DIM - 1);

            int gi = (int)std::round(gridY);
            int gj = (int)std::round(gridX);

            if (gi >= 0 && gi < MESH_GRID_DIM && gj >= 0 && gj < MESH_GRID_DIM) {
                int idx = gi * MESH_GRID_DIM + gj;
                auto& v = mPersistentMesh[idx];

                // Running average of Z based on confidence
                float alpha = (v.confidence < 0.1f) ? 0.5f : 0.1f;
                v.z = v.z * (1.0f - alpha) + p_anchor.z * alpha;
                v.confidence = std::min(1.0f, v.confidence + 0.05f);

                camPoints[idx] = glm::vec2(c * (float)color.cols / dCols, r * (float)color.rows / dRows);
            }
        }
    }

    updateTexture(color, camPoints);

    // Laplacian Smoothing (Weighted by confidence to preserve features)
    for (int iter = 0; iter < 3; ++iter) {
        std::vector<float> nextZ(mPersistentMesh.size());
        for (int i = 1; i < MESH_GRID_DIM - 1; ++i) {
            for (int j = 1; j < MESH_GRID_DIM - 1; ++j) {
                int idx = i * MESH_GRID_DIM + j;
                auto& v = mPersistentMesh[idx];
                if (v.confidence < 0.01f) {
                    nextZ[idx] = 0; continue;
                }
                float sum = mPersistentMesh[idx - 1].z + mPersistentMesh[idx + 1].z +
                            mPersistentMesh[idx - MESH_GRID_DIM].z + mPersistentMesh[idx + MESH_GRID_DIM].z;
                nextZ[idx] = v.z * 0.7f + (sum / 4.0f) * 0.3f;
            }
        }
        for (int i = 1; i < MESH_GRID_DIM - 1; ++i) {
            for (int j = 1; j < MESH_GRID_DIM - 1; ++j) {
                mPersistentMesh[i * MESH_GRID_DIM + j].z = nextZ[i * MESH_GRID_DIM + j];
            }
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
    glBindBuffer(GL_ARRAY_BUFFER, mVbo);
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

void SurfaceMesh::save(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return;
    uint32_t count = static_cast<uint32_t>(mPersistentMesh.size());
    out.write(reinterpret_cast<const char*>(&count), sizeof(uint32_t));
    out.write(reinterpret_cast<const char*>(mPersistentMesh.data()), static_cast<std::streamsize>(count * sizeof(MeshVertex)));

    // Save texture as binary blob
    if (mMuralTexture.data) {
        int rows = mMuralTexture.rows, cols = mMuralTexture.cols, type = mMuralTexture.type();
        out.write(reinterpret_cast<const char*>(&rows), sizeof(int));
        out.write(reinterpret_cast<const char*>(&cols), sizeof(int));
        out.write(reinterpret_cast<const char*>(&type), sizeof(int));
        out.write(reinterpret_cast<const char*>(mMuralTexture.data), static_cast<std::streamsize>(mMuralTexture.total() * mMuralTexture.elemSize()));
    }
}

void SurfaceMesh::load(const std::string& path) {
    std::lock_guard<std::mutex> lock(mMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return;
    mPersistentMesh.clear();
    uint32_t count;
    in.read(reinterpret_cast<char*>(&count), sizeof(uint32_t));
    if (count > MESH_GRID_DIM * MESH_GRID_DIM) count = MESH_GRID_DIM * MESH_GRID_DIM;
    mPersistentMesh.resize(count);
    in.read(reinterpret_cast<char*>(mPersistentMesh.data()), static_cast<std::streamsize>(count * sizeof(MeshVertex)));

    int rows, cols, type;
    if (in.read(reinterpret_cast<char*>(&rows), sizeof(int))) {
        in.read(reinterpret_cast<char*>(&cols), sizeof(int));
        in.read(reinterpret_cast<char*>(&type), sizeof(int));
        mMuralTexture = cv::Mat(rows, cols, type);
        in.read(reinterpret_cast<char*>(mMuralTexture.data), static_cast<std::streamsize>(mMuralTexture.total() * mMuralTexture.elemSize()));
    }

    mInitialized = true;
    mMeshDirty = true;
    mTextureDirty = true;
    mIndicesUploaded = false;
}

void SurfaceMesh::updateTexture(const cv::Mat& color, const std::vector<glm::vec2>& camPoints) {
    // Piecewise warp: Optimized to warp a SUBSET of patches directly into mMuralTexture to save battery.
    int totalPatches = (MESH_GRID_DIM - 1) * (MESH_GRID_DIM - 1);
    int patchesToProcess = 128; // Increased for higher resolution grid

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

        // Only update if the quad has begun to align
        if (mPersistentMesh[i00].confidence < 0.05f) continue;

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
