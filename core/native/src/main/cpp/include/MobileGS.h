#ifndef MOBILEGS_H
#define MOBILEGS_H

#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <opencv2/core.hpp>
#include <GLES2/gl2.h>

// Constants
constexpr float VOXEL_SIZE = 0.02f; // 2cm voxels
constexpr float CONFIDENCE_THRESHOLD = 3.0f;
constexpr float CONFIDENCE_INCREMENT = 1.0f;
constexpr int MAX_SPLATS = 500000;
constexpr float CULL_DISTANCE = 5.0f; // Meters

// Tuning Constants
constexpr int MAX_DEPTH_MM = 8000;
constexpr float SPLAT_LEARNING_RATE = 0.1f;
constexpr float CONFIDENCE_BOOST_THRESHOLD = 5.0f;

// Internal structure for a gaussian splat point
struct Splat {
    float x, y, z;
    float r, g, b, a;
    float confidence;
};

// Voxel Key for Sparse Hashing
struct VoxelKey {
    int x, y, z;
    bool operator==(const VoxelKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

// Hash function for VoxelKey
struct VoxelKeyHash {
    std::size_t operator()(const VoxelKey& k) const {
        return std::hash<int>()(k.x) ^ (std::hash<int>()(k.y) << 1) ^ (std::hash<int>()(k.z) << 2);
    }
};

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    bool initialize();
    void updateCamera(const float* view, const float* proj);
    void processDepthFrame(const cv::Mat& depth, int width, int height);
    void draw();

    int getPointCount();

    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);

    void applyTransform(const float* transform);
    void clear();

private:
    std::vector<Splat> m_Splats;
    std::vector<float> m_DrawBuffer; // Reuse buffer to reduce allocations
    std::unordered_map<VoxelKey, int, VoxelKeyHash> mVoxelGrid;
    std::mutex m_SplatsMutex;
    float m_ViewMatrix[16];
    float m_ProjMatrix[16];

    // Encapsulated Shader State
    GLuint m_Program = 0;
    GLint m_LocMVP = -1;
};

#endif // MOBILEGS_H
