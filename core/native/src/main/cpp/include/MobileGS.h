#ifndef MOBILEGS_H
#define MOBILEGS_H

#include <string>
#include <vector>
#include <mutex>
#include <opencv2/core.hpp>

// Internal structure for a gaussian splat point
struct Splat {
    float x, y, z;
    float r, g, b, a;
    float confidence;
};

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void initialize();
    void updateCamera(const float* view, const float* proj);
    void processDepthFrame(const cv::Mat& depth, int width, int height);
    void draw();

    int getPointCount();

    // CHANGED: Return bool to satisfy JNI check
    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);

    void applyTransform(const float* transform);
    void clear();

private:
    std::vector<Splat> m_Splats;
    std::mutex m_SplatsMutex;
    float m_ViewMatrix[16];
    float m_ProjMatrix[16];
};

#endif // MOBILEGS_H