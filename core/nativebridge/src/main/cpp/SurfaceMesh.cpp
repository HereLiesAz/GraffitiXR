#include "include/SurfaceMesh.h"
#include <android/log.h>
#include <glm/gtc/matrix_transform.hpp>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SurfaceMesh", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SurfaceMesh", __VA_ARGS__)

static const char* kVertexShader =
    "#version 300 es\n"
    "layout(location = 0) in vec3 aPosition;\n"
    "uniform mat4 uMvp;\n"
    "void main() {\n"
    "  gl_Position = uMvp * vec4(aPosition, 1.0);\n"
    "}\n";

static const char* kFragmentShader =
    "#version 300 es\n"
    "precision mediump float;\n"
    "uniform vec4 uColor;\n"
    "out vec4 oColor;\n"
    "void main() {\n"
    "  oColor = uColor;\n"
    "}\n";

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

SurfaceMesh::SurfaceMesh() {}
SurfaceMesh::~SurfaceMesh() {
    if (mProgram) glDeleteProgram(mProgram);
    if (mVbo) glDeleteBuffers(1, &mVbo);
    if (mIbo) glDeleteBuffers(1, &mIbo);
}

void SurfaceMesh::initGl() {
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
        
        glGenBuffers(1, &mVbo);
        glGenBuffers(1, &mIbo);
    }
}

void SurfaceMesh::update(const cv::Mat& depth, const float* viewMat, const float* projMat, const float* anchorMatrix) {
    if (depth.empty()) return;

    std::lock_guard<std::mutex> lock(mMutex);
    if (!mInitialized) {
        float extent = 5.0f;
        float step = (extent * 2.0f) / (MESH_GRID_DIM - 1);
        mPersistentMesh.reserve(MESH_GRID_DIM * MESH_GRID_DIM);
        for (int i = 0; i < MESH_GRID_DIM; ++i) {
            for (int j = 0; j < MESH_GRID_DIM; ++j) {
                mPersistentMesh.push_back({-extent + j * step, -extent + i * step, 0.0f, 0.0f});
            }
        }
        mPersistentMeshIndices.reserve((MESH_GRID_DIM - 1) * (MESH_GRID_DIM - 1) * 4);
        for (int i = 0; i < MESH_GRID_DIM - 1; ++i) {
            for (int j = 0; j < MESH_GRID_DIM - 1; ++j) {
                int bl = i * MESH_GRID_DIM + j;
                int br = bl + 1;
                int tl = (i + 1) * MESH_GRID_DIM + j;
                int tr = tl + 1;
                mPersistentMeshIndices.push_back(bl); mPersistentMeshIndices.push_back(br);
                mPersistentMeshIndices.push_back(bl); mPersistentMeshIndices.push_back(tl);
            }
        }
        mInitialized = true;
    }

    glm::mat4 V = glm::make_mat4(viewMat);
    glm::mat4 A = glm::make_mat4(anchorMatrix);
    glm::mat4 invVA = glm::inverse(V * A);

    float fx = projMat[0] * (depth.cols / 2.0f);
    float fy = projMat[5] * (depth.rows / 2.0f);
    float cx = (projMat[8] + 1.0f) * (depth.cols / 2.0f);
    float cy = (-projMat[9] + 1.0f) * (depth.rows / 2.0f);

    for (auto& v : mPersistentMesh) {
        glm::vec4 p_cam = V * A * glm::vec4(v.x, v.y, v.z, 1.0f);
        if (p_cam.z >= -0.1f) continue;
        
        float u = (p_cam.x * fx / -p_cam.z) + cx;
        float vi = (p_cam.y * -fy / -p_cam.z) + cy;
        
        if (u >= 0 && u < depth.cols && vi >= 0 && vi < depth.rows) {
            float d = depth.at<float>((int)vi, (int)u);
            if (d > 0.1f && d < 10.0f) {
                float current_d = -p_cam.z;
                if (std::abs(current_d - d) < 0.3f) {
                    glm::vec4 p_target_cam = p_cam * (d / current_d);
                    p_target_cam.w = 1.0f;
                    glm::vec4 p_target_anchor = invVA * p_target_cam;

                    float alpha = 0.15f;
                    v.x += (p_target_anchor.x - v.x) * alpha;
                    v.y += (p_target_anchor.y - v.y) * alpha;
                    v.z += (p_target_anchor.z - v.z) * alpha;
                    v.confidence = std::min(1.0f, v.confidence + 0.1f);
                }
            }
        }
    }

    // Laplacian Smoothing
    std::vector<float> nextZ(mPersistentMesh.size());
    for (int i = 1; i < MESH_GRID_DIM - 1; ++i) {
        for (int j = 1; j < MESH_GRID_DIM - 1; ++j) {
            int idx = i * MESH_GRID_DIM + j;
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

    std::vector<float> verts;
    verts.reserve(mPersistentMesh.size() * 3);
    for(auto& v : mPersistentMesh) { verts.push_back(v.x); verts.push_back(v.y); verts.push_back(v.z); }

    glBindBuffer(GL_ARRAY_BUFFER, mVbo);
    glBufferData(GL_ARRAY_BUFFER, verts.size() * sizeof(float), verts.data(), GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mIbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, mPersistentMeshIndices.size() * sizeof(uint32_t), mPersistentMeshIndices.data(), GL_STATIC_DRAW);

    glUseProgram(mProgram);
    glUniformMatrix4fv(glGetUniformLocation(mProgram, "uMvp"), 1, GL_FALSE, glm::value_ptr(mvp));
    glUniform4f(glGetUniformLocation(mProgram, "uColor"), 0.0f, 1.0f, 1.0f, 0.2f);

    glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, (void*)0);
    glDrawElements(GL_LINES, mPersistentMeshIndices.size(), GL_UNSIGNED_INT, (void*)0);
    glDisableVertexAttribArray(0);
    glDisable(GL_BLEND);
    glDepthMask(GL_TRUE);
}

void SurfaceMesh::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mPersistentMesh.clear();
    mInitialized = false;
}

void SurfaceMesh::getMesh(std::vector<float>& outVertices, std::vector<float>& outWeights) {
    std::lock_guard<std::mutex> lock(mMutex);
    outVertices.clear(); outWeights.clear();
    for(const auto& v : mPersistentMesh) {
        outVertices.push_back(v.x); outVertices.push_back(v.y); outVertices.push_back(v.z);
        outWeights.push_back(v.confidence);
    }
}
