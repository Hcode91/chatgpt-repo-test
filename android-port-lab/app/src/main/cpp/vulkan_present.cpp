// Vulkan Android SurfaceView real display/presentation proof.
// Renders GPU-cleared moving color frames, not emulated RSX geometry.
// Lifecycle avoids stale surface usage: Java requests cancellation before destroy.
#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <jni.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <atomic>
#include <vector>
#include <string>
#include <sstream>
#include <thread>
#include <chrono>
#include <algorithm>
#include <cmath>

namespace {
std::atomic<bool> cancel_requested{false};
std::string err(const char* step, VkResult code) {
    return std::string("Vulkan ") + step + " failed: " + std::to_string(static_cast<int>(code));
}
const char* test_name = "Vulkan frame presentation (NOT PS3 RSX)";
}
extern "C" JNIEXPORT void JNICALL
Java_com_hcode91_infamousprobe_VulkanActivity_cancelVulkan(JNIEnv*, jclass) {
    cancel_requested.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hcode91_infamousprobe_VulkanActivity_renderVulkan(JNIEnv* env, jclass, jobject jsurface, jint maxFrames) {
    cancel_requested.store(false);
    ANativeWindow* window = ANativeWindow_fromSurface(env, jsurface);
    if (!window) return env->NewStringUTF("Android native window unavailable");
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkSemaphore acquireSemaphore = VK_NULL_HANDLE;
    VkSemaphore presentSemaphore = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    std::vector<VkImage> images;
    int frameCount = 0;
    std::string result;

#define TRY_VK(step, expression) do { VkResult _r = (expression); if (_r != VK_SUCCESS) { result = err(step, _r); goto cleanup; } } while(0)

    {
        const char* ext[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME };
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "inFAMOUS Android Port Lab GPU";
        app.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ci.pApplicationInfo = &app;
        ci.enabledExtensionCount = 2;
        ci.ppEnabledExtensionNames = ext;
        TRY_VK("instance", vkCreateInstance(&ci, nullptr, &instance));
    }
    {
        VkAndroidSurfaceCreateInfoKHR si{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        si.window = window;
        TRY_VK("android surface", vkCreateAndroidSurfaceKHR(instance, &si, nullptr, &surface));
    }
    {
        uint32_t count = 0;
        TRY_VK("enumerate GPUs", vkEnumeratePhysicalDevices(instance, &count, nullptr));
        if (count == 0) { result = "No Vulkan physical devices"; goto cleanup; }
        std::vector<VkPhysicalDevice> phys(count);
        TRY_VK("GPU list", vkEnumeratePhysicalDevices(instance, &count, phys.data()));
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        uint32_t family = UINT32_MAX;
        for (auto p : phys) {
            uint32_t n = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(p, &n, nullptr);
            std::vector<VkQueueFamilyProperties> props(n);
            vkGetPhysicalDeviceQueueFamilyProperties(p, &n, props.data());
            for (uint32_t i = 0; i < n; ++i) {
                VkBool32 supported = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(p, i, surface, &supported);
                if ((props[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && supported) {
                    physical = p; family = i; break;
                }
            }
            if (physical) break;
        }
        if (!physical) { result = "No graphics+present Vulkan queue"; goto cleanup; }
        float priority = 1.0f;
        VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qi.queueFamilyIndex = family;
        qi.queueCount = 1;
        qi.pQueuePriorities = &priority;
        const char* deviceExtension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
        VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        di.queueCreateInfoCount = 1;
        di.pQueueCreateInfos = &qi;
        di.enabledExtensionCount = 1;
        di.ppEnabledExtensionNames = &deviceExtension;
        TRY_VK("device", vkCreateDevice(physical, &di, nullptr, &device));
        VkQueue queue = VK_NULL_HANDLE;
        vkGetDeviceQueue(device, family, 0, &queue);

        VkSurfaceCapabilitiesKHR capabilities{};
        TRY_VK("surface capabilities", vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &capabilities));
        if (!(capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) {
            result = "Swapchain images do not support TRANSFER_DST; cannot clear via Vulkan"; goto cleanup;
        }
        uint32_t formatCount = 0;
        TRY_VK("surface format count", vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &formatCount, nullptr));
        if (!formatCount) { result = "No surface formats"; goto cleanup; }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        TRY_VK("surface formats", vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &formatCount, formats.data()));
        VkSurfaceFormatKHR chosen = formats[0];
        for (auto f : formats) {
            if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) {
                chosen = f; break;
            }
        }
        VkExtent2D extent = capabilities.currentExtent;
        if (extent.width == UINT32_MAX) {
            extent.width = std::max(capabilities.minImageExtent.width,
                         std::min(capabilities.maxImageExtent.width, static_cast<uint32_t>(ANativeWindow_getWidth(window))));
            extent.height = std::max(capabilities.minImageExtent.height,
                         std::min(capabilities.maxImageExtent.height, static_cast<uint32_t>(ANativeWindow_getHeight(window))));
        }
        if (extent.width == 0 || extent.height == 0) { result = "Window has zero extent"; goto cleanup; }
        uint32_t imageCount = std::max(capabilities.minImageCount, 2u);
        if (capabilities.maxImageCount && imageCount > capabilities.maxImageCount)
            imageCount = capabilities.maxImageCount;
        VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        sci.surface = surface; sci.minImageCount = imageCount;
        sci.imageFormat = chosen.format; sci.imageColorSpace = chosen.colorSpace;
        sci.imageExtent = extent; sci.imageArrayLayers = 1;
        sci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        sci.preTransform = capabilities.currentTransform;
        sci.compositeAlpha = (capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                              ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        sci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        sci.clipped = VK_TRUE;
        TRY_VK("swapchain", vkCreateSwapchainKHR(device, &sci, nullptr, &swapchain));
        uint32_t actualImages = 0;
        TRY_VK("swapchain image count", vkGetSwapchainImagesKHR(device, swapchain, &actualImages, nullptr));
        images.resize(actualImages);
        TRY_VK("swapchain images", vkGetSwapchainImagesKHR(device, swapchain, &actualImages, images.data()));

        VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pci.queueFamilyIndex = family; pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        TRY_VK("command pool", vkCreateCommandPool(device, &pci, nullptr, &pool));
        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ai.commandPool = pool; ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = 1;
        TRY_VK("command buffer", vkAllocateCommandBuffers(device, &ai, &cmd));
        VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        TRY_VK("acquire semaphore", vkCreateSemaphore(device, &sem, nullptr, &acquireSemaphore));
        TRY_VK("present semaphore", vkCreateSemaphore(device, &sem, nullptr, &presentSemaphore));
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        TRY_VK("fence", vkCreateFence(device, &fci, nullptr, &fence));

        for (int frame = 0; frame < std::max(1, std::min(600, static_cast<int>(maxFrames))); ++frame) {
            if (cancel_requested.load()) { result = "Vulkan test cancelled after " + std::to_string(frameCount) + " frames"; break; }
            uint32_t imageIndex = 0;
            VkResult a = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, acquireSemaphore, VK_NULL_HANDLE, &imageIndex);
            if (a == VK_ERROR_OUT_OF_DATE_KHR) { result = "Surface resized; rerun the preview"; break; }
            if (a != VK_SUCCESS && a != VK_SUBOPTIMAL_KHR) { result = err("acquire", a); break; }
            TRY_VK("reset command buffer", vkResetCommandBuffer(cmd, 0));
            VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            TRY_VK("begin commands", vkBeginCommandBuffer(cmd, &bi));
            VkImageMemoryBarrier before{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            before.oldLayout = frame < static_cast<int>(actualImages) ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            before.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            // Initial contents are discarded on each frame; UNDEFINED is valid for clears.
            before.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            before.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            before.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            before.image = images[imageIndex];
            before.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            before.subresourceRange.levelCount = 1;
            before.subresourceRange.layerCount = 1;
            before.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &before);
            float t = static_cast<float>(frame) / 45.0f;
            VkClearColorValue color{};
            color.float32[0] = 0.12f + 0.32f * (1.0f + std::sin(t)) * 0.5f;
            color.float32[1] = 0.06f + 0.16f * (1.0f + std::sin(t * 1.3f + 1.5f)) * 0.5f;
            color.float32[2] = 0.24f + 0.48f * (1.0f + std::sin(t * 0.8f + 2.0f)) * 0.5f;
            color.float32[3] = 1.0f;
            VkImageSubresourceRange range{};
            range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            range.levelCount = 1; range.layerCount = 1;
            vkCmdClearColorImage(cmd, images[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1, &range);
            VkImageMemoryBarrier after{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            after.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            after.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            after.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            after.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            after.image = images[imageIndex]; after.subresourceRange = range;
            after.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &after);
            TRY_VK("end commands", vkEndCommandBuffer(cmd));
            VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.waitSemaphoreCount = 1; submit.pWaitSemaphores = &acquireSemaphore;
            submit.pWaitDstStageMask = &waitStage;
            submit.commandBufferCount = 1; submit.pCommandBuffers = &cmd;
            submit.signalSemaphoreCount = 1; submit.pSignalSemaphores = &presentSemaphore;
            TRY_VK("queue submit", vkQueueSubmit(queue, 1, &submit, fence));
            VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
            pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = &presentSemaphore;
            pi.swapchainCount = 1; pi.pSwapchains = &swapchain; pi.pImageIndices = &imageIndex;
            VkResult present = vkQueuePresentKHR(queue, &pi);
            if (present != VK_SUCCESS && present != VK_SUBOPTIMAL_KHR) { result = err("present", present); break; }
            TRY_VK("queue idle", vkQueueWaitIdle(queue));
            TRY_VK("queue fence", vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX));
            TRY_VK("reset fence", vkResetFences(device, 1, &fence));
            ++frameCount;
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
        }
        if (result.empty()) {
            VkPhysicalDeviceProperties gpu{};
            vkGetPhysicalDeviceProperties(physical, &gpu);
            std::ostringstream report;
            report << test_name << "\nGPU: " << gpu.deviceName
                   << "\nResolution: " << extent.width << "x" << extent.height
                   << "\nVulkan swapchain frames presented: " << frameCount
                   << "\nPASS if you saw smoothly changing colours. No 3D/RSX yet.";
            result = report.str();
        }
    }
cleanup:
    if (device) vkDeviceWaitIdle(device);
    if (fence) vkDestroyFence(device, fence, nullptr);
    if (presentSemaphore) vkDestroySemaphore(device, presentSemaphore, nullptr);
    if (acquireSemaphore) vkDestroySemaphore(device, acquireSemaphore, nullptr);
    if (pool) vkDestroyCommandPool(device, pool, nullptr);
    if (swapchain) vkDestroySwapchainKHR(device, swapchain, nullptr);
    if (device) vkDestroyDevice(device, nullptr);
    if (surface) vkDestroySurfaceKHR(instance, surface, nullptr);
    if (instance) vkDestroyInstance(instance, nullptr);
    ANativeWindow_release(window);
    return env->NewStringUTF(result.c_str());
}
