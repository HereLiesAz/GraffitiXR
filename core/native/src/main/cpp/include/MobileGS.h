#ifndef MOBILEGS_H
#define MOBILEGS_H

#include <vector>
#include <string>
#include <mutex>
#include <atomic>
#include <thread>
#include <condition_variable>
#include <unordered_map>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <opencv2/core.hpp>

constexpr float VOXEL_SIZE = 0.02f; // 2cm voxels
constexpr int MAX_SPLATS = 50000;
constexpr float CONFIDENCE_INCREMENT = 0.05f;

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

struct Splat {
    float x, y, z;
    float r, g, b;
    float opacity;
    float scale;
    float confidence;
};

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    void initialize();
    void updateCamera(const float* viewMtx, const float* projMtx);
    void feedDepthData(const uint16_t* depthData, int width, int height);
    void draw();
    void onSurfaceChanged(int width, int height);

    // I/O
    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);
    void clear();
    int getSplatCount();

    // Map Alignment (Touch-based adjustment)
    void alignMap(const float* transformMtx);

private:
    void compileShaders();
    void processVoxelGrid();

    // OpenGL State
    GLuint m_Program;
    GLint m_LocMVP;
    GLint m_LocPointSize;
    GLint m_LocColor; // If used
    GLuint m_VAO;
    GLuint m_VBO;

    // Data
    std::vector<Splat> m_Splats;
    std::vector<float> m_DrawBuffer; // For uploading to GPU
    std::unordered_map<VoxelKey, int, VoxelKeyHash> m_VoxelGrid;

    // Camera State
    glm::mat4 m_ViewMatrix;
    glm::mat4 m_ProjMatrix;
    glm::mat4 m_WorldTransform; // User alignment

    // Synchronization
    std::mutex m_SplatsMutex;
    std::atomic<bool> m_IsInitialized;

    // Viewport
    int m_Width;
    int m_Height;
};

#endif // MOBILEGS_H
