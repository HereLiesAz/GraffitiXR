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
    bool initialize(ANativeWindow* window, AAssetManager* assetManager);
    void resize(int width, int height);
    void destroy();

    // Rendering
    void renderFrame();

    // AR Integration
    void updateCamera(float* viewMatrix, float* projectionMatrix);
    void updateModel(float* modelMatrix);

private:
    bool createInstance();
    bool createSurface();
    bool selectPhysicalDevice();
    bool createLogicalDevice();
    bool createSwapchain();
    bool createRenderPass();
    bool createPipeline();
    bool createFramebuffers();
    bool createCommandPool();
    bool createCommandBuffers();
    bool createSyncObjects();

    void cleanupSwapchain();
    void recreateSwapchain();

    // Android
    ANativeWindow* window = nullptr;
    AAssetManager* assetManager = nullptr;

    // Vulkan Core
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    VkQueue presentQueue = VK_NULL_HANDLE;

    // Swapchain
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat swapchainImageFormat;
    VkExtent2D swapchainExtent;
    std::vector<VkImage> swapchainImages;
    std::vector<VkImageView> swapchainImageViews;
    std::vector<VkFramebuffer> swapchainFramebuffers;

    // Pipeline
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline graphicsPipeline = VK_NULL_HANDLE;

    // Commands & Sync
    VkCommandPool commandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers;
    std::vector<VkSemaphore> imageAvailableSemaphores;
    std::vector<VkSemaphore> renderFinishedSemaphores;
    std::vector<VkFence> inFlightFences;

    uint32_t currentFrame = 0;
    bool framebufferResized = false;
    int width = 0;
    int height = 0;
};

#endif // GRAFFITIXR_VULKANBACKEND_H