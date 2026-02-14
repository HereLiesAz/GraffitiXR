#ifndef VULKAN_BACKEND_H
#define VULKAN_BACKEND_H

#include <vulkan/vulkan.h>
#include <vector>
#include <string>

class VulkanBackend {
public:
    VulkanBackend();
    ~VulkanBackend();

    bool initialize();
    bool initSurface(void* nativeWindow);
    void resize(int width, int height);
    bool createComputePipeline(const uint32_t* code, size_t size); // NEW: For splat rasterization
    void cleanup();

private:
    VkInstance mInstance = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkSurfaceKHR mSurface = VK_NULL_HANDLE;

    bool createInstance();
    bool pickPhysicalDevice();
    bool createLogicalDevice();
};

#endif // VULKAN_BACKEND_H
