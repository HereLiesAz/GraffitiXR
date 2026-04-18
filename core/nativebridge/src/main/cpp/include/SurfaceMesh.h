#pragma once
#include <opencv2/opencv.hpp>
#include <vector>
#include <mutex>
#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <glm/gtc/type_ptr.hpp>

struct MeshVertex {
    float x, y, z;          // Position (Anchor-Local Space)
    float confidence;       // Persistence/Weight
};

class SurfaceMesh {
public:
    SurfaceMesh();
    ~SurfaceMesh();

    void initGl();
    void update(const cv::Mat& depth, const float* viewMat, const float* projMat, const float* anchorMatrix);
    void draw(const glm::mat4& mvp);
    void clear();
    void getMesh(std::vector<float>& outVertices, std::vector<float>& outWeights);

private:
    std::mutex mMutex;
    std::vector<MeshVertex> mPersistentMesh;
    std::vector<uint32_t> mPersistentMeshIndices;
    bool mInitialized = false;

    GLuint mProgram = 0;
    GLuint mVbo = 0;
    GLuint mIbo = 0;

    static constexpr int MESH_GRID_DIM = 32;
};
