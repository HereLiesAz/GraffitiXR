#include "MobileGS.h"
#include <android/log.h>
#include <fstream>
#include <cmath>
#include <glm/gtc/type_ptr.hpp>
#include <glm/gtc/matrix_transform.hpp>

#define LOG_TAG "MobileGS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* VERTEX_SHADER = R"(#version 300 es
uniform mat4 u_MVP;
uniform float u_PointSize;
layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec3 a_Color;
layout(location = 2) in float a_Opacity;
out vec4 v_Color;
void main() {
    gl_Position = u_MVP * vec4(a_Position, 1.0);
    gl_PointSize = u_PointSize;
    v_Color = vec4(a_Color, a_Opacity);
}
)";

static const char* FRAGMENT_SHADER = R"(#version 300 es
precision mediump float;
in vec4 v_Color;
out vec4 FragColor;
void main() {
    vec2 coord = gl_PointCoord - vec2(0.5);
    if(length(coord) > 0.5) discard;
    FragColor = v_Color;
}
)";

MobileGS::MobileGS()
    : m_Program(0), m_LocMVP(-1), m_LocPointSize(-1), m_VAO(0), m_VBO(0),
      m_IsInitialized(false), m_Width(0), m_Height(0), m_WorldTransform(1.0f) {
    m_Splats.reserve(MAX_SPLATS);
}

MobileGS::~MobileGS() {
    if (m_Program != 0) {
        glDeleteProgram(m_Program);
    }
    if (m_VAO != 0) {
        glDeleteVertexArrays(1, &m_VAO);
    }
    if (m_VBO != 0) {
        glDeleteBuffers(1, &m_VBO);
    }
}

void MobileGS::initialize() {
    if (m_IsInitialized) return;
    compileShaders();
    m_IsInitialized = true;
    LOGI("MobileGS initialized");
}

void MobileGS::compileShaders() {
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &VERTEX_SHADER, nullptr);
    glCompileShader(vs);

    GLint compiled;
    glGetShaderiv(vs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        glDeleteShader(vs);
        return;
    }

    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &FRAGMENT_SHADER, nullptr);
    glCompileShader(fs);

    glGetShaderiv(fs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        glDeleteShader(fs);
        return;
    }

    m_Program = glCreateProgram();
    glAttachShader(m_Program, vs);
    glAttachShader(m_Program, fs);
    glLinkProgram(m_Program);

    GLint linked;
    glGetProgramiv(m_Program, GL_LINK_STATUS, &linked);
    if (!linked) {
        glDeleteProgram(m_Program);
        return;
    }

    glDeleteShader(vs);
    glDeleteShader(fs);

    m_LocMVP = glGetUniformLocation(m_Program, "u_MVP");
    m_LocPointSize = glGetUniformLocation(m_Program, "u_PointSize");

    glGenVertexArrays(1, &m_VAO);
    glGenBuffers(1, &m_VBO);
}

void MobileGS::updateCamera(const float* viewMtx, const float* projMtx) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_ViewMatrix = glm::make_mat4(viewMtx);
    m_ProjMatrix = glm::make_mat4(projMtx);
}

void MobileGS::feedDepthData(const uint16_t* depthData, int width, int height) {
    if (!depthData || width <= 0 || height <= 0 || !m_IsInitialized) return;

    std::lock_guard<std::mutex> lock(m_SplatsMutex);

    // Extract intrinsics from Projection Matrix (approximate for ARCore)
    // P[0][0] = 2 * fx / w
    // P[1][1] = 2 * fy / h
    float fx = m_ProjMatrix[0][0] * width / 2.0f;
    float fy = m_ProjMatrix[1][1] * height / 2.0f;
    float cx = width / 2.0f; // Approx
    float cy = height / 2.0f; // Approx

    glm::mat4 invView = glm::inverse(m_ViewMatrix);
    glm::vec3 camPos = glm::vec3(invView[3]);

    // Stride to reduce processing
    int stride = 8;

    for (int y = 0; y < height; y += stride) {
        for (int x = 0; x < width; x += stride) {
            uint16_t d = depthData[y * width + x];
            if (d == 0 || d > 5000) continue; // Skip invalid or too far (5m)

            float depthM = d * 0.001f; // mm to meters

            // Unproject to Camera Space
            // Z is negative in OpenGL camera space
            float z_cam = -depthM;
            float x_cam = (x - cx) * depthM / fx;
            float y_cam = -(y - cy) * depthM / fy; // Flip Y for image coords

            glm::vec4 P_cam(x_cam, y_cam, z_cam, 1.0f);
            glm::vec4 P_world = invView * P_cam;

            // Voxel Grid Integration
            VoxelKey key;
            key.x = (int)std::floor(P_world.x / VOXEL_SIZE);
            key.y = (int)std::floor(P_world.y / VOXEL_SIZE);
            key.z = (int)std::floor(P_world.z / VOXEL_SIZE);

            if (m_VoxelGrid.find(key) != m_VoxelGrid.end()) {
                // Update existing
                int idx = m_VoxelGrid[key];
                Splat& s = m_Splats[idx];
                s.confidence = std::min(1.0f, s.confidence + CONFIDENCE_INCREMENT);
                // In a real system, we'd fuse color here too (weighted avg)
            } else {
                // Create new
                if (m_Splats.size() < MAX_SPLATS) {
                    Splat s;
                    s.x = P_world.x;
                    s.y = P_world.y;
                    s.z = P_world.z;
                    // Default color (Cyan-ish for now, since we don't have RGB here yet)
                    s.r = 0.0f; s.g = 1.0f; s.b = 1.0f;
                    s.opacity = 1.0f;
                    s.scale = VOXEL_SIZE;
                    s.confidence = 0.1f;

                    m_VoxelGrid[key] = m_Splats.size();
                    m_Splats.push_back(s);
                }
            }
        }
    }
}

void MobileGS::draw() {
    if (!m_IsInitialized || m_Program == 0) return;

    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    if (m_Splats.empty()) return;

    glUseProgram(m_Program);
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    glm::mat4 mvp = m_ProjMatrix * m_ViewMatrix * m_WorldTransform;
    glUniformMatrix4fv(m_LocMVP, 1, GL_FALSE, glm::value_ptr(mvp));
    glUniform1f(m_LocPointSize, 15.0f);

    // Populate Draw Buffer
    // Format: x, y, z, r, g, b, a (7 floats per splat)
    m_DrawBuffer.clear();
    m_DrawBuffer.reserve(m_Splats.size() * 7);
    for (const auto& s : m_Splats) {
        // Simple confidence visualization: Alpha fades in
        m_DrawBuffer.push_back(s.x);
        m_DrawBuffer.push_back(s.y);
        m_DrawBuffer.push_back(s.z);

        // Color based on confidence (Red -> Green)
        float conf = s.confidence;
        m_DrawBuffer.push_back(1.0f - conf); // R
        m_DrawBuffer.push_back(conf);        // G
        m_DrawBuffer.push_back(0.0f);        // B

        m_DrawBuffer.push_back(s.opacity * conf);
    }

    glBindVertexArray(m_VAO);
    glBindBuffer(GL_ARRAY_BUFFER, m_VBO);
    glBufferData(GL_ARRAY_BUFFER, m_DrawBuffer.size() * sizeof(float), m_DrawBuffer.data(), GL_DYNAMIC_DRAW);

    // Attribs
    // 0: Position (3 floats)
    // 1: Color (3 floats)
    // 2: Opacity (1 float)
    GLsizei stride = 7 * sizeof(float);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, (void*)0);

    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, stride, (void*)(3 * sizeof(float)));

    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, stride, (void*)(6 * sizeof(float)));

    glDrawArrays(GL_POINTS, 0, m_Splats.size());

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glDisableVertexAttribArray(2);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindVertexArray(0);
    glDisable(GL_BLEND);
}

void MobileGS::onSurfaceChanged(int width, int height) {
    m_Width = width;
    m_Height = height;
    glViewport(0, 0, width, height);
}

bool MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;

    uint32_t count = m_Splats.size();
    out.write((char*)&count, sizeof(count));
    if (count > 0) {
        out.write((char*)m_Splats.data(), count * sizeof(Splat));
    }
    return true;
}

bool MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    uint32_t count = 0;
    in.read((char*)&count, sizeof(count));
    if (count > MAX_SPLATS) count = MAX_SPLATS; // Safety cap

    m_Splats.resize(count);
    if (count > 0) {
        in.read((char*)m_Splats.data(), count * sizeof(Splat));
    }

    // Rebuild Voxel Grid
    m_VoxelGrid.clear();
    for(int i=0; i<m_Splats.size(); ++i) {
        const auto& s = m_Splats[i];
        VoxelKey key;
        key.x = (int)std::floor(s.x / VOXEL_SIZE);
        key.y = (int)std::floor(s.y / VOXEL_SIZE);
        key.z = (int)std::floor(s.z / VOXEL_SIZE);
        m_VoxelGrid[key] = i;
    }

    return true;
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_Splats.clear();
    m_VoxelGrid.clear();
}

int MobileGS::getSplatCount() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    return (int)m_Splats.size();
}

void MobileGS::alignMap(const float* transformMtx) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_WorldTransform = glm::make_mat4(transformMtx);
}
