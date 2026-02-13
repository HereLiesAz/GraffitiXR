#ifndef MOBILE_GS_H
#define MOBILE_GS_H

#include <vector>
#include <string>
#include <mutex>
#include <unordered_map>
#include <GLES3/gl3.h>
#include <opencv2/core.hpp>
#include <glm/glm.hpp>
#include <glm/gtc/quaternion.hpp>

// Voxel Key for Spatial Hashing
struct VoxelKey {
    int x, y, z;
    bool operator==(const VoxelKey& other) const {
        return x == other.x && y == other.y && z == other.z;
    }
};

struct VoxelKeyHash {
    std::size_t operator()(const VoxelKey& k) const {
        return ((k.x * 73856093) ^ (k.y * 19349663) ^ (k.z * 83492791));
    }
};

struct Splat {
    glm::vec3 pos;
    glm::vec3 scale;
    glm::quat rot;
    glm::vec3 color;
    float opacity; // Used for confidence
};

class MobileGS {
public:
    MobileGS();
    ~MobileGS();

    // Lifecycle
    void initialize();
    void resetGL(); // NEW: Handle Context Loss
    void clear();

    // Input
    void onSurfaceChanged(int width, int height);
    void updateCamera(float* viewMtx, float* projMtx);
    void feedDepthData(uint16_t* depthData, uint8_t* colorData, int width, int height, int stride, float* poseMtx, float fov);
    void setTargetDescriptors(const cv::Mat& descriptors);

    // Rendering
    void draw();

    // Map Management
    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);
    void pruneMap(int ageThreshold);
    void alignMap(float* transformMtx);
    int getSplatCount();

private:
    // GL State
    GLuint mProgram = 0;
    GLint mLocView = -1;
    GLint mLocProj = -1;
    GLint mLocCamPos = -1;

    // Instancing buffers
    GLuint mVBO_Quad = 0;      // Static unit quad
    GLuint mVBO_Instance = 0;  // Dynamic splat data

    bool mGlDirty = true; // Signals need to re-upload VBO

    // Data State
    std::vector<Splat> mSplats;
    std::unordered_map<VoxelKey, int, VoxelKeyHash> mVoxelGrid;
    std::mutex mChunkMutex;

    // Matrices
    glm::mat4 mViewMat;
    glm::mat4 mProjMat;
    glm::vec3 mCamPos;

    // Target (Teleological)
    cv::Mat mTargetDescriptors;
    bool mHasTarget = false;

    int mFrameCount = 0;
    int mViewportWidth = 0;
    int mViewportHeight = 0;

    void updateMatrices();
    void uploadSplatData(); // Helper to send mSplats to GPU
    void sortSplats();
};

#endif // MOBILE_GS_H