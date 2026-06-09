#pragma once
#include <opencv2/opencv.hpp>
#include <vector>
#include <mutex>
#include <atomic>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <glm/gtc/type_ptr.hpp>

struct MeshVertex {
    float x, y, z;          // Position (Anchor-Local Space)
    float u, v;             // Texture Coordinates (Mural Space)
    float confidence;       // Persistence/Weight
};

class SurfaceMesh {
public:
    SurfaceMesh();
    ~SurfaceMesh();

    void initGl();
    void update(const cv::Mat& depth, const cv::Mat& color, const float* viewMat, const float* projMat, const float* anchorMatrix, float lightLevel);
    void draw(const glm::mat4& mvp);
    void clear();
    void getMesh(std::vector<float>& outVertices, std::vector<float>& outWeights);
    void save(const std::string& path);
    void load(const std::string& path);

private:
    void updateTexture(const cv::Mat& color, const std::vector<glm::vec2>& camPoints);

    std::mutex mMutex;
    std::vector<MeshVertex> mPersistentMesh;
    std::vector<uint32_t> mPersistentMeshIndices;
    std::vector<uint32_t> mWireframeIndices;
    bool mInitialized = false;

    cv::Mat mMuralTexture;
    GLuint mTextureId = 0;
    // Atomic so initGl() (GL thread, now lock-free) and the worker threads in update()/updateTexture()
    // can touch these dirty flags without a data race — without re-taking the contended data mutex.
    std::atomic<bool> mTextureDirty{false};
    std::atomic<bool> mMeshDirty{false};
    std::atomic<bool> mIndicesUploaded{false};
    int mNextTexturePatchIndex = 0;

    GLuint mProgram = 0;
    GLuint mVbo = 0;
    GLuint mIbo = 0;
    GLuint mWireIbo = 0;

    static constexpr int MESH_GRID_DIM = 128;
    static constexpr int TEXTURE_SIZE = 1024;
};
