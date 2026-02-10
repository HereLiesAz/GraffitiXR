#include "MobileGS.h"
#include <jni.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <vector>

#define LOG_TAG "MobileGS_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MAX_SPLATS 50000

static GLuint gProgram = 0;

MobileGS::MobileGS() {
    m_Splats.reserve(MAX_SPLATS);
    LOGI("MobileGS Created");
}

MobileGS::~MobileGS() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_Splats.clear();
    mVoxelGrid.clear();
    if (gProgram != 0) {
        glDeleteProgram(gProgram);
        gProgram = 0;
    }
    LOGI("MobileGS Destroyed");
}

static GLuint loadShader(GLenum type, const char* shaderSrc) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;

    glShaderSource(shader, 1, &shaderSrc, NULL);
    glCompileShader(shader);

    GLint compiled;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, NULL, infoLog);
            LOGE("Error compiling shader:\n%s", infoLog);
            delete[] infoLog;
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

void MobileGS::initialize() {
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // Compile Shader
    const char* vShaderStr =
        "attribute vec4 vPosition;\n"
        "attribute float vConfidence;\n"
        "varying float fConfidence;\n"
        "uniform mat4 uMVPMatrix;\n"
        "void main() {\n"
        "  gl_Position = uMVPMatrix * vPosition;\n"
        "  gl_PointSize = 15.0 * vConfidence;\n"
        "  fConfidence = vConfidence;\n"
        "}\n";

    const char* fShaderStr =
        "precision mediump float;\n"
        "varying float fConfidence;\n"
        "void main() {\n"
        "  gl_FragColor = vec4(0.0, 1.0, 0.0, fConfidence);\n"
        "}\n";

    GLuint vs = loadShader(GL_VERTEX_SHADER, vShaderStr);
    GLuint fs = loadShader(GL_FRAGMENT_SHADER, fShaderStr);

    if (vs == 0 || fs == 0) {
        LOGE("Failed to load shaders");
        return;
    }

    gProgram = glCreateProgram();
    glAttachShader(gProgram, vs);
    glAttachShader(gProgram, fs);
    glLinkProgram(gProgram);

    GLint linked;
    glGetProgramiv(gProgram, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(gProgram, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetProgramInfoLog(gProgram, infoLen, NULL, infoLog);
            LOGE("Error linking program:\n%s", infoLog);
            delete[] infoLog;
        }
        glDeleteProgram(gProgram);
        gProgram = 0;
        return;
    }

    LOGI("MobileGS Initialized, Program: %d", gProgram);
}

void MobileGS::updateCamera(const float* view, const float* proj) {
    if (!view || !proj) return;
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    memcpy(m_ViewMatrix, view, 16 * sizeof(float));
    memcpy(m_ProjMatrix, proj, 16 * sizeof(float));
}

static void invertViewMatrix(const float* m, float* out) {
    out[0] = m[0]; out[4] = m[1]; out[8] = m[2];
    out[1] = m[4]; out[5] = m[5]; out[9] = m[6];
    out[2] = m[8]; out[6] = m[9]; out[10] = m[10];

    float tx = m[12];
    float ty = m[13];
    float tz = m[14];

    out[12] = -(out[0] * tx + out[4] * ty + out[8] * tz);
    out[13] = -(out[1] * tx + out[5] * ty + out[9] * tz);
    out[14] = -(out[2] * tx + out[6] * ty + out[10] * tz);

    out[3] = 0.0f; out[7] = 0.0f; out[11] = 0.0f; out[15] = 1.0f;
}

void MobileGS::processDepthFrame(const cv::Mat& depth, int width, int height) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);

    if (m_Splats.size() >= MAX_SPLATS) {
        // Simple strategy: if full, stop adding
    }

    float fx = m_ProjMatrix[0] * width / 2.0f;
    float fy = m_ProjMatrix[5] * height / 2.0f;
    float cx = width / 2.0f;
    float cy = height / 2.0f;

    float invView[16];
    invertViewMatrix(m_ViewMatrix, invView);

    int stride = 8; // Performance optimization

    for (int v = 0; v < height; v += stride) {
        // Correctly use OpenCV row pointer to handle stride/padding
        const uint16_t* rowPtr = depth.ptr<uint16_t>(v);

        for (int u = 0; u < width; u += stride) {
            uint16_t d_raw = rowPtr[u];
            if (d_raw == 0 || d_raw > 5000) continue; // max 5m

            float d = d_raw * 0.001f;

            float x_c = (u - cx) * d / fx;
            float y_c = -(v - cy) * d / fy;
            float z_c = -d;

            float x = invView[0]*x_c + invView[4]*y_c + invView[8]*z_c + invView[12];
            float y = invView[1]*x_c + invView[5]*y_c + invView[9]*z_c + invView[13];
            float z = invView[2]*x_c + invView[6]*y_c + invView[10]*z_c + invView[14];

            VoxelKey key;
            key.x = (int)floor(x / VOXEL_SIZE);
            key.y = (int)floor(y / VOXEL_SIZE);
            key.z = (int)floor(z / VOXEL_SIZE);

            if (mVoxelGrid.find(key) != mVoxelGrid.end()) {
                int idx = mVoxelGrid[key];
                Splat& s = m_Splats[idx];
                float N = (float)s.updateCount;
                s.x = (s.x * N + x) / (N + 1);
                s.y = (s.y * N + y) / (N + 1);
                s.z = (s.z * N + z) / (N + 1);
                s.confidence = std::min(1.0f, s.confidence + 0.05f);
                s.updateCount++;
            } else {
                if (m_Splats.size() < MAX_SPLATS) {
                    Splat s;
                    s.x = x; s.y = y; s.z = z;
                    s.r = 0.0f; s.g = 1.0f; s.b = 0.0f; s.a = 1.0f;
                    s.confidence = 0.1f;
                    s.updateCount = 1;

                    mVoxelGrid[key] = m_Splats.size();
                    m_Splats.push_back(s);
                }
            }
        }
    }
}

void MobileGS::draw() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    if (m_Splats.empty()) return;
    if (gProgram == 0) return;

    glUseProgram(gProgram);

    float mvp[16];
    // Inline multiply
    for (int i=0; i<4; i++) {
        for (int j=0; j<4; j++) {
            mvp[j*4+i] = 0.0f;
            for (int k=0; k<4; k++) {
                mvp[j*4+i] += m_ProjMatrix[k*4+i] * m_ViewMatrix[j*4+k];
            }
        }
    }

    GLint uMVP = glGetUniformLocation(gProgram, "uMVPMatrix");
    glUniformMatrix4fv(uMVP, 1, GL_FALSE, mvp);

    std::vector<float> buffer;
    buffer.reserve(m_Splats.size() * 4);
    for (const auto& s : m_Splats) {
        if (s.confidence < 0.2f) continue;
        buffer.push_back(s.x);
        buffer.push_back(s.y);
        buffer.push_back(s.z);
        buffer.push_back(s.confidence);
    }

    if (buffer.empty()) return;

    GLint vPos = glGetAttribLocation(gProgram, "vPosition");
    GLint vConf = glGetAttribLocation(gProgram, "vConfidence");

    glEnableVertexAttribArray(vPos);
    glEnableVertexAttribArray(vConf);

    glVertexAttribPointer(vPos, 3, GL_FLOAT, GL_FALSE, 4 * sizeof(float), buffer.data());
    glVertexAttribPointer(vConf, 1, GL_FLOAT, GL_FALSE, 4 * sizeof(float), buffer.data() + 3);

    glDrawArrays(GL_POINTS, 0, buffer.size() / 4);

    glDisableVertexAttribArray(vPos);
    glDisableVertexAttribArray(vConf);
}

int MobileGS::getPointCount() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    return (int)m_Splats.size();
}

bool MobileGS::saveModel(const std::string& path) {
    return true;
}

bool MobileGS::loadModel(const std::string& path) {
    return true;
}

void MobileGS::applyTransform(const float* transform) {
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_Splats.clear();
    mVoxelGrid.clear();
}
