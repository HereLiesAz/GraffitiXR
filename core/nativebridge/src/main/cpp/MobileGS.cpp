#include "MobileGS.h"
#include <android/log.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <iostream>
#include <fstream>
#include <cstddef>
#include <glm/gtc/type_ptr.hpp>

#define LOG_TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Constants
const int SAMPLE_STRIDE = 4;
const float MIN_DEPTH = 0.2f;
const float MAX_DEPTH = 5.0f;
const int MAX_SPLATS = 500000;
const float VOXEL_SIZE = 0.02f; // 2cm

const float QUAD_VERTICES[] = {
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f,  1.0f,
        1.0f,  1.0f
};

// Shaders
// 3D Gaussian Splatting Vertex Shader
// Renders a 3D scaled and rotated quad
const char* VERTEX_SHADER = R"(#version 300 es
layout(location = 0) in vec2 aQuadVert; // Unit quad vertex (Attribute 0 - Divisor 0)
layout(location = 1) in vec3 aPosition; // Instance pos (Attribute 1 - Divisor 1)
layout(location = 2) in vec3 aScale;    // Instance scale (Attribute 2 - Divisor 1)
layout(location = 3) in vec4 aRotation; // Instance rot (Attribute 3 - Divisor 1)
layout(location = 4) in vec3 aColor;    // Instance color (Attribute 4 - Divisor 1)
layout(location = 5) in float aOpacity; // Instance opacity (Attribute 5 - Divisor 1)

uniform mat4 uView;
uniform mat4 uProj;

out vec4 vColor;
out vec2 vUV;

// Helper: Rotate vector by quaternion
vec3 rotateVector(vec3 v, vec4 q) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

void main() {
    // 1. Scale
    // We multiply by 3.0 to cover +/- 3 sigma (99.7% of energy)
    vec3 scaledVert = vec3(aQuadVert, 0.0) * aScale * 3.0;

    // 2. Rotate
    vec3 rotatedVert = rotateVector(scaledVert, aRotation);

    // 3. Translate
    vec3 worldPos = aPosition + rotatedVert;

    // 4. Project
    gl_Position = uProj * uView * vec4(worldPos, 1.0);

    vColor = vec4(aColor, aOpacity);
    vUV = aQuadVert * 3.0; // Pass scaled UVs for gaussian calc
}
)";

const char* FRAGMENT_SHADER = R"(#version 300 es
precision mediump float;
in vec4 vColor;
in vec2 vUV;
out vec4 FragColor;

uniform float u_LightIntensity;
uniform vec3 u_LightColor;
uniform int u_VizMode; // 0=Gaussian, 1=Point, 2=Wireframe, 3=Fog

void main() {
    float distSq = dot(vUV, vUV);
    float alpha = 1.0;

    if (u_VizMode == 0 || u_VizMode == 3) {
        // Gaussian Falloff
        alpha = exp(-0.5 * distSq);
        if (distSq > 9.0 || alpha < 0.01) discard;
    } else if (u_VizMode == 1) {
        // Point Cloud: Only draw center core
        if (distSq > 0.1) discard;
        alpha = 0.8;
    } else if (u_VizMode == 2) {
        // Wireframe: Thin ring
        if (distSq < 8.0 || distSq > 9.0) discard;
        alpha = 0.5;
    }

    // Apply Light Estimation
    vec3 litColor = vColor.rgb * u_LightIntensity * u_LightColor;
    FragColor = vec4(litColor, vColor.a * alpha);
}
)";

const char* FOG_VERTEX_SHADER = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)";

const char* FOG_FRAGMENT_SHADER = R"(#version 300 es
precision mediump float;
uniform vec4 u_FogColor;
out vec4 FragColor;
void main() {
    FragColor = u_FogColor;
}
)";

// Helper: Compile Shader
GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
            LOGE("Error compiling shader:\n%s", infoLog.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

// Helper: Create Program
GLuint createProgram(const char* vSource, const char* fSource) {
    GLuint vShader = compileShader(GL_VERTEX_SHADER, vSource);
    if (vShader == 0) return 0;

    GLuint fShader = compileShader(GL_FRAGMENT_SHADER, fSource);
    if (fShader == 0) {
        glDeleteShader(vShader);
        return 0;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vShader);
    glAttachShader(program, fShader);
    glLinkProgram(program);

    GLint linked;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            std::vector<char> infoLog(infoLen);
            glGetProgramInfoLog(program, infoLen, nullptr, infoLog.data());
            LOGE("Error linking program:\n%s", infoLog.data());
        }
        glDeleteProgram(program);
        program = 0;
    }
    glDeleteShader(vShader);
    glDeleteShader(fShader);
    return program;
}

MobileGS::MobileGS() : mFrameCount(0), mProgram(0), mLocView(-1), mLocProj(-1), mMeshVBO(0), mVBO_Quad(0), mVBO_Instance(0), mGlDirty(true), mVizMode(0) {
    mVulkanBackend = new VulkanBackend();
    LOGI("MobileGS Constructor");
}

MobileGS::~MobileGS() {
    clear();
    if (mMeshVBO != 0) glDeleteBuffers(1, &mMeshVBO);
    if (mVBO_Quad != 0) glDeleteBuffers(1, &mVBO_Quad);
    if (mVBO_Instance != 0) glDeleteBuffers(1, &mVBO_Instance);
    if (mProgram != 0) glDeleteProgram(mProgram);

    delete mVulkanBackend;
}

void MobileGS::initialize() {
    if (mProgram == 0) {
        LOGI("MobileGS Initialize: Compiling Shaders");
        mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mLocView = glGetUniformLocation(mProgram, "uView");
        mLocProj = glGetUniformLocation(mProgram, "uProj");

        mFogProgram = createProgram(FOG_VERTEX_SHADER, FOG_FRAGMENT_SHADER);
        mLocFogColor = glGetUniformLocation(mFogProgram, "u_FogColor");

        // Mark dirty
        mGlDirty = true;
    }

    if (mVulkanBackend != nullptr) {
        // Disabled to reduce startup complexity
        // mVulkanBackend->initialize();
    }
}

void MobileGS::resetGL() {
    LOGI("MobileGS: Resetting GL State");
    mProgram = 0;
    mFogProgram = 0;
    mVBO_Quad = 0;
    mVBO_Instance = 0;
    mMeshVBO = 0;
    mLocView = -1;
    mLocProj = -1;
    mLocFogColor = -1;
    mGlDirty = true;
}

void MobileGS::updateMesh(float* vertices, int vertexCount) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    mMeshVertices.clear();
    for (int i = 0; i < vertexCount; ++i) {
        MeshVertex v;
        v.pos = glm::vec3(vertices[i * 3], vertices[i * 3 + 1], vertices[i * 3 + 2]);
        v.normal = glm::vec3(0, 1, 0); // Simplified normal
        mMeshVertices.push_back(v);
    }
    mMeshVertexCount = vertexCount;
    mMeshDirty = true;
}

void MobileGS::initVulkan(void* nativeWindow) {
    if (mVulkanBackend != nullptr) {
        mVulkanBackend->initSurface(nativeWindow);
    }
}

void MobileGS::resizeVulkan(int width, int height) {
    if (mVulkanBackend != nullptr) {
        mVulkanBackend->resize(width, height);
    }
}

void MobileGS::destroyVulkan() {
    if (mVulkanBackend != nullptr) {
        mVulkanBackend->destroySurface();
    }
}

void MobileGS::uploadMesh() {
    if (!mMeshDirty || mMeshVertices.empty()) return;

    if (mMeshVBO == 0) {
        glGenBuffers(1, &mMeshVBO);
    }

    glBindBuffer(GL_ARRAY_BUFFER, mMeshVBO);
    glBufferData(GL_ARRAY_BUFFER, mMeshVertices.size() * sizeof(MeshVertex), mMeshVertices.data(), GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    mMeshDirty = false;
}

void MobileGS::uploadSplatData() {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    // 1. Setup Quad VBO (Once)
    if (mVBO_Quad == 0) {
        glGenBuffers(1, &mVBO_Quad);
        glBindBuffer(GL_ARRAY_BUFFER, mVBO_Quad);
        glBufferData(GL_ARRAY_BUFFER, sizeof(QUAD_VERTICES), QUAD_VERTICES, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    if (mSplats.empty()) return;

    // Optimization: Only sort/upload if dirty or camera moved significantly (> 5cm)
    float camDistSq = glm::dot(mCamPos - mLastCamPos, mCamPos - mLastCamPos);
    bool camMoved = camDistSq > (0.05f * 0.05f);

    if (!mGlDirty && !camMoved && mVBO_Instance != 0) return;

    // 2. Setup Instance VBO
    if (mVBO_Instance == 0) {
        glGenBuffers(1, &mVBO_Instance);
    }

    // Sort before upload (Painter's Algorithm for Alpha)
    sortSplats();

    glBindBuffer(GL_ARRAY_BUFFER, mVBO_Instance);
    glBufferData(GL_ARRAY_BUFFER, mSplats.size() * sizeof(Splat), mSplats.data(), GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    mLastCamPos = mCamPos;
    mGlDirty = false;
}

void MobileGS::draw() {
    if (mProgram == 0 || mSplats.empty()) return;

    // --- PHASE 0: Stencil Setup for Fog of War ---
    bool useFog = (mVizMode == 3);
    if (useFog) {
        glEnable(GL_STENCIL_TEST);
        glStencilMask(0xFF);
        glClearStencil(0);
        glClear(GL_STENCIL_BUFFER_BIT);

        // Render splats into stencil ONLY
        glStencilFunc(GL_ALWAYS, 1, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        glDepthMask(GL_FALSE);
    }

    glUseProgram(mProgram);

    // Update Uniforms
    glUniformMatrix4fv(mLocView, 1, GL_FALSE, glm::value_ptr(mViewMat));
    glUniformMatrix4fv(mLocProj, 1, GL_FALSE, glm::value_ptr(mProjMat));
    glUniform1f(glGetUniformLocation(mProgram, "u_LightIntensity"), mLightIntensity);
    glUniform3fv(glGetUniformLocation(mProgram, "u_LightColor"), 1, glm::value_ptr(mLightColor));
    glUniform1i(glGetUniformLocation(mProgram, "u_VizMode"), mVizMode);

    // Ensure data is on GPU
    uploadSplatData();
    uploadMesh();

    if (mVBO_Quad == 0 || mVBO_Instance == 0) return;

    if (!useFog) {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
    }

    // --- PHASE 1: Render Mesh for Occlusion ---
    if (mMeshVBO != 0 && mMeshVertexCount > 0) {
        if (!useFog) glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE); // Don't draw color
        glDepthMask(GL_TRUE); // Write to depth

        glBindBuffer(GL_ARRAY_BUFFER, mMeshVBO);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(MeshVertex), (void*)offsetof(MeshVertex, pos));

        glDrawArrays(GL_TRIANGLES, 0, mMeshVertexCount);

        glDisableVertexAttribArray(0);
        if (!useFog) glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE); // Re-enable color
    }

    // --- PHASE 2: Draw Splats ---
    if (!useFog) glDepthMask(GL_FALSE); // Don't overwrite occlusion depth from mesh

    // 1. Quad Vertices (Attribute 0) - Per Vertex
    glBindBuffer(GL_ARRAY_BUFFER, mVBO_Quad);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (void*)0);
    glVertexAttribDivisor(0, 0);

    // 2. Instance Attributes - Per Instance
    glBindBuffer(GL_ARRAY_BUFFER, mVBO_Instance);
    size_t stride = sizeof(Splat);

    // Helper lambda for cleanliness
    auto setAttrib = [&](int loc, int size, int type, bool norm, size_t offset) {
        glEnableVertexAttribArray(loc);
        glVertexAttribPointer(loc, size, type, norm, stride, (void*)offset);
        glVertexAttribDivisor(loc, 1);
    };

    setAttrib(1, 3, GL_FLOAT, GL_FALSE, offsetof(Splat, pos));
    setAttrib(2, 3, GL_FLOAT, GL_FALSE, offsetof(Splat, scale));
    setAttrib(3, 4, GL_FLOAT, GL_FALSE, offsetof(Splat, rot));
    setAttrib(4, 3, GL_FLOAT, GL_FALSE, offsetof(Splat, color));
    setAttrib(5, 1, GL_FLOAT, GL_FALSE, offsetof(Splat, opacity));

    // Draw Instanced
    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, mSplats.size());

    // --- PHASE 3: Draw Fog Overlay where stencil is 0 ---
    if (useFog) {
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glStencilFunc(GL_EQUAL, 0, 0xFF); // Draw where splats ARE NOT
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);

        glUseProgram(mFogProgram);
        glUniform4f(mLocFogColor, 0.0f, 0.0f, 0.0f, 0.7f); // Dark transparent layer

        glBindBuffer(GL_ARRAY_BUFFER, mVBO_Quad);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (void*)0);
        glVertexAttribDivisor(0, 0);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);
    }

    // Cleanup
    for(int i=0; i<=5; i++) {
        glDisableVertexAttribArray(i);
        glVertexAttribDivisor(i, 0); // Reset divisor
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void MobileGS::sortSplats() {
    // Sort Back-to-Front (Farthest first) for alpha blending
    // Using distance squared to camera position
    std::sort(mSplats.begin(), mSplats.end(), [this](const Splat& a, const Splat& b) {
        glm::vec3 diff1 = a.pos - mCamPos;
        glm::vec3 diff2 = b.pos - mCamPos;
        float d1 = glm::dot(diff1, diff1);
        float d2 = glm::dot(diff2, diff2);
        return d1 > d2;
    });
}

void MobileGS::setTargetDescriptors(const cv::Mat& descriptors) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    descriptors.copyTo(mTargetDescriptors);
    mHasTarget = !mTargetDescriptors.empty();
    LOGI("MobileGS: Target descriptors set. Rows: %d", mTargetDescriptors.rows);
}

void MobileGS::trainStep() {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    // Simple "Training" / Refinement Loop
    // 1. Densify: Already handled in feedDepthData by adding new points in empty voxels.
    // 2. Prune: Remove points with low opacity (confidence).

    // We iterate backwards to safely erase
    for (int i = mSplats.size() - 1; i >= 0; i--) {
        Splat& s = mSplats[i];

        // Decay opacity slightly every frame to simulate "forgetting" if not re-observed
        // But only if we are actively training (this method called)
        s.opacity *= 0.99f;

        // Pruning threshold
        if (s.opacity < 0.1f) {
            // Swap with last and pop
            if (i != mSplats.size() - 1) {
                mSplats[i] = mSplats.back();
            }
            mSplats.pop_back();
            mGlDirty = true;
        }
    }

    // If we pruned, invalidate/rebuild the grid
    if (mGlDirty) {
        mVoxelGrid.clear();
        for(int i=0; i<mSplats.size(); i++) {
            const Splat& s = mSplats[i];
            VoxelKey key = {
                    (int)floor(s.pos.x / VOXEL_SIZE),
                    (int)floor(s.pos.y / VOXEL_SIZE),
                    (int)floor(s.pos.z / VOXEL_SIZE)
            };
            mVoxelGrid[key] = i;
        }
    }
}

void MobileGS::pruneMap(int ageThresholdFrames) {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    // Implementation: Culling based on max count
    // If we exceed MAX_SPLATS, we indiscriminately remove random points
    // (or ideally oldest/lowest confidence) to fit.

    if (mSplats.size() > MAX_SPLATS) {
        LOGI("Pruning Map: Count %zu exceeds MAX %d", mSplats.size(), MAX_SPLATS);

        // Target: Remove 10%
        int targetCount = MAX_SPLATS * 0.9;

        // Naive sort by opacity (confidence) ascending
        // We want to keep HIGH opacity, so remove lowest opacity first.
        std::sort(mSplats.begin(), mSplats.end(), [](const Splat& a, const Splat& b) {
            return a.opacity > b.opacity; // Descending: High opacity first
        });

        // Resize to target, dropping the end (low opacity)
        mSplats.resize(targetCount);

        // Rebuild Spatial Hash
        mVoxelGrid.clear();
        for(int i=0; i<mSplats.size(); i++) {
            const Splat& s = mSplats[i];
            VoxelKey key = {
                    (int)floor(s.pos.x / VOXEL_SIZE),
                    (int)floor(s.pos.y / VOXEL_SIZE),
                    (int)floor(s.pos.z / VOXEL_SIZE)
            };
            mVoxelGrid[key] = i;
        }

        mGlDirty = true;
    }
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    mSplats.clear();
    mVoxelGrid.clear();
    mGlDirty = true;
}

int MobileGS::getSplatCount() {
    return mSplats.size();
}

void MobileGS::onSurfaceChanged(int width, int height) {
    mViewportWidth = width;
    mViewportHeight = height;
    glViewport(0, 0, width, height);
}

void MobileGS::updateCamera(float* viewMtx, float* projMtx) {
    mViewMat = glm::make_mat4(viewMtx);
    mProjMat = glm::make_mat4(projMtx);

    // Extract Camera Position (Inverse of View Matrix)
    // View = R * T. InvView = T^-1 * R^-1.
    // Easier: glm::inverse(mViewMat)[3] gives the translation component of inverse, which is cam pos.
    glm::mat4 invView = glm::inverse(mViewMat);
    mCamPos = glm::vec3(invView[3]);
}

void MobileGS::updateLight(float intensity, float r, float g, float b) {
    mLightIntensity = intensity;
    mLightColor = glm::vec3(r, g, b);
}

void MobileGS::setVisualizationMode(int mode) {
    mVizMode = mode;
}

void MobileGS::feedDepthData(uint16_t* depthData, uint8_t* colorData, int width, int height, int depthStride, int colorStride, float* poseMtx, float fov) {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    if (mSplats.size() >= MAX_SPLATS) return;

    glm::mat4 poseMat = glm::make_mat4(poseMtx);
    float tanHalfFov = tan(fov * 0.5f);
    float aspectRatio = (float)width / height;

    for (int y = 0; y < height; y += SAMPLE_STRIDE) {
        for (int x = 0; x < width; x += SAMPLE_STRIDE) {
            int idx = y * depthStride + x;
            float depth = depthData[idx] * 0.001f; // mm to meters

            if (depth < MIN_DEPTH || depth > MAX_DEPTH) continue;

            // 1. Unproject
            float ndc_x = ((float)x / width) * 2.0f - 1.0f;
            float ndc_y = ((float)y / height) * 2.0f - 1.0f;

            // Camera Space (Assuming OpenGL Coordinate System: -Z forward, +Y up)
            float cam_x = ndc_x * depth * tanHalfFov * aspectRatio;
            float cam_y = ndc_y * depth * tanHalfFov;
            float cam_z = -depth;

            glm::vec4 camPos(cam_x, cam_y, cam_z, 1.0f);
            glm::vec4 worldPos = poseMat * camPos;

            // 2. Voxel Hashing
            VoxelKey key = {
                    (int)floor(worldPos.x / VOXEL_SIZE),
                    (int)floor(worldPos.y / VOXEL_SIZE),
                    (int)floor(worldPos.z / VOXEL_SIZE)
            };

            if (mVoxelGrid.find(key) == mVoxelGrid.end()) {
                // New Splat
                Splat s;
                s.pos = glm::vec3(worldPos);

                // Scale initialization
                float pixelSize = 2.0f * depth * tanHalfFov / height;
                float splatScale = pixelSize * SAMPLE_STRIDE * 1.5f;
                s.scale = glm::vec3(splatScale);

                // Rotation: Identity for now
                s.rot = glm::quat(1.0f, 0.0f, 0.0f, 0.0f);

                // Color
                if (colorData) {
                    // Assuming RGBA buffer from JNI
                    int pIdx = (y * colorStride) + (x * 4);
                    s.color = glm::vec3(
                            colorData[pIdx] / 255.0f,
                            colorData[pIdx + 1] / 255.0f,
                            colorData[pIdx + 2] / 255.0f
                    );
                } else {
                    s.color = glm::vec3(0.0f, 1.0f, 0.0f);
                }
                s.opacity = 0.5f;

                mSplats.push_back(s);
                mVoxelGrid[key] = mSplats.size() - 1;
                mGlDirty = true;
            } else {
                // Update Existing
                int splatIdx = mVoxelGrid[key];
                Splat& s = mSplats[splatIdx];

                // Moving Average for Position (Densification/Refinement)
                s.pos = glm::mix(s.pos, glm::vec3(worldPos), 0.1f);

                // Update Color
                if (colorData) {
                    int pIdx = (y * colorStride) + (x * 4);
                    glm::vec3 newColor(
                            colorData[pIdx] / 255.0f,
                            colorData[pIdx + 1] / 255.0f,
                            colorData[pIdx + 2] / 255.0f
                    );
                    s.color = glm::mix(s.color, newColor, 0.1f);
                }

                if (s.opacity < 1.0f) s.opacity += 0.05f;
            }
        }
    }
}

bool MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;

    // Header "GXRM"
    out.write("GXRM", 4);
    int version = 2; // Bump to v2 for new Splat struct
    out.write((char*)&version, sizeof(int));

    int count = mSplats.size();
    out.write((char*)&count, sizeof(int));

    out.write((char*)mSplats.data(), count * sizeof(Splat));
    return true;
}

// Backward compatibility struct
struct SplatV1 {
    float x, y, z;
    float r, g, b;
    float opacity;
};

bool MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mChunkMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    char header[4];
    in.read(header, 4);
    if (strncmp(header, "GXRM", 4) != 0) return false;

    int version;
    in.read((char*)&version, sizeof(int));

    int count;
    in.read((char*)&count, sizeof(int));

    if (count < 0 || count > MAX_SPLATS) {
        LOGE("Invalid splat count in model file: %d", count);
        return false;
    }

    if (version == 1) {
        // Upgrade from V1
        std::vector<SplatV1> oldSplats(count);
        in.read((char*)oldSplats.data(), count * sizeof(SplatV1));

        mSplats.clear();
        mSplats.reserve(count);
        for(const auto& os : oldSplats) {
            Splat s;
            s.pos = glm::vec3(os.x, os.y, os.z);
            s.color = glm::vec3(os.r, os.g, os.b);
            s.opacity = os.opacity;
            // Defaults
            s.scale = glm::vec3(0.05f); // 5cm default
            s.rot = glm::quat(1.0f, 0.0f, 0.0f, 0.0f);
            mSplats.push_back(s);
        }
    } else if (version == 2) {
        mSplats.resize(count);
        in.read((char*)mSplats.data(), count * sizeof(Splat));
    } else {
        LOGE("Unknown map version: %d", version);
        return false;
    }

    // Rebuild Voxel Grid
    mVoxelGrid.clear();
    for(int i=0; i<mSplats.size(); i++) {
        const Splat& s = mSplats[i];
        VoxelKey key = {
                (int)floor(s.pos.x / VOXEL_SIZE),
                (int)floor(s.pos.y / VOXEL_SIZE),
                (int)floor(s.pos.z / VOXEL_SIZE)
        };
        mVoxelGrid[key] = i;
    }

    mGlDirty = true;
    return true;
}

void MobileGS::alignMap(float* transformMtx) {
    std::lock_guard<std::mutex> lock(mChunkMutex);

    glm::mat4 T = glm::make_mat4(transformMtx);
    glm::mat3 R = glm::mat3(T);
    glm::vec3 t = glm::vec3(T[3]);
    glm::quat q = glm::quat_cast(R);

    for (auto& s : mSplats) {
        // 1. Position: P' = R*P + t
        s.pos = R * s.pos + t;

        // 2. Rotation: q' = q_align * q_old
        s.rot = q * s.rot;
    }

    // 3. Rebuild Voxel Grid
    mVoxelGrid.clear();
    for(int i=0; i<mSplats.size(); i++) {
        const Splat& s = mSplats[i];
        VoxelKey key = {
                (int)floor(s.pos.x / VOXEL_SIZE),
                (int)floor(s.pos.y / VOXEL_SIZE),
                (int)floor(s.pos.z / VOXEL_SIZE)
        };
        mVoxelGrid[key] = i;
    }

    mGlDirty = true;
    LOGI("MobileGS: Map aligned with correction matrix.");
}

bool MobileGS::importModel3D(const std::string& path) {
    LOGI("Importing 3D model from %s", path.c_str());

    std::ifstream f(path.c_str());
    if (!f.good()) {
        LOGE("3D Model file not found: %s", path.c_str());
        return false;
    }

    // TODO: Integrate tinygltf or similar for .glb/.gltf
    // Currently returns false to indicate lack of full implementation in v1.0
    LOGE("3D Model import not implemented in this build.");
    return false;
}