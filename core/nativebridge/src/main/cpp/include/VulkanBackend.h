// ~~~ FILE: ./core/nativebridge/src/main/cpp/include/VulkanBackend.h ~~~
#ifndef GRAFFITIXR_VULKAN_BACKEND_H
#define GRAFFITIXR_VULKAN_BACKEND_H

#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <android/asset_manager.h>
#include <vector>
#include <string>

struct SplatGaussian; // Forward declaration

/**
 * Handles the Vulkan Graphics Pipeline for high-performance rendering.
 */
class VulkanBackend {
public:
    VulkanBackend();
    ~VulkanBackend();

    bool initialize(ANativeWindow* window, AAssetManager* assetManager);
    void destroy();

    // Ingests sorted splats and dispatches them to the GPU
    void renderFrame(const std::vector<SplatGaussian>& splats);

    void resize(int width, int height);
    void updateCamera(const float* viewMatrix, const float* projectionMatrix);
    void setLighting(float intensity, const float* colorCorrection);
    void setVisualizationMode(int mode);

    // Upload RGBA bitmap as overlay; subsequent frames will alpha-blend it over the splats.
    void setOverlayTexture(int width, int height, const uint8_t* rgba);

private:
    ANativeWindow* m_window = nullptr;
    AAssetManager* m_assetManager = nullptr;

    VkInstance m_instance = VK_NULL_HANDLE;
    VkPhysicalDevice m_physicalDevice = VK_NULL_HANDLE;
    VkDevice m_device = VK_NULL_HANDLE;
    VkQueue m_graphicsQueue = VK_NULL_HANDLE;
    uint32_t m_graphicsQueueFamilyIndex = 0;

    VkSurfaceKHR m_surface = VK_NULL_HANDLE;
    VkSwapchainKHR m_swapchain = VK_NULL_HANDLE;
    VkFormat m_swapchainFormat = VK_FORMAT_UNDEFINED;
    VkExtent2D m_swapchainExtent = {0, 0};

    std::vector<VkImage> m_swapchainImages;
    std::vector<VkImageView> m_swapchainImageViews;
    std::vector<VkFramebuffer> m_swapchainFramebuffers;

    VkRenderPass m_renderPass = VK_NULL_HANDLE;
    VkPipelineLayout m_pipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_graphicsPipeline = VK_NULL_HANDLE;

    VkCommandPool m_commandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> m_commandBuffers;

    int m_visualizationMode = 0; // 0=RGB, 1=Heatmap
    int m_overlayEnabled = 0;

    // Descriptor infrastructure
    VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool m_descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet m_descriptorSet = VK_NULL_HANDLE;

    // Overlay texture (default: 1x1 white dummy, replaced on setOverlayTexture)
    VkImage m_overlayImage = VK_NULL_HANDLE;
    VkDeviceMemory m_overlayImageMemory = VK_NULL_HANDLE;
    VkImageView m_overlayImageView = VK_NULL_HANDLE;
    VkSampler m_overlaySampler = VK_NULL_HANDLE;

    // Synchronization
    std::vector<VkSemaphore> m_imageAvailableSemaphores;
    std::vector<VkSemaphore> m_renderFinishedSemaphores;
    std::vector<VkFence> m_inFlightFences;
    int m_currentFrame = 0;

    // Geometry Buffers (Splat Data)
    VkBuffer m_vertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_vertexBufferMemory = VK_NULL_HANDLE;
    size_t m_vertexBufferSize = 0;

    // Uniform Buffers (Camera/Light)
    struct UniformBufferObject {
        float view[16];
        float proj[16];
        float lightIntensity;
        float lightColor[3];
    } m_ubo;

    VkBuffer m_uniformBuffer = VK_NULL_HANDLE;
    VkDeviceMemory m_uniformBufferMemory = VK_NULL_HANDLE;

    // Initialization Steps
    bool createInstance();
    bool pickPhysicalDevice();
    bool createLogicalDevice();
    bool createSurface();
    bool createSwapchain();
    bool createImageViews();
    bool createRenderPass();
    bool createGraphicsPipeline();
    bool createFramebuffers();
    bool createCommandPool();
    bool createCommandBuffers();
    bool createSyncObjects();

    // Helpers
    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties, VkBuffer& buffer, VkDeviceMemory& bufferMemory);
    void uploadSplatData(const std::vector<SplatGaussian>& splats);
    void updateUniformBuffer();
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);
    VkShaderModule createShaderModule(const std::vector<char>& code);
    std::vector<char> readFile(const std::string& filename);
    void cleanupSwapchain();
    void recreateSwapchain();

    // Texture / descriptor helpers
    bool createDescriptorSetLayout();
    bool createDescriptorPool();
    bool createSampler();
    bool createTextureImage(int width, int height, const uint8_t* rgba,
                            VkImage& image, VkDeviceMemory& memory);
    bool createTextureImageView(VkImage image, VkImageView& view);
    void destroyOverlayResources();
    bool createDescriptorSet();
    VkCommandBuffer beginSingleTimeCommands();
    void endSingleTimeCommands(VkCommandBuffer cmd);
    void transitionImageLayout(VkImage image, VkImageLayout oldLayout, VkImageLayout newLayout);
    void copyBufferToImage(VkBuffer buffer, VkImage image, uint32_t width, uint32_t height);
};

#endif // GRAFFITIXR_VULKAN_BACKEND_H