#ifndef GRAFFITIXR_VULKANBACKEND_H
#define GRAFFITIXR_VULKANBACKEND_H

#include <android/native_window.h>
#include <android/asset_manager.h>
#include <vulkan/vulkan.h>
#include <vector>
#include <string>

class VulkanBackend {
public:
    VulkanBackend();
    ~VulkanBackend();

    // Lifecycle
    bool initialize();
    bool initSurface(void* nativeWindow);
    void resize(int width, int height);
    void destroySurface();
    void cleanup();

    // Compute Support
    bool createComputePipeline(const uint32_t* code, size_t size);

    // Placeholder for future rendering integration
    void renderFrame();
    void updateCamera(float* viewMatrix, float* projectionMatrix);

private:
    bool createInstance();
    bool pickPhysicalDevice();
    bool createLogicalDevice();

    // Vulkan Core
    VkInstance mInstance = VK_NULL_HANDLE;
    VkSurfaceKHR mSurface = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;
    VkQueue mGraphicsQueue = VK_NULL_HANDLE;

    // Dimensions
    int mWidth = 0;
    int mHeight = 0;
};

#endif // GRAFFITIXR_VULKANBACKEND_H
