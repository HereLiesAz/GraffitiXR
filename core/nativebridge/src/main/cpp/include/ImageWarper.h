#pragma once

#include <GLES3/gl3.h>
#include <vector>
#include <glm/glm.hpp>
#include <mutex>

class ImageWarper {
public:
    ImageWarper();
    ~ImageWarper();

    void init();
    void setSourceImage(const uint8_t* data, int width, int height);
    void applyLiquify(const std::vector<glm::vec2>& stroke, float brushSize, float intensity);
    void draw(int viewportWidth, int viewportHeight);
    void bakeToBitmap(uint8_t* outData);
    void reset();

private:
    void updateMesh();
    void initShaders();

    int mWidth = 0;
    int mHeight = 0;
    int mGridDim = 64;

    GLuint mProgram = 0;
    GLuint mTextureId = 0;
    GLuint mVboPos = 0;
    GLuint mVboTex = 0;
    GLuint mIbo = 0;
    GLuint mFbo = 0;
    GLuint mOffscreenTexture = 0;

    std::vector<glm::vec2> mVertices;
    std::vector<glm::vec2> mTexCoords;
    std::vector<uint32_t> mIndices;

    std::mutex mMutex;
    bool mInitialized = false;
    bool mMeshDirty = true;
};
