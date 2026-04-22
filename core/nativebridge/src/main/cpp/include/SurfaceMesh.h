#pragma once
#include <opencv2/opencv.hpp>
#include <vector>
#include <mutex>
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

private:
    void updateTexture(const cv::Mat& color, const std::vector<glm::vec2>& camPoints);

    std::mutex mMutex;
    std::vector<MeshVertex> mPersistentMesh;
    std::vector<uint32_t> mPersistentMeshIndices;
    std::vector<uint32_t> mWireframeIndices;
    bool mInitialized = false;

    cv::Mat mMuralTexture;
    GLuint mTextureId = 0;
    bool mTextureDirty = false;
    bool mMeshDirty = false;
    bool mIndicesUploaded = false;
    int mNextTexturePatchIndex = 0;

    GLuint mProgram = 0;
    GLuint mVbo = 0;
    GLuint mIbo = 0;
    GLuint mWireIbo = 0;

    static constexpr int MESH_GRID_DIM = 32;
    static constexpr int TEXTURE_SIZE = 1024;
};
