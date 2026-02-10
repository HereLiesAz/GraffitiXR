#include "MobileGS.h"
#include <jni.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <fstream>
#include <cmath>
#include <vector>

#define LOG_TAG "MobileGS_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Shader sources
const char* VS_SRC =
    "attribute vec4 vPosition;\n"
    "attribute vec4 vColor;\n"
    "varying vec4 fColor;\n"
    "uniform mat4 uMVP;\n"
    "void main() {\n"
    "  gl_Position = uMVP * vPosition;\n"
    "  gl_PointSize = 15.0;\n"
    "  fColor = vColor;\n"
    "}\n";

const char* FS_SRC =
    "precision mediump float;\n"
    "varying vec4 fColor;\n"
    "void main() {\n"
    "  gl_FragColor = fColor;\n"
    "}\n";

// Global shader handles
GLuint gProgram = 0;
GLint gLocMVP = -1;

MobileGS::MobileGS() {
    m_Splats.reserve(MAX_SPLATS);
    m_DrawBuffer.reserve(MAX_SPLATS * 7);
    LOGI("MobileGS Created");
}

MobileGS::~MobileGS() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_Splats.clear();
    mVoxelGrid.clear();
    m_DrawBuffer.clear();
    LOGI("MobileGS Destroyed");
}

void MobileGS::initialize() {
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // Compile Shaders
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &VS_SRC, NULL);
    glCompileShader(vs);

    GLint compiled;
    glGetShaderiv(vs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        LOGI("Vertex shader compilation failed");
        return;
    }

    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &FS_SRC, NULL);
    glCompileShader(fs);

    glGetShaderiv(fs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        LOGI("Fragment shader compilation failed");
        return;
    }

    gProgram = glCreateProgram();
    glAttachShader(gProgram, vs);
    glAttachShader(gProgram, fs);

    // Bind attributes BEFORE linking
    glBindAttribLocation(gProgram, 0, "vPosition");
    glBindAttribLocation(gProgram, 1, "vColor");

    glLinkProgram(gProgram);

    GLint linked;
    glGetProgramiv(gProgram, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGI("Shader linking failed");
        return;
    }

    gLocMVP = glGetUniformLocation(gProgram, "uMVP");

    LOGI("MobileGS Initialized with Shader. Program: %d", gProgram);
}

void MobileGS::updateCamera(const float* view, const float* proj) {
    if (!view || !proj) return;
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    memcpy(m_ViewMatrix, view, 16 * sizeof(float));
    memcpy(m_ProjMatrix, proj, 16 * sizeof(float));
}

void MobileGS::processDepthFrame(const cv::Mat& depth, int width, int height) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);

    // 1. Invert Matrices
    // Transpose raw column-major data to row-major OpenCV matrices
    cv::Mat viewMat(4, 4, CV_32F, m_ViewMatrix);
    viewMat = viewMat.t();

    cv::Mat projMat(4, 4, CV_32F, m_ProjMatrix);
    projMat = projMat.t();

    cv::Mat invView, invProj;

    if (cv::determinant(viewMat) == 0 || cv::determinant(projMat) == 0) return;

    cv::invert(viewMat, invView);
    cv::invert(projMat, invProj);

    const int STRIDE = 8; // Process 1 out of every 8x8 pixels

    for (int y = 0; y < height; y += STRIDE) {
        const uint16_t* rowPtr = depth.ptr<uint16_t>(y);
        for (int x = 0; x < width; x += STRIDE) {
            uint16_t d_mm = rowPtr[x];

            if (d_mm == 0 || d_mm > 8000) continue;

            float d_meters = d_mm * 0.001f;
            if (d_meters > CULL_DISTANCE) continue;

            // 2. Unproject to View Space
            float ndc_x = (2.0f * x / width) - 1.0f;
            float ndc_y = 1.0f - (2.0f * y / height); // Flip Y

            // Clip space vector
            cv::Mat clipVec = (cv::Mat_<float>(4, 1) << ndc_x, ndc_y, 0.0f, 1.0f);
            cv::Mat viewVec = invProj * clipVec;

            float w = viewVec.at<float>(3, 0);
            if (std::abs(w) < 1e-5) continue;

            float vx = viewVec.at<float>(0, 0) / w;
            float vy = viewVec.at<float>(1, 0) / w;
            float vz = viewVec.at<float>(2, 0) / w;

            if (std::abs(vz) < 1e-5) continue;
            float scale = -d_meters / vz;

            float px_view = vx * scale;
            float py_view = vy * scale;
            float pz_view = vz * scale;

            // 3. Transform to World Space
            cv::Mat pView = (cv::Mat_<float>(4, 1) << px_view, py_view, pz_view, 1.0f);
            cv::Mat pWorld = invView * pView;

            float wx = pWorld.at<float>(0, 0);
            float wy = pWorld.at<float>(1, 0);
            float wz = pWorld.at<float>(2, 0);

            // 4. Voxel Hashing
            VoxelKey key;
            key.x = (int)std::floor(wx / VOXEL_SIZE);
            key.y = (int)std::floor(wy / VOXEL_SIZE);
            key.z = (int)std::floor(wz / VOXEL_SIZE);

            auto it = mVoxelGrid.find(key);
            if (it != mVoxelGrid.end()) {
                // Update existing splat
                int idx = it->second;
                Splat& s = m_Splats[idx];

                float alpha = 0.1f;
                s.x = s.x * (1.0f - alpha) + wx * alpha;
                s.y = s.y * (1.0f - alpha) + wy * alpha;
                s.z = s.z * (1.0f - alpha) + wz * alpha;

                if (s.confidence < CONFIDENCE_THRESHOLD + 5.0f) {
                    s.confidence += CONFIDENCE_INCREMENT;
                }
            } else {
                // Create new splat
                if (m_Splats.size() < MAX_SPLATS) {
                    Splat s;
                    s.x = wx;
                    s.y = wy;
                    s.z = wz;

                    // Simple coloring: Gradient based on Y (height)
                    s.r = 0.5f + (wy * 0.2f);
                    s.g = 0.8f;
                    s.b = 0.9f;
                    s.a = 0.0f;
                    s.confidence = 1.0f;

                    m_Splats.push_back(s);
                    mVoxelGrid[key] = m_Splats.size() - 1;
                }
            }
        }
    }
}

void MobileGS::draw() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    if (m_Splats.empty() || gProgram == 0) return;

    glUseProgram(gProgram);

    // Compute MVP using Corrected Matrices
    cv::Mat viewMat(4, 4, CV_32F, m_ViewMatrix);
    viewMat = viewMat.t();

    cv::Mat projMat(4, 4, CV_32F, m_ProjMatrix);
    projMat = projMat.t();

    cv::Mat mvp = projMat * viewMat;

    // Transpose back for OpenGL Column-Major order
    cv::Mat mvpGL = mvp.t();

    glUniformMatrix4fv(gLocMVP, 1, GL_FALSE, (float*)mvpGL.data);

    glEnable(GL_BLEND);

    // Optimization: reuse m_DrawBuffer
    m_DrawBuffer.clear();
    // Assuming m_DrawBuffer capacity is sufficient from constructor or previous runs

    for (const auto& s : m_Splats) {
        float alpha = (s.confidence >= CONFIDENCE_THRESHOLD) ? 1.0f : (s.confidence / CONFIDENCE_THRESHOLD);
        if (alpha < 0.1f) continue;

        m_DrawBuffer.push_back(s.x);
        m_DrawBuffer.push_back(s.y);
        m_DrawBuffer.push_back(s.z);

        // Debug Color (Green/Blue)
        m_DrawBuffer.push_back(0.2f);
        m_DrawBuffer.push_back(0.8f);
        m_DrawBuffer.push_back(1.0f);
        m_DrawBuffer.push_back(alpha);
    }

    if (m_DrawBuffer.empty()) return;

    glEnableVertexAttribArray(0); // vPosition
    glEnableVertexAttribArray(1); // vColor

    int stride = 7 * sizeof(float);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, m_DrawBuffer.data());
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, m_DrawBuffer.data() + 3);

    glDrawArrays(GL_POINTS, 0, m_DrawBuffer.size() / 7);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
}

int MobileGS::getPointCount() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    return (int)m_Splats.size();
}

bool MobileGS::saveModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;

    uint64_t count = m_Splats.size();
    out.write(reinterpret_cast<const char*>(&count), sizeof(count));
    out.write(reinterpret_cast<const char*>(m_Splats.data()), count * sizeof(Splat));
    out.close();
    LOGI("Saved model to %s with %llu splats", path.c_str(), (unsigned long long)count);
    return true;
}

bool MobileGS::loadModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    uint64_t count = 0;
    in.read(reinterpret_cast<char*>(&count), sizeof(count));

    m_Splats.resize(count);
    in.read(reinterpret_cast<char*>(m_Splats.data()), count * sizeof(Splat));
    in.close();

    mVoxelGrid.clear();
    for (size_t i = 0; i < m_Splats.size(); ++i) {
        const auto& s = m_Splats[i];
        VoxelKey key;
        key.x = (int)std::floor(s.x / VOXEL_SIZE);
        key.y = (int)std::floor(s.y / VOXEL_SIZE);
        key.z = (int)std::floor(s.z / VOXEL_SIZE);
        mVoxelGrid[key] = i;
    }

    LOGI("Loaded model from %s with %llu splats", path.c_str(), (unsigned long long)count);
    return true;
}

void MobileGS::applyTransform(const float* transform) {
    LOGI("applyTransform");
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_Splats.clear();
    mVoxelGrid.clear();
    m_DrawBuffer.clear();
}
