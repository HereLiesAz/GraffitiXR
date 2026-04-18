#include "include/SurfaceUnroller.h"
#include <cmath>
#include <algorithm>
#include <numeric>

SurfaceUnroller::SurfaceUnroller(int dim) : mDim(dim), mCount(dim * dim) {}

std::vector<glm::vec2> SurfaceUnroller::unroll(const std::vector<float>& vertices, int iterations) {
    if ((int)vertices.size() < mCount * 3) return {};

    auto P = computeP(vertices);

    std::vector<glm::vec2> Y(mCount);
    for (int i = 0; i < mCount; ++i) {
        Y[i] = glm::vec2((float)(i % mDim) / (mDim - 1), (float)(i / mDim) / (mDim - 1));
    }

    std::vector<glm::vec2> velocity(mCount, glm::vec2(0.0f));
    std::vector<glm::vec2> gains(mCount, glm::vec2(1.0f));
    float momentum = 0.5f;

    for (int iter = 0; iter < iterations; ++iter) {
        if (iter == 50) momentum = 0.8f;

        auto grads = computeKLmaxGradients(P, Y);

        for (int i = 0; i < mCount; ++i) {
            for (int d = 0; d < 2; ++d) {
                float g = grads[i][d];
                float v = velocity[i][d];
                gains[i][d] = ((g > 0) != (v > 0)) ? (gains[i][d] + 0.2f) : (gains[i][d] * 0.8f);
                if (gains[i][d] < 0.01f) gains[i][d] = 0.01f;
                if (gains[i][d] > 100.0f) gains[i][d] = 100.0f;

                float learningRate = 200.0f;
                velocity[i][d] = momentum * v - learningRate * gains[i][d] * g;
                Y[i][d] += velocity[i][d];
            }
        }

        // Center
        glm::vec2 mean(0.0f);
        for (const auto& y : Y) mean += y;
        mean /= (float)mCount;
        for (auto& y : Y) y -= mean;
    }

    // Normalize to [0, 1]
    float minX = 1e10f, minY = 1e10f, maxX = -1e10f, maxY = -1e10f;
    for (const auto& y : Y) {
        minX = std::min(minX, y.x); maxX = std::max(maxX, y.x);
        minY = std::min(minY, y.y); maxY = std::max(maxY, y.y);
    }
    float rangeX = maxX - minX, rangeY = maxY - minY;
    for (auto& y : Y) {
        y.x = (y.x - minX) / (rangeX > 1e-6f ? rangeX : 1.0f);
        y.y = (y.y - minY) / (rangeY > 1e-6f ? rangeY : 1.0f);
    }

    return Y;
}

std::vector<std::vector<float>> SurfaceUnroller::computeP(const std::vector<float>& v) {
    std::vector<std::vector<float>> P(mCount, std::vector<float>(mCount, 0.0f));
    float targetEntropy = std::log2(mPerplexity);

    for (int i = 0; i < mCount; ++i) {
        float minSigma = 1e-10f, maxSigma = 1e10f, sigma = 1.0f;
        for (int b = 0; b < 20; ++b) {
            float sumP = 0.0f;
            for (int j = 0; j < mCount; ++j) {
                if (i == j) continue;
                float d2 = dist3dSquared(v, i, j);
                P[i][j] = std::exp(-d2 / (2.0f * sigma * sigma));
                sumP += P[i][j];
            }
            float entropy = 0.0f;
            if (sumP > 1e-9f) {
                for (int j = 0; j < mCount; ++j) {
                    if (i == j) continue;
                    P[i][j] /= sumP;
                    if (P[i][j] > 1e-10f) entropy -= P[i][j] * std::log2(P[i][j]);
                }
            }
            if (entropy > targetEntropy) { maxSigma = sigma; sigma = (minSigma + sigma) / 2.0f; }
            else { minSigma = sigma; if (maxSigma > 1e9f) sigma *= 2.0f; else sigma = (maxSigma + sigma) / 2.0f; }
        }
    }

    std::vector<std::vector<float>> symP(mCount, std::vector<float>(mCount, 0.0f));
    for (int i = 0; i < mCount; ++i) {
        for (int j = 0; j < mCount; ++j) {
            symP[i][j] = (P[i][j] + P[j][i]) / (2.0f * mCount);
        }
    }
    return symP;
}

std::vector<glm::vec2> SurfaceUnroller::computeKLmaxGradients(const std::vector<std::vector<float>>& P, const std::vector<glm::vec2>& Y) {
    std::vector<float> Q(mCount * mCount, 0.0f);
    float sumQ = 0.0f;
    for (int i = 0; i < mCount; ++i) {
        for (int j = 0; j < mCount; ++j) {
            if (i == j) continue;
            float d2 = dist2dSquared(Y[i], Y[j]);
            float qVal = 1.0f / (1.0f + d2);
            Q[i * mCount + j] = qVal;
            sumQ += qVal;
        }
    }

    std::vector<glm::vec2> grads(mCount, glm::vec2(0.0f));
    for (int i = 0; i < mCount; ++i) {
        for (int j = 0; j < mCount; ++j) {
            if (i == j) continue;
            float q_ij = Q[i * mCount + j] / (sumQ + 1e-9f);
            float p_ij = P[i][j];
            float q_kernel = Q[i * mCount + j];

            float mult = (p_ij > q_ij) ? (4.0f * (p_ij - q_ij) * q_kernel) : (0.5f * (p_ij - q_ij) * q_kernel);
            grads[i] += mult * (Y[i] - Y[j]);
        }
    }
    return grads;
}

float SurfaceUnroller::dist3dSquared(const std::vector<float>& v, int i1, int i2) {
    float dx = v[i1*3]-v[i2*3], dy = v[i1*3+1]-v[i2*3+1], dz = v[i1*3+2]-v[i2*3+2];
    return dx*dx + dy*dy + dz*dz;
}

float SurfaceUnroller::dist2dSquared(const glm::vec2& y1, const glm::vec2& y2) {
    glm::vec2 d = y1 - y2;
    return glm::dot(d, d);
}
