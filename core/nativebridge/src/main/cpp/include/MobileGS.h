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
#include "VulkanBackend.h"

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

struct MeshVertex {
    glm::vec3 pos;
    glm::vec3 normal;
};

enum class EngineStatus : int {
    SUCCESS = 0,
    ERROR_NOT_INITIALIZED = -1,
    ERROR_INVALID_ARGUMENT = -2,
    ERROR_OUT_OF_MEMORY = -3,
    ERROR_GPU_ERROR = -4,
    ERROR_IO_FAILURE = -5
};

enum class SplatVisualizationMode : int {
    GAUSSIAN = 0,
    POINT_CLOUD = 1,
    WIREFRAME = 2,
    FOG_OF_WAR = 3
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
    void updateLight(float intensity, float r, float g, float b); // NEW: Light estimation
    void setVisualizationMode(int mode); // NEW: Visualization mode
    void feedDepthData(uint16_t* depthData, uint8_t* colorData, int width, int height, int depthStride, int colorStride, float* poseMtx, float fov);
    void updateMesh(float* vertices, int vertexCount); // NEW: Update surface mesh
    void setTargetDescriptors(const cv::Mat& descriptors);

    // Vulkan Support
    void initVulkan(void* nativeWindow);
    void resizeVulkan(int width, int height);

    // Rendering
    void draw();

    // Splat Training / Refinement
    void trainStep();

    // Map Management
    bool saveModel(const std::string& path);
    bool loadModel(const std::string& path);
    bool importModel3D(const std::string& path); // NEW: .glb/.gltf import
    void pruneMap(int ageThreshold);
    void alignMap(float* transformMtx);
    int getSplatCount();

private:
    // GL State
    GLuint mProgram = 0;
    GLuint mFogProgram = 0; // NEW: Fog overlay shader
    GLint mLocView = -1;
    GLint mLocProj = -1;
    GLint mLocFogColor = -1;

    // Mesh State
    GLuint mMeshVBO = 0;
    int mMeshVertexCount = 0;
    std::vector<MeshVertex> mMeshVertices;
    
    // Instancing buffers
    GLuint mVBO_Quad = 0;      // Static unit quad
    GLuint mVBO_Instance = 0;  // Dynamic splat data
    
    bool mGlDirty = true; // Signals need to re-upload VBO
    bool mMeshDirty = false;

    // Data State
    std::vector<Splat> mSplats;
    std::unordered_map<VoxelKey, int, VoxelKeyHash> mVoxelGrid;
    std::mutex mChunkMutex;

    // Matrices
    glm::mat4 mViewMat;
    glm::mat4 mProjMat;
    glm::vec3 mCamPos;

    // Light Estimation
    float mLightIntensity = 1.0f;
    glm::vec3 mLightColor = glm::vec3(1.0f);

    // Target (Teleological)
    cv::Mat mTargetDescriptors;
    bool mHasTarget = false;

    int mFrameCount = 0;
    int mViewportWidth = 0;
    int mViewportHeight = 0;

    int mVizMode = 0; // NEW: Visualization mode

    VulkanBackend* mVulkanBackend = nullptr;

    void uploadSplatData(); // Helper to send mSplats to GPU
    void uploadMesh();
    void sortSplats();
};

#endif // MOBILE_GS_H
