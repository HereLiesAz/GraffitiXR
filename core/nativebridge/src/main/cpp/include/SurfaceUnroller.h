#pragma once
#include <vector>
#include <glm/glm.hpp>

class SurfaceUnroller {
public:
    SurfaceUnroller(int dim = 32);
    std::vector<glm::vec2> unroll(const std::vector<float>& vertices, int iterations = 150);

private:
    int mDim;
    int mCount;
    float mPerplexity = 30.0f;

    std::vector<std::vector<float>> computeP(const std::vector<float>& v);
    std::vector<glm::vec2> computeKLmaxGradients(const std::vector<std::vector<float>>& P, const std::vector<glm::vec2>& Y);
    float dist3dSquared(const std::vector<float>& v, int i, int j);
    float dist2dSquared(const glm::vec2& y1, const glm::vec2& y2);
};
