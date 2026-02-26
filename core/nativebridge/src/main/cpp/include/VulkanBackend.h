#ifndef GRAFFITIXR_VULKAN_BACKEND_H
#define GRAFFITIXR_VULKAN_BACKEND_H

#include <android/native_window.h>
#include <android/asset_manager.h>
#include <vulkan/vulkan.h>

class VulkanBackend {
public:
    VulkanBackend();
    ~VulkanBackend();

    bool initialize(ANativeWindow* window, AAssetManager* assetManager);
    void destroy();
    void renderFrame();
    void resize(int width, int height);
    void updateCamera(const float* viewMatrix, const float* projectionMatrix);
    void setLighting(float intensity, const float* colorCorrection);

private:
    ANativeWindow* m_window = nullptr;
};

#endif // GRAFFITIXR_VULKAN_BACKEND_H