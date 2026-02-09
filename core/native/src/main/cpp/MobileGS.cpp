#include "MobileGS.h"
#include <jni.h>
#include <GLES2/gl2.h>
#include <android/log.h>

#define LOG_TAG "MobileGS_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define MAX_SPLATS 5000
#define DRAW_STRIDE 20

MobileGS::MobileGS() {
    m_Splats.reserve(MAX_SPLATS);
    LOGI("MobileGS Created");
}

MobileGS::~MobileGS() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_Splats.clear();
    LOGI("MobileGS Destroyed");
}

void MobileGS::initialize() {
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    LOGI("MobileGS Initialized");
}

void MobileGS::updateCamera(const float* view, const float* proj) {
    if (!view || !proj) return;
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    memcpy(m_ViewMatrix, view, 16 * sizeof(float));
    memcpy(m_ProjMatrix, proj, 16 * sizeof(float));
}

void MobileGS::processDepthFrame(const cv::Mat& depth, int width, int height) {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);

    if (m_Splats.size() >= MAX_SPLATS) {
        size_t removeCount = m_Splats.size() / 10;
        if (removeCount < 1) removeCount = 1;
        m_Splats.erase(m_Splats.begin(), m_Splats.begin() + removeCount);
    }
    // Projection logic would go here
}

void MobileGS::draw() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    if (m_Splats.empty()) return;

    glEnable(GL_BLEND);

    std::vector<float> drawBuffer;
    drawBuffer.reserve((m_Splats.size() / DRAW_STRIDE) * 7);

    for (size_t i = 0; i < m_Splats.size(); i += DRAW_STRIDE) {
        const auto& s = m_Splats[i];

        drawBuffer.push_back(s.x);
        drawBuffer.push_back(s.y);
        drawBuffer.push_back(s.z);

        drawBuffer.push_back(s.r);
        drawBuffer.push_back(s.g);
        drawBuffer.push_back(s.b);
        drawBuffer.push_back(0.3f);
    }

    if (drawBuffer.empty()) return;

    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);

    int stride = 7 * sizeof(float);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, drawBuffer.data());
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, drawBuffer.data() + 3);

    glDrawArrays(GL_POINTS, 0, drawBuffer.size() / 7);

    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
}

int MobileGS::getPointCount() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    return (int)m_Splats.size();
}

bool MobileGS::saveModel(const std::string& path) {
    LOGI("saveModel: %s", path.c_str());
    // Placeholder: Return true to indicate success
    return true;
}

bool MobileGS::loadModel(const std::string& path) {
    LOGI("loadModel: %s", path.c_str());
    // Placeholder: Return true to indicate success
    return true;
}

void MobileGS::applyTransform(const float* transform) {
    LOGI("applyTransform");
}

void MobileGS::clear() {
    std::lock_guard<std::mutex> lock(m_SplatsMutex);
    m_Splats.clear();
}