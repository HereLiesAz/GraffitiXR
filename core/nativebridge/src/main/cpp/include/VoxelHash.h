#pragma once
#include <opencv2/opencv.hpp>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <glm/gtc/type_ptr.hpp>

struct Splat {
    float x, y, z;          // Position (World Space)
    float r, g, b, a;       // Color
    float confidence;       // Persistence
    float nx, ny, nz;       // Surface Normal
    float radius;           // Splat Scale
};

struct VoxelKey {
    int x, y, z;
    bool operator==(const VoxelKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

struct VoxelKeyHash {
    std::size_t operator()(const VoxelKey& k) const {
        return ((std::hash<int>()(k.x) ^ (std::hash<int>()(k.y) << 1)) >> 1) ^ (std::hash<int>()(k.z) << 1);
    }
};

class VoxelHash {
public:
    VoxelHash();
    ~VoxelHash();

    void initGl();
    void update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, float voxelSize);
    void draw(const glm::mat4& mvp, float focalY, int screenHeight);
    void clear();
    void prune(float threshold);
    int getSplatCount() const;

private:
    void pruneInternal(float threshold);
    void pruneMap();

    mutable std::mutex mMutex;
    std::vector<Splat> mSplatData;
    std::unordered_map<VoxelKey, int, VoxelKeyHash> mVoxelGrid;

    float mLastVoxelSize = 0.005f;
    GLuint mProgram = 0;
    GLuint mPointVbo = 0;
    GLuint mIndexVbo = 0;

    static constexpr int MAX_SPLATS = 500000;
};
