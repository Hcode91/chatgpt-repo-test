#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <ps3emu/milestone.h>
#include <stdint.h>
#include <cstdio>
#include <string>

static std::string gpu_probe() {
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "infamous-port-lab";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult r = vkCreateInstance(&ci, nullptr, &instance);
    if (r != VK_SUCCESS) return "Vulkan instance: FAILED (" + std::to_string(r) + ")";
    uint32_t count = 0;
    r = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    std::string result = "Vulkan devices: " + std::to_string(count);
    if (r == VK_SUCCESS && count > 0) {
        VkPhysicalDevice device = VK_NULL_HANDLE;
        uint32_t one = 1;
        if (vkEnumeratePhysicalDevices(instance, &one, &device) == VK_SUCCESS) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(device, &props);
            result += "\nGPU: ";
            result += props.deviceName;
        }
    }
    vkDestroyInstance(instance, nullptr);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hcode91_infamousprobe_MainActivity_nativeProbe(JNIEnv* env, jclass) {
    ps3_ms("android:runtime-probe");
    long page = sysconf(_SC_PAGESIZE);
    const size_t size = 0x100000000ULL; // Reserve 4 GiB virtual address space, not RAM
    void* mapping = mmap(nullptr, size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    bool reserved = mapping != MAP_FAILED;
    bool writable = false;
    if (reserved) {
        if (mprotect(mapping, static_cast<size_t>(page), PROT_READ | PROT_WRITE) == 0) {
            volatile uint8_t* p = static_cast<volatile uint8_t*>(mapping);
            p[0] = 0x5a;
            writable = (p[0] == 0x5a);
        }
        munmap(mapping, size);
    }
    std::string result = "Architecture: arm64-v8a\n";
    result += "Page size: " + std::to_string(page) + " bytes\n";
    result += "4 GiB guest VA reservation: " + std::string(reserved ? "PASS" : "FAIL") + "\n";
    result += "Page commit/read/write: " + std::string(writable ? "PASS" : "FAIL") + "\n";
    result += "ps3recomp runtime milestone module: LINKED\n";
    result += gpu_probe();
    ps3_ms("android:runtime-probe-complete");
    return env->NewStringUTF(result.c_str());
}
