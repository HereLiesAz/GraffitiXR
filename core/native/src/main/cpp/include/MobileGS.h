#ifndef MOBILEGS_H
#define MOBILEGS_H

#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <functional>
#include <opencv2/core.hpp>

// Voxel Size in meters (20mm)
#define VOXEL_SIZE 0.02f

// Voxel Key for hashing
struct VoxelKey {
    int x, y, z;

    bool operator==(const VoxelKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

// Hasher for VoxelKey
struct VoxelKeyHash {
    std::size_t operator()(const VoxelKey& k) const {
        // Simple hash combination
        size_t h1 = std::hash<int>()(k.x);
        size_t h2 = std::hash<int>()(k.y);
        size_t h3 = std::hash<int>()(k.z);
        return h1 ^ (h2 << 1) ^ (h3 << 2);
    }
};

// Internal structure for a gaussian splat point
struct Splat {
    float x, y, z;
    float r, g, b, a;
    float confidence;
    int updateCount;
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

    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);

    void applyTransform(const float* transform);
    void clear();

private:
    std::vector<Splat> m_Splats;
    std::unordered_map<VoxelKey, int, VoxelKeyHash> mVoxelGrid;
    std::mutex m_SplatsMutex;
    float m_ViewMatrix[16];
    float m_ProjMatrix[16];
};

#endif // MOBILEGS_H
