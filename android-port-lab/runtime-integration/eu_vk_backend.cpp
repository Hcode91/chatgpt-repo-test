/*
 * Vulkan backend for ps3recomp's register-file RSX draw engine (Android).
 *
 * rsx_draw_engine.c decodes the guest's NV4097 command stream, its shaders
 * (as HLSL, from the toolkit's two decompilers) and its textures, and drives a
 * host API through the small rsx_draw_backend interface. Upstream implements
 * that interface for Direct3D 12 and Metal; this file implements it for
 * Vulkan 1.3 so the game renders on the phone's GPU.
 *
 * Shape (mirrors rsx_metal_backend.m's engine half):
 *   - Draws, clears and depth resolves are RECORDED with their constants and
 *     geometry copied into one staging arena; at submit_and_wait / present the
 *     records are encoded into one command buffer, submitted, and waited on
 *     (the engine recycles staging the moment submit_and_wait returns).
 *   - Shaders: HLSL -> SPIR-V through glslang (the same front end the Metal
 *     path uses), with per-stage binding shifts so every resource lands in one
 *     descriptor-set namespace; the set layout is built from glslang's own
 *     reflection, so arrays of textures and individual units both work.
 *   - No window in the runner process: the presented surface is blitted to an
 *     RGBA8 image, read back, and published to the Android app through the
 *     shared-memory bridge (eu_android_bridge.c).
 *   - Images live in VK_IMAGE_LAYOUT_GENERAL; passes are separated by a full
 *     memory barrier. Simple and correct first; faster layouts can follow.
 *   - BC1/2/3 textures are decoded on the CPU when the GPU lacks BC support
 *     (typical for Adreno on Android).
 *
 * No game code or data here. Selected by EU_RSX (default "vulkan"); any
 * initialisation failure falls back to upstream's CPU software preview.
 */
#define VK_NO_PROTOTYPES 1
#include <vulkan/vulkan.h>
#include <dlfcn.h>

#include <glslang/Public/ShaderLang.h>
#include <glslang/Public/ResourceLimits.h>
#include <glslang/Include/Types.h>
#if __has_include(<glslang/SPIRV/GlslangToSpv.h>)
#include <glslang/SPIRV/GlslangToSpv.h>
#else
#include <SPIRV/GlslangToSpv.h>
#endif

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <zlib.h>
#include <string>
#include <vector>
#include <deque>

/* These headers carry their own extern "C" guards (and C++ templates). */
#include "rsx_draw_engine.h"
#include "rsx_texture_layout.h"

extern "C" unsigned g_eu_eng_drop[16] __attribute__((weak));
/* rsx_commands.c's vtable (rsx_commands.h, reached through the engine header). With
 * the engine drawing, the rsx_state path still runs on every FIFO word; give it
 * an all-NULL backend rather than none, so no path dereferences a NULL backend
 * (build 15 crashed in rsx_process_method reading offset 0x78 = draw_arrays). */
/* Every callback is a real do-nothing function, not NULL: build 19 crashed
 * jumping to address 0 from rsx_process_method (an immediate-mode quad draw
 * calling a callback it had not checked). */
namespace noop {
int  init(void*, u32, u32) { return 0; }
void v(void*) {}
void present(void*, u32) {}
void st(void*, const rsx_state*) {}
void clear(void*, u32, u32, float, u8) {}
void draw(void*, u32, u32, u32) {}
void tex(void*, u32, const rsx_texture_state*) {}
}
static rsx_backend g_noop_vtable = {
    nullptr,          /* userdata */
    noop::init, noop::v, noop::v, noop::v, noop::present,
    noop::st, noop::st, noop::st, noop::st, noop::st, noop::st, noop::st, noop::st,
    noop::clear, noop::draw, noop::draw, noop::tex,
};
extern "C" void eu_bridge_frame_rgba(const uint8_t* rgba, unsigned w, unsigned h,
                                     unsigned pitch) __attribute__((weak));
extern "C" unsigned eu_bridge_capture_pending(void) __attribute__((weak));
extern "C" void eu_bridge_capture_done(unsigned n) __attribute__((weak));

namespace {

/* ------------------------------------------------------------------ loader
 * The NDK's libvulkan stub for API 26 exports only Vulkan 1.0, so every entry
 * point (1.3's dynamic rendering included) is resolved at run time. */
#define EU_VK_GLOBAL(X) X(vkCreateInstance) X(vkEnumerateInstanceVersion)
#define EU_VK_FUNCS(X) \
    X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties) X(vkGetPhysicalDeviceMemoryProperties) \
    X(vkGetPhysicalDeviceFeatures2) X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkCreateDevice) \
    X(vkGetPhysicalDeviceFormatProperties) X(vkGetDeviceQueue) X(vkCreateCommandPool) \
    X(vkAllocateCommandBuffers) X(vkCreateFence) X(vkCreateDescriptorPool) X(vkCreateBuffer) \
    X(vkGetBufferMemoryRequirements) X(vkAllocateMemory) X(vkBindBufferMemory) X(vkMapMemory) \
    X(vkFreeMemory) X(vkDestroyBuffer) X(vkCreateImage) X(vkGetImageMemoryRequirements) \
    X(vkBindImageMemory) X(vkDestroyImage) X(vkCreateImageView) X(vkDestroyImageView) \
    X(vkCmdPipelineBarrier) X(vkResetCommandBuffer) X(vkBeginCommandBuffer) X(vkEndCommandBuffer) \
    X(vkResetFences) X(vkQueueSubmit) X(vkWaitForFences) X(vkGetFenceStatus) X(vkCmdCopyBufferToImage) \
    X(vkCmdCopyImageToBuffer) X(vkCreateShaderModule) X(vkCreateSampler) X(vkCreateGraphicsPipelines) \
    X(vkDestroyPipeline) X(vkCreateDescriptorSetLayout) X(vkDestroyDescriptorSetLayout) \
    X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) X(vkCmdBindPipeline) \
    X(vkCmdSetPrimitiveTopology) X(vkCmdSetViewport) X(vkCmdSetScissor) X(vkCmdSetStencilReference) \
    X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) X(vkCmdBindDescriptorSets) \
    X(vkCmdBindVertexBuffers) X(vkCmdBindIndexBuffer) X(vkCmdDrawIndexed) X(vkCmdDraw) \
    X(vkCmdBeginRendering) X(vkCmdEndRendering) X(vkResetDescriptorPool) X(vkCmdBlitImage)
#define EU_VK_DECL(f) PFN_##f f = nullptr;
EU_VK_GLOBAL(EU_VK_DECL)
EU_VK_FUNCS(EU_VK_DECL)
PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = nullptr;

bool load_global()
{
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        fprintf(stderr, "[RSX vulkan] cannot load libvulkan.so: %s\n", dlerror());
        return false;
    }
    vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!vkGetInstanceProcAddr) return false;
#define EU_VK_LOAD_G(f) f = (PFN_##f)vkGetInstanceProcAddr(nullptr, #f);
    EU_VK_GLOBAL(EU_VK_LOAD_G)
    return vkCreateInstance != nullptr;
}

bool load_instance(VkInstance inst)
{
    bool ok = true;
#define EU_VK_LOAD_I(f) f = (PFN_##f)vkGetInstanceProcAddr(inst, #f); \
    if (!f) { fprintf(stderr, "[RSX vulkan] missing entry point %s\n", #f); ok = false; }
    EU_VK_FUNCS(EU_VK_LOAD_I)
    return ok;
}

/* ------------------------------------------------------------------ limits */
constexpr u32 MAX_OBJECTS = 4096;
constexpr u32 MAX_PIPES = 4096;
constexpr u32 MAX_RECORDS = 8192;
constexpr u32 MAX_VIEWS = 256;
constexpr u32 MAX_SAMPLERS = 256;
constexpr u32 STAGE_ALIGN = 256;
constexpr u32 STAGE_MAX = 256u << 20;
constexpr u32 UBO_PAD = 64u << 10;   /* tail so a padded UBO range stays in bounds */
constexpr u32 PRESENT_MAX_W = 1280, PRESENT_MAX_H = 720;

/* Descriptor binding namespace (set 0). The decompilers' HLSL registers:
 *   VS: cbuffer b0, vertex textures t16..t19, samplers s0..s3
 *   PS: cbuffer b1, textures t0..t15, samplers s0..s15                     */
constexpr int B_VS_UBO = 0, B_PS_UBO = 1;
constexpr int PS_TEX_SHIFT = 2;      /* t0..15  -> 2..17  */
constexpr int PS_SAMP_SHIFT = 18;    /* s0..15  -> 18..33 */
constexpr int VS_TEX_SHIFT = 24;     /* t16..19 -> 40..43 */
constexpr int VS_SAMP_SHIFT = 44;    /* s0..3   -> 44..47 */

/* ------------------------------------------------------------------ device */
VkInstance g_inst;
VkPhysicalDevice g_phys;
VkDevice g_dev;
VkQueue g_queue;
u32 g_qfam;
VkPhysicalDeviceMemoryProperties g_memprops;
VkPhysicalDeviceProperties g_props;
VkFormat g_depth_fmt = VK_FORMAT_UNDEFINED;
bool g_bc_ok;
bool g_mirror_clamp_ok;
VkCommandPool g_cmdpool;
VkCommandBuffer g_cb_imm;
VkFence g_fence_imm;
bool g_active;
char g_log[8192];

struct Buffer {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* map = nullptr;
    VkDeviceSize size = 0;
};

u32 mem_type(u32 bits, VkMemoryPropertyFlags want)
{
    for (u32 i = 0; i < g_memprops.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (g_memprops.memoryTypes[i].propertyFlags & want) == want) return i;
    return UINT32_MAX;
}

uint64_t g_mem_img, g_mem_buf;   /* device memory held by images / buffers (5 s memory readout) */
bool buffer_create(Buffer& b, VkDeviceSize size, VkBufferUsageFlags usage)
{
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = usage;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(g_dev, &bi, nullptr, &b.buf) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(g_dev, b.buf, &mr);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                                     VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX ||
        vkAllocateMemory(g_dev, &ai, nullptr, &b.mem) != VK_SUCCESS) {
        vkDestroyBuffer(g_dev, b.buf, nullptr);
        b.buf = VK_NULL_HANDLE;
        return false;
    }
    vkBindBufferMemory(g_dev, b.buf, b.mem, 0);
    vkMapMemory(g_dev, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map);
    b.size = size;
    g_mem_buf += size;
    return true;
}

void buffer_destroy(Buffer& b)
{
    if (b.mem) { vkFreeMemory(g_dev, b.mem, nullptr); g_mem_buf -= b.size; }
    if (b.buf) vkDestroyBuffer(g_dev, b.buf, nullptr);
    b = Buffer{};
}

/* Grow-only host-visible buffer. Only called when nothing is in flight. */
bool buffer_ensure(Buffer& b, VkDeviceSize need, VkBufferUsageFlags usage)
{
    if (b.size >= need) return true;
    VkDeviceSize sz = b.size ? b.size : (4u << 20);
    while (sz < need) sz *= 2;
    buffer_destroy(b);
    return buffer_create(b, sz, usage);
}

Buffer g_upload_buf;    /* texture uploads */
uint64_t g_st_total, g_st_vb, g_st_ib, g_up_bytes;   /* bytes moved, per 5 s report */
u32 g_up_count;

/* Two submission slots: the CPU records and encodes frame N+1 while the GPU
 * still runs frame N. Each slot owns everything the GPU reads from the host
 * side (command buffer, descriptor pool, vertex/uniform staging, present
 * readback); a slot is only reused after its fence has signalled. */
constexpr u32 NUM_SLOTS = 2;
struct Slot {
    VkCommandBuffer cb = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkDescriptorPool dpool = VK_NULL_HANDLE;
    Buffer stage;      /* vertex/index/uniform data for one submit */
    Buffer readback;   /* present image copy */
    bool in_flight = false, publish = false;
    u32 pw = 0, ph = 0;
    uint64_t serial = 0;
};
Slot g_slot[NUM_SLOTS];
u32 g_cur;                 /* slot being encoded */
uint64_t g_serial;         /* submissions made */
uint64_t g_completed;      /* highest submission known finished */
#define g_cb (g_slot[g_cur].cb)
#define g_fence (g_slot[g_cur].fence)
#define g_dpool (g_slot[g_cur].dpool)
#define g_stage_buf (g_slot[g_cur].stage)
#define g_readback_buf (g_slot[g_cur].readback)
Buffer g_zero_ubo;      /* bound when a stage has no constants */

/* ------------------------------------------------------------------ objects */
struct Obj {
    bool live = false, retired = false, owns = true, depth = false, cube = false;
    bool bc_decode = false;
    VkImage img = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;   /* sampled view (swizzled for textures) */
    VkImageView att = VK_NULL_HANDLE;    /* attachment view (identity)          */
    VkFormat fmt = VK_FORMAT_UNDEFINED;
    rsx_be_format bfmt = RSX_BE_FMT_NONE;
    u32 w = 0, h = 0, mips = 1, layers = 1;
    u32 parent = 0;                      /* surface_view: the owning surface    */
    uint64_t memsz = 0;                  /* device memory it owns (memory readout) */
    bool initialised = false;            /* moved out of UNDEFINED              */
    uint64_t retire_after = 0;           /* destroy once this submission is done */
    u32 frame_writes = 0;                /* draws/clears into it in frame write_frame */
    u32 write_frame = 0;
    float last_clear[4] = {0, 0, 0, 0};
};
Obj g_obj[MAX_OBJECTS];
u32 g_obj_count;
u32 g_obj_free[MAX_OBJECTS];
u32 g_obj_free_count;

struct View { u32 surface, remap, format, view; };
View g_views[MAX_VIEWS];
u32 g_view_count;

Obj* obj(u32 h)
{
    return (h && h <= g_obj_count && g_obj[h - 1].live) ? &g_obj[h - 1] : nullptr;
}

u32 obj_add(const Obj& o)
{
    u32 slot;
    if (g_obj_free_count) slot = g_obj_free[--g_obj_free_count];
    else {
        if (g_obj_count >= MAX_OBJECTS) return 0;
        slot = g_obj_count++;
    }
    g_obj[slot] = o;
    g_obj[slot].live = true;
    return slot + 1;
}

void obj_destroy_now(Obj& o)
{
    if (o.view) vkDestroyImageView(g_dev, o.view, nullptr);
    if (o.att) vkDestroyImageView(g_dev, o.att, nullptr);
    if (o.owns) {
        if (o.img) vkDestroyImage(g_dev, o.img, nullptr);
        if (o.mem) { vkFreeMemory(g_dev, o.mem, nullptr); g_mem_img -= o.memsz; }
    }
    o = Obj{};
}

u32 g_rec_count;   /* forward: records pending (objects can't be recycled while > 0) */

void collect_retired()
{
    for (u32 i = 0; i < g_obj_count; i++) {
        if (!g_obj[i].live || !g_obj[i].retired || g_completed < g_obj[i].retire_after) continue;
        obj_destroy_now(g_obj[i]);
        g_obj_free[g_obj_free_count++] = i;
    }
}

/* ------------------------------------------------------------------ formats */
VkFormat vk_format(rsx_be_format f)
{
    switch (f) {
    case RSX_BE_FMT_R8:            return VK_FORMAT_R8_UNORM;
    case RSX_BE_FMT_R8G8:          return VK_FORMAT_R8G8_UNORM;
    case RSX_BE_FMT_R8G8B8A8:      return VK_FORMAT_R8G8B8A8_UNORM;
    case RSX_BE_FMT_BC1:           return g_bc_ok ? VK_FORMAT_BC1_RGBA_UNORM_BLOCK : VK_FORMAT_R8G8B8A8_UNORM;
    case RSX_BE_FMT_BC2:           return g_bc_ok ? VK_FORMAT_BC2_UNORM_BLOCK : VK_FORMAT_R8G8B8A8_UNORM;
    case RSX_BE_FMT_BC3:           return g_bc_ok ? VK_FORMAT_BC3_UNORM_BLOCK : VK_FORMAT_R8G8B8A8_UNORM;
    case RSX_BE_FMT_R16:           return VK_FORMAT_R16_UNORM;
    case RSX_BE_FMT_R16G16:        return VK_FORMAT_R16G16_UNORM;
    case RSX_BE_FMT_R16G16F:       return VK_FORMAT_R16G16_SFLOAT;
    case RSX_BE_FMT_R16G16B16A16F: return VK_FORMAT_R16G16B16A16_SFLOAT;
    case RSX_BE_FMT_R32F:          return VK_FORMAT_R32_SFLOAT;
    case RSX_BE_FMT_R32G32B32A32F: return VK_FORMAT_R32G32B32A32_SFLOAT;
    default:                       return VK_FORMAT_R8_UNORM;
    }
}

u32 bytes_per_pixel(VkFormat f)
{
    switch (f) {
    case VK_FORMAT_R8_UNORM: return 1;
    case VK_FORMAT_R8G8_UNORM: case VK_FORMAT_R16_UNORM: return 2;
    case VK_FORMAT_R16G16B16A16_SFLOAT: return 8;
    case VK_FORMAT_R32G32B32A32_SFLOAT: return 16;
    default: return 4;
    }
}

bool depth_has_stencil()
{
    return g_depth_fmt == VK_FORMAT_D32_SFLOAT_S8_UINT || g_depth_fmt == VK_FORMAT_D24_UNORM_S8_UINT;
}

bool is_bc(rsx_be_format f) { return f == RSX_BE_FMT_BC1 || f == RSX_BE_FMT_BC2 || f == RSX_BE_FMT_BC3; }

VkComponentSwizzle swz(u8 sel)
{
    switch (sel) {
    case 0: return VK_COMPONENT_SWIZZLE_R;
    case 1: return VK_COMPONENT_SWIZZLE_G;
    case 2: return VK_COMPONENT_SWIZZLE_B;
    case 3: return VK_COMPONENT_SWIZZLE_A;
    case RSX_REMAP_ONE: return VK_COMPONENT_SWIZZLE_ONE;
    default: return VK_COMPONENT_SWIZZLE_ZERO;
    }
}

/* The crossbar arrives in A,R,G,B field order: destR is out[1] .. destA out[0]. */
VkComponentMapping remap_mapping(u32 remap, u32 rsx_fmt)
{
    u8 sel[4];
    rsx_texture_component_remap(remap, rsx_fmt & 0x9Fu, sel);
    return VkComponentMapping{swz(sel[1]), swz(sel[2]), swz(sel[3]), swz(sel[0])};
}

/* ------------------------------------------------------------------ BC decode */
void rgb565(u16 c, u8 out[3])
{
    out[0] = (u8)(((c >> 11) & 31) * 255 / 31);
    out[1] = (u8)(((c >> 5) & 63) * 255 / 63);
    out[2] = (u8)((c & 31) * 255 / 31);
}

void bc1_colors(const u8* b, u8 pal[4][4], bool force4)
{
    u16 c0 = (u16)(b[0] | (b[1] << 8)), c1 = (u16)(b[2] | (b[3] << 8));
    rgb565(c0, pal[0]);
    rgb565(c1, pal[1]);
    pal[0][3] = pal[1][3] = 255;
    if (c0 > c1 || force4) {
        for (int k = 0; k < 3; k++) {
            pal[2][k] = (u8)((2 * pal[0][k] + pal[1][k]) / 3);
            pal[3][k] = (u8)((pal[0][k] + 2 * pal[1][k]) / 3);
        }
        pal[2][3] = pal[3][3] = 255;
    } else {
        for (int k = 0; k < 3; k++) pal[2][k] = (u8)((pal[0][k] + pal[1][k]) / 2);
        pal[2][3] = 255;
        pal[3][0] = pal[3][1] = pal[3][2] = pal[3][3] = 0;
    }
}

/* Decode BC1/2/3 block rows into RGBA8 (w x h). */
void bc_decode(rsx_be_format f, const u8* src, u32 row_bytes, u32 w, u32 h, u8* dst)
{
    const u32 bw = (w + 3) / 4, bh = (h + 3) / 4;
    const u32 bsz = (f == RSX_BE_FMT_BC1) ? 8 : 16;
    for (u32 by = 0; by < bh; by++) {
        const u8* row = src + (size_t)by * row_bytes;
        for (u32 bx = 0; bx < bw; bx++) {
            const u8* blk = row + (size_t)bx * bsz;
            const u8* cblk = (f == RSX_BE_FMT_BC1) ? blk : blk + 8;
            u8 pal[4][4];
            bc1_colors(cblk, pal, f != RSX_BE_FMT_BC1);
            u32 idx = (u32)cblk[4] | ((u32)cblk[5] << 8) | ((u32)cblk[6] << 16) | ((u32)cblk[7] << 24);
            u8 alpha[16];
            if (f == RSX_BE_FMT_BC2) {
                for (int i = 0; i < 16; i++) {
                    u8 a4 = (u8)((blk[i / 2] >> ((i & 1) * 4)) & 15);
                    alpha[i] = (u8)(a4 * 17);
                }
            } else if (f == RSX_BE_FMT_BC3) {
                u8 a[8];
                a[0] = blk[0];
                a[1] = blk[1];
                if (a[0] > a[1]) for (int i = 1; i < 7; i++) a[i + 1] = (u8)(((7 - i) * a[0] + i * a[1]) / 7);
                else {
                    for (int i = 1; i < 5; i++) a[i + 1] = (u8)(((5 - i) * a[0] + i * a[1]) / 5);
                    a[6] = 0;
                    a[7] = 255;
                }
                u64 bits = 0;
                for (int i = 0; i < 6; i++) bits |= (u64)blk[2 + i] << (8 * i);
                for (int i = 0; i < 16; i++) alpha[i] = a[(bits >> (3 * i)) & 7];
            }
            for (int py = 0; py < 4; py++) {
                const u32 y = by * 4 + py;
                if (y >= h) break;
                for (int px = 0; px < 4; px++) {
                    const u32 x = bx * 4 + px;
                    if (x >= w) continue;
                    const int i = py * 4 + px;
                    const u8* c = pal[(idx >> (2 * i)) & 3];
                    u8* o = dst + ((size_t)y * w + x) * 4;
                    o[0] = c[0];
                    o[1] = c[1];
                    o[2] = c[2];
                    o[3] = (f == RSX_BE_FMT_BC1) ? c[3] : alpha[i];
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ images */
bool image_create(Obj& o, VkFormat fmt, u32 w, u32 h, u32 mips, u32 layers, bool cube,
                  VkImageUsageFlags usage)
{
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ii.flags = cube ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = fmt;
    ii.extent = {w, h, 1};
    ii.mipLevels = mips;
    ii.arrayLayers = layers;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = usage;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(g_dev, &ii, nullptr, &o.img) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(g_dev, o.img, &mr);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) ai.memoryTypeIndex = mem_type(mr.memoryTypeBits, 0);
    if (vkAllocateMemory(g_dev, &ai, nullptr, &o.mem) != VK_SUCCESS) {
        vkDestroyImage(g_dev, o.img, nullptr);
        o.img = VK_NULL_HANDLE;
        return false;
    }
    vkBindImageMemory(g_dev, o.img, o.mem, 0);
    o.memsz = mr.size;
    g_mem_img += mr.size;
    o.fmt = fmt;
    o.w = w;
    o.h = h;
    o.mips = mips;
    o.layers = layers;
    o.cube = cube;
    return true;
}

VkImageView make_view(VkImage img, VkFormat fmt, VkImageViewType type, VkImageAspectFlags aspect,
                      u32 mips, u32 layers, VkComponentMapping map)
{
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = img;
    vi.viewType = type;
    vi.format = fmt;
    vi.components = map;
    vi.subresourceRange = {aspect, 0, mips, 0, layers};
    VkImageView v = VK_NULL_HANDLE;
    vkCreateImageView(g_dev, &vi, nullptr, &v);
    return v;
}

const VkComponentMapping IDENTITY{VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                                  VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};

VkImageAspectFlags full_aspect(const Obj& o)
{
    if (!o.depth) return VK_IMAGE_ASPECT_COLOR_BIT;
    VkImageAspectFlags a = VK_IMAGE_ASPECT_DEPTH_BIT;
    if (g_depth_fmt == VK_FORMAT_D32_SFLOAT_S8_UINT || g_depth_fmt == VK_FORMAT_D24_UNORM_S8_UINT)
        a |= VK_IMAGE_ASPECT_STENCIL_BIT;
    return a;
}

void barrier_all(VkCommandBuffer cb)
{
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         0, 1, &mb, 0, nullptr, 0, nullptr);
}

/* UNDEFINED -> GENERAL for every image that has not been used yet. */
void init_layouts(VkCommandBuffer cb)
{
    std::vector<VkImageMemoryBarrier> bars;
    for (u32 i = 0; i < g_obj_count; i++) {
        Obj& o = g_obj[i];
        if (!o.live || !o.owns || o.initialised || !o.img) continue;
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.srcAccessMask = 0;
        b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = o.img;
        b.subresourceRange = {full_aspect(o), 0, o.mips, 0, o.layers};
        bars.push_back(b);
        o.initialised = true;
    }
    if (!bars.empty())
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                             0, 0, nullptr, 0, nullptr, (u32)bars.size(), bars.data());
}

VkCommandBuffer imm_begin()
{
    vkResetCommandBuffer(g_cb_imm, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(g_cb_imm, &bi);
    init_layouts(g_cb_imm);
    barrier_all(g_cb_imm);   /* after any frame still running on the GPU */
    return g_cb_imm;
}

void imm_end()
{
    vkEndCommandBuffer(g_cb_imm);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &g_cb_imm;
    vkResetFences(g_dev, 1, &g_fence_imm);
    vkQueueSubmit(g_queue, 1, &si, g_fence_imm);
    vkWaitForFences(g_dev, 1, &g_fence_imm, VK_TRUE, UINT64_MAX);
}

/* Copy tightly packed rows into one subresource. */
void upload_now(Obj& o, u32 face, u32 mip, u32 w, u32 h, const void* src, u32 row_bytes, u32 rows)
{
    const size_t bytes = (size_t)row_bytes * rows;
    g_up_count++;
    g_up_bytes += bytes;
    if (!buffer_ensure(g_upload_buf, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) return;
    memcpy(g_upload_buf.map, src, bytes);
    VkCommandBuffer cb = imm_begin();
    VkBufferImageCopy c{};
    c.bufferOffset = 0;
    c.bufferRowLength = 0;
    if (!is_bc(o.bfmt) || o.bc_decode) {
        const u32 bpp = bytes_per_pixel(o.fmt);
        if (bpp && row_bytes % bpp == 0 && row_bytes / bpp > w) c.bufferRowLength = row_bytes / bpp;
    }
    c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, mip, face, 1};
    c.imageExtent = {w, h, 1};
    vkCmdCopyBufferToImage(cb, g_upload_buf.buf, o.img, VK_IMAGE_LAYOUT_GENERAL, 1, &c);
    barrier_all(cb);
    imm_end();
}

/* ------------------------------------------------------------------ shaders */
struct ResBinding {
    int binding;
    VkDescriptorType type;
    u32 count;
    VkShaderStageFlags stages;
    bool cube;
    u32 ubo_size;
};

bool g_glslang_ready;

bool compile_spirv(const char* src, EShLanguage lang, bool hlsl, std::vector<u32>& spv,
                   std::vector<ResBinding>* refl)
{
    if (!g_glslang_ready) {
        if (!glslang::InitializeProcess()) return false;
        g_glslang_ready = true;
    }
    const EShMessages msgs = (EShMessages)(EShMsgSpvRules | EShMsgVulkanRules |
                                           (hlsl ? EShMsgReadHlsl : 0));
    glslang::TShader sh(lang);
    sh.setStrings(&src, 1);
    if (hlsl) {
        sh.setEnvInput(glslang::EShSourceHlsl, lang, glslang::EShClientVulkan, 100);
        sh.setEntryPoint("main");
        const bool vs = (lang == EShLangVertex);
        sh.setShiftBinding(glslang::EResTexture, vs ? VS_TEX_SHIFT : PS_TEX_SHIFT);
        sh.setShiftBinding(glslang::EResSampler, vs ? VS_SAMP_SHIFT : PS_SAMP_SHIFT);
        sh.setShiftBinding(glslang::EResUbo, 0);
    } else {
        sh.setEnvInput(glslang::EShSourceGlsl, lang, glslang::EShClientVulkan, 100);
    }
    sh.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_1);
    sh.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_3);
    sh.setAutoMapBindings(true);
    sh.setAutoMapLocations(true);
    if (!sh.parse(GetDefaultResources(), hlsl ? 100 : 450, false, msgs)) {
        snprintf(g_log, sizeof g_log, "parse: %s", sh.getInfoLog());
        return false;
    }
    glslang::TProgram prog;
    prog.addShader(&sh);
    if (!prog.link(msgs) || !prog.mapIO()) {
        snprintf(g_log, sizeof g_log, "link: %s", prog.getInfoLog());
        return false;
    }
    if (refl) {
        prog.buildReflection();
        const VkShaderStageFlags st = (lang == EShLangVertex) ? VK_SHADER_STAGE_VERTEX_BIT
                                                              : VK_SHADER_STAGE_FRAGMENT_BIT;
        for (int i = 0; i < prog.getNumUniformBlocks(); i++) {
            const glslang::TObjectReflection& b = prog.getUniformBlock(i);
            if (b.getBinding() < 0) continue;
            refl->push_back({b.getBinding(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, st, false,
                             (u32)(b.size > 0 ? b.size : 0)});
        }
        for (int i = 0; i < prog.getNumUniformVariables(); i++) {
            const glslang::TObjectReflection& u = prog.getUniform(i);
            const glslang::TType* t = u.getType();
            if (!t || t->getBasicType() != glslang::EbtSampler || u.getBinding() < 0) continue;
            const glslang::TSampler& s = t->getSampler();
            VkDescriptorType dt = s.isPureSampler() ? VK_DESCRIPTOR_TYPE_SAMPLER
                                : s.isCombined()    ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                : s.isImage()       ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                                    : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            u32 count = t->isArray() ? (u32)t->getOuterArraySize() : 1u;
            if (count == 0) count = 1;
            refl->push_back({u.getBinding(), dt, count, st, s.dim == glslang::EsdCube, 0});
        }
    }
    spv::SpvBuildLogger logger;
    glslang::SpvOptions opts;
    opts.disableOptimizer = !hlsl;   /* HLSL needs the legalization passes */
    glslang::GlslangToSpv(*prog.getIntermediate(lang), spv, &logger, &opts);
    if (spv.empty()) {
        snprintf(g_log, sizeof g_log, "spirv: %s", logger.getAllMessages().c_str());
        return false;
    }
    return true;
}

VkShaderModule make_module(const std::vector<u32>& spv)
{
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    ci.codeSize = spv.size() * 4;
    ci.pCode = spv.data();
    VkShaderModule m = VK_NULL_HANDLE;
    vkCreateShaderModule(g_dev, &ci, nullptr, &m);
    return m;
}

u64 fnv1a(const char* s)
{
    u64 h = 1469598103934665603ull;
    for (; *s; s++) {
        h ^= (u8)*s;
        h *= 1099511628211ull;
    }
    return h;
}

struct Func {
    u64 hash;
    VkShaderModule mod;
    std::vector<ResBinding> refl;
    bool ok;
};
std::deque<Func> g_funcs;   /* deque: pointers stay valid as it grows */
u32 g_shader_failures;

const Func* function_for(const char* hlsl, bool vs)
{
    const u64 h = fnv1a(hlsl) ^ (vs ? 0x5653ull : 0x5053ull);
    for (const Func& f : g_funcs) if (f.hash == h) return f.ok ? &f : nullptr;
    Func f{h, VK_NULL_HANDLE, {}, false};
    std::vector<u32> spv;
    if (compile_spirv(hlsl, vs ? EShLangVertex : EShLangFragment, true, spv, &f.refl)) {
        f.mod = make_module(spv);
        f.ok = f.mod != VK_NULL_HANDLE;
    }
    if (!f.ok && g_shader_failures++ < 16) {
        fprintf(stderr, "[RSX vulkan] %s program %016llx failed: %.600s\n", vs ? "vertex" : "fragment",
                (unsigned long long)h, g_log);
        const char* dump = getenv("EU_VK_SHADER_DUMP");
        if (dump && *dump == '1') fprintf(stderr, "----- HLSL -----\n%s\n----------------\n", hlsl);
    }
    g_funcs.push_back(std::move(f));
    return g_funcs.back().ok ? &g_funcs.back() : nullptr;
}

/* ------------------------------------------------------------------ samplers */
struct Samp { u64 key; VkSampler s; };
Samp g_samp[MAX_SAMPLERS];
u32 g_samp_count;
VkSampler g_default_sampler, g_point_sampler;

VkSamplerAddressMode wrap(u32 w)
{
    switch (w & 0xFu) {
    case 1: return VK_SAMPLER_ADDRESS_MODE_REPEAT;
    case 2: return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
    case 4: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
    case 6: case 7: case 8: return g_mirror_clamp_ok ? VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE
                                                     : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    default: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    }
}

int sampler_slot(const rsx_be_sampler_desc* d)
{
    const u64 key = (u64)d->min_linear | ((u64)d->mag_linear << 1) | ((u64)d->mip_linear << 2) |
                    ((u64)d->mip_present << 3) | ((u64)d->wrap_s << 4) | ((u64)d->wrap_t << 8) |
                    ((u64)d->wrap_r << 12) | ((u64)(u32)(d->min_lod * 256.0f) << 16) |
                    ((u64)(u32)(d->max_lod * 256.0f) << 32);
    for (u32 i = 0; i < g_samp_count; i++) if (g_samp[i].key == key) return (int)i;
    if (g_samp_count >= MAX_SAMPLERS) return -1;
    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = d->mag_linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    si.minFilter = d->min_linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    si.mipmapMode = d->mip_linear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = wrap(d->wrap_s);
    si.addressModeV = wrap(d->wrap_t);
    si.addressModeW = wrap(d->wrap_r);
    si.minLod = d->mip_present ? d->min_lod : 0.0f;
    si.maxLod = d->mip_present ? d->max_lod : 0.25f;   /* no mips: stay on level 0 */
    si.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
    VkSampler s = VK_NULL_HANDLE;
    if (vkCreateSampler(g_dev, &si, nullptr, &s) != VK_SUCCESS) return -1;
    g_samp[g_samp_count] = {key, s};
    return (int)g_samp_count++;
}

/* ------------------------------------------------------------------ pipelines */
struct Pipe {
    bool live = false;
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline variant[3][2] = {};   /* [point/line/triangle][with colour / depth-only] */
    bool failed[3][2] = {};
    std::vector<ResBinding> bindings;
    VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE;
    rsx_be_render_state rs{};
    u32 attr_count = 0, stride = 0, rt_count = 1;
    VkFormat rt_fmt = VK_FORMAT_R8G8B8A8_UNORM;
};
Pipe g_pipe[MAX_PIPES];
u32 g_pipe_count;

VkCompareOp cmp(u32 f)
{
    switch (f) {
    case 0x0200: return VK_COMPARE_OP_NEVER;
    case 0x0201: return VK_COMPARE_OP_LESS;
    case 0x0202: return VK_COMPARE_OP_EQUAL;
    case 0x0203: return VK_COMPARE_OP_LESS_OR_EQUAL;
    case 0x0204: return VK_COMPARE_OP_GREATER;
    case 0x0205: return VK_COMPARE_OP_NOT_EQUAL;
    case 0x0206: return VK_COMPARE_OP_GREATER_OR_EQUAL;
    default: return VK_COMPARE_OP_ALWAYS;
    }
}

VkStencilOp sop(u32 op)
{
    switch (op) {
    case 0x0000: return VK_STENCIL_OP_ZERO;
    case 0x1E01: return VK_STENCIL_OP_REPLACE;
    case 0x1E02: return VK_STENCIL_OP_INCREMENT_AND_CLAMP;
    case 0x1E03: return VK_STENCIL_OP_DECREMENT_AND_CLAMP;
    case 0x150A: return VK_STENCIL_OP_INVERT;
    case 0x8507: return VK_STENCIL_OP_INCREMENT_AND_WRAP;
    case 0x8508: return VK_STENCIL_OP_DECREMENT_AND_WRAP;
    default: return VK_STENCIL_OP_KEEP;
    }
}

VkBlendFactor bf(u32 f)
{
    switch (f) {
    case 0x0000: return VK_BLEND_FACTOR_ZERO;
    case 0x0001: return VK_BLEND_FACTOR_ONE;
    case 0x0300: return VK_BLEND_FACTOR_SRC_COLOR;
    case 0x0301: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
    case 0x0302: return VK_BLEND_FACTOR_SRC_ALPHA;
    case 0x0303: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    case 0x0304: return VK_BLEND_FACTOR_DST_ALPHA;
    case 0x0305: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
    case 0x0306: return VK_BLEND_FACTOR_DST_COLOR;
    case 0x0307: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
    case 0x0308: return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
    default: return VK_BLEND_FACTOR_ONE;
    }
}

VkBlendOp bop(u32 e)
{
    switch (e) {
    case 0x8007: return VK_BLEND_OP_MIN;
    case 0x8008: return VK_BLEND_OP_MAX;
    case 0x800A: return VK_BLEND_OP_SUBTRACT;
    case 0x800B: return VK_BLEND_OP_REVERSE_SUBTRACT;
    default: return VK_BLEND_OP_ADD;
    }
}

int topo_class(rsx_topology t)
{
    switch (t) {
    case RSX_TOPOLOGY_POINTS: return 0;
    case RSX_TOPOLOGY_LINES: case RSX_TOPOLOGY_LINE_STRIP: return 1;
    default: return 2;
    }
}

VkPrimitiveTopology vk_topo(rsx_topology t)
{
    switch (t) {
    case RSX_TOPOLOGY_POINTS: return VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
    case RSX_TOPOLOGY_LINES: return VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
    case RSX_TOPOLOGY_LINE_STRIP: return VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
    case RSX_TOPOLOGY_TRIANGLE_STRIP: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    default: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    }
}

VkPipeline build_variant(Pipe& p, int cls, bool depth_only)
{
    const u32 ncol = depth_only ? 0u : p.rt_count;
    std::vector<VkVertexInputAttributeDescription> attrs;
    for (u32 s = 0; s < p.attr_count; s++)
        attrs.push_back({s, 0, VK_FORMAT_R32G32B32A32_SFLOAT, s * 16u});
    VkVertexInputBindingDescription vb{0, p.stride, VK_VERTEX_INPUT_RATE_VERTEX};
    VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vi.vertexBindingDescriptionCount = 1;
    vi.pVertexBindingDescriptions = &vb;
    vi.vertexAttributeDescriptionCount = (u32)attrs.size();
    vi.pVertexAttributeDescriptions = attrs.data();

    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = cls == 0 ? VK_PRIMITIVE_TOPOLOGY_POINT_LIST
                : cls == 1 ? VK_PRIMITIVE_TOPOLOGY_LINE_LIST : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1;
    vp.scissorCount = 1;

    const rsx_be_render_state& rs = p.rs;
    VkPipelineRasterizationStateCreateInfo rz{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rz.polygonMode = VK_POLYGON_MODE_FILL;
    rz.lineWidth = 1.0f;
    rz.cullMode = VK_CULL_MODE_NONE;
    /* CULL_FACE FRONT=0x0404 BACK=0x0405 FRONT_AND_BACK=0x0408 (front, as the
     * D3D12 and Metal engines); FRONT_FACE CW=0x0900 CCW=0x0901. The negative
     * viewport height keeps D3D's winding convention, as Metal's does. */
    if (rs.cull_enable && rs.cull_face)
        rz.cullMode = (rs.cull_face == 0x0404u || rs.cull_face == 0x0408u) ? VK_CULL_MODE_FRONT_BIT
                    : rs.cull_face == 0x0405u ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE;
    rz.frontFace = (rs.front_face == 0x0901u) ? VK_FRONT_FACE_COUNTER_CLOCKWISE : VK_FRONT_FACE_CLOCKWISE;

    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineDepthStencilStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
    ds.depthTestEnable = rs.depth_test ? VK_TRUE : VK_FALSE;
    ds.depthWriteEnable = (rs.depth_test && rs.depth_write) ? VK_TRUE : VK_FALSE;
    ds.depthCompareOp = rs.depth_test ? cmp(rs.depth_func) : VK_COMPARE_OP_ALWAYS;
    ds.stencilTestEnable = rs.stencil_enable ? VK_TRUE : VK_FALSE;
    VkStencilOpState fs{sop(rs.s_fail), sop(rs.s_zpass), sop(rs.s_zfail), cmp(rs.s_func),
                        rs.s_func_mask & 0xFFu, rs.s_write_mask & 0xFFu, 0};
    ds.front = fs;
    ds.back = rs.stencil_two_sided
                  ? VkStencilOpState{sop(rs.bs_fail), sop(rs.bs_zpass), sop(rs.bs_zfail), cmp(rs.bs_func),
                                     rs.s_func_mask & 0xFFu, rs.s_write_mask & 0xFFu, 0}
                  : fs;

    VkColorComponentFlags wm = 0;
    if ((rs.color_mask >> 0) & 0xFF) wm |= VK_COLOR_COMPONENT_B_BIT;
    if ((rs.color_mask >> 8) & 0xFF) wm |= VK_COLOR_COMPONENT_G_BIT;
    if ((rs.color_mask >> 16) & 0xFF) wm |= VK_COLOR_COMPONENT_R_BIT;
    if ((rs.color_mask >> 24) & 0xFF) wm |= VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendAttachmentState att{};
    att.colorWriteMask = wm;
    if (rs.blend_enable) {
        att.blendEnable = VK_TRUE;
        att.srcColorBlendFactor = bf(rs.sf_rgb);
        att.dstColorBlendFactor = bf(rs.df_rgb);
        att.colorBlendOp = bop(rs.eq_rgb);
        att.srcAlphaBlendFactor = bf(rs.sf_a);
        att.dstAlphaBlendFactor = bf(rs.df_a);
        att.alphaBlendOp = bop(rs.eq_a);
    }
    VkPipelineColorBlendAttachmentState atts[RSX_BE_MAX_COLOR_TARGETS] = {att, att, att, att};
    VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = ncol;
    cb.pAttachments = atts;

    VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
                            VK_DYNAMIC_STATE_STENCIL_REFERENCE, VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY};
    VkPipelineDynamicStateCreateInfo dy{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dy.dynamicStateCount = 4;
    dy.pDynamicStates = dyn;

    VkFormat fmts[RSX_BE_MAX_COLOR_TARGETS] = {p.rt_fmt, p.rt_fmt, p.rt_fmt, p.rt_fmt};
    VkPipelineRenderingCreateInfo ri{VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO};
    ri.colorAttachmentCount = ncol;
    ri.pColorAttachmentFormats = fmts;
    ri.depthAttachmentFormat = g_depth_fmt;
    ri.stencilAttachmentFormat = depth_has_stencil() ? g_depth_fmt : VK_FORMAT_UNDEFINED;

    VkPipelineShaderStageCreateInfo stages[2] = {
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, p.vs, "main", nullptr},
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, p.fs, "main", nullptr},
    };
    VkGraphicsPipelineCreateInfo gi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gi.pNext = &ri;
    gi.stageCount = 2;
    gi.pStages = stages;
    gi.pVertexInputState = &vi;
    gi.pInputAssemblyState = &ia;
    gi.pViewportState = &vp;
    gi.pRasterizationState = &rz;
    gi.pMultisampleState = &ms;
    gi.pDepthStencilState = &ds;
    gi.pColorBlendState = &cb;
    gi.pDynamicState = &dy;
    gi.layout = p.layout;
    VkPipeline pl = VK_NULL_HANDLE;
    if (vkCreateGraphicsPipelines(g_dev, VK_NULL_HANDLE, 1, &gi, nullptr, &pl) != VK_SUCCESS) {
        static int said;
        if (said++ < 8) fprintf(stderr, "[RSX vulkan] graphics pipeline creation failed\n");
        return VK_NULL_HANDLE;
    }
    return pl;
}

/* ------------------------------------------------------------------ helpers */
VkPipelineLayout g_helper_layout;
VkDescriptorSetLayout g_helper_dsl;
VkShaderModule g_fullscreen_vs, g_blit_fs, g_depth_fs, g_depthpack_fs;
VkPipeline g_blit_pipe, g_depth_pipe, g_depthpack_pipe;
Obj g_present;              /* RGBA8 image the presented surface is scaled into */
u32 g_null2d, g_nullcube;   /* object handles */

const char* kFullscreenVS =
    "#version 450\n"
    "layout(location=0) out vec2 uv;\n"
    "void main(){ vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n"
    "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); uv = p; }\n";
const char* kBlitFS =
    "#version 450\n"
    "layout(set=0,binding=0) uniform texture2D src; layout(set=0,binding=1) uniform sampler smp;\n"
    "layout(location=0) in vec2 uv; layout(location=0) out vec4 o;\n"
    "void main(){ o = texture(sampler2D(src, smp), uv); }\n";
/* Depth (R32F snapshot) re-encoded the way a PS3 shader sees a DEPTH24_S8
 * buffer read through an A8R8G8B8 texture: the 32-bit word is d24 << 8 | s8,
 * so A = d[23:16], R = d[15:8], G = d[7:0], B = stencil (0). Stored in this
 * backend's R,G,B,A order, matching how decoded A8R8G8B8 textures are kept. */
const char* kDepthPackFS =
    "#version 450\n"
    "layout(set=0,binding=0) uniform texture2D src; layout(set=0,binding=1) uniform sampler smp;\n"
    "layout(location=0) in vec2 uv; layout(location=0) out vec4 o;\n"
    "void main(){ float d = clamp(texture(sampler2D(src, smp), uv).r, 0.0, 1.0);\n"
    "  uint u = uint(d * 16777215.0 + 0.5);\n"
    "  o = vec4(float((u >> 8) & 255u), float(u & 255u), 0.0, float((u >> 16) & 255u)) / 255.0; }\n";

const char* kDepthFS =
    "#version 450\n"
    "layout(set=0,binding=0) uniform texture2D src; layout(set=0,binding=1) uniform sampler smp;\n"
    "layout(location=0) in vec2 uv; layout(location=0) out float o;\n"
    "void main(){ o = texture(sampler2D(src, smp), uv).r; }\n";

VkPipeline helper_pipeline(VkShaderModule fs, VkFormat fmt)
{
    VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = vp.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rz{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rz.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState att{};
    att.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1;
    cb.pAttachments = &att;
    VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dy{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dy.dynamicStateCount = 2;
    dy.pDynamicStates = dyn;
    VkPipelineRenderingCreateInfo ri{VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO};
    ri.colorAttachmentCount = 1;
    ri.pColorAttachmentFormats = &fmt;
    VkPipelineShaderStageCreateInfo st[2] = {
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, g_fullscreen_vs, "main", nullptr},
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, fs, "main", nullptr},
    };
    VkGraphicsPipelineCreateInfo gi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gi.pNext = &ri;
    gi.stageCount = 2;
    gi.pStages = st;
    gi.pVertexInputState = &vi;
    gi.pInputAssemblyState = &ia;
    gi.pViewportState = &vp;
    gi.pRasterizationState = &rz;
    gi.pMultisampleState = &ms;
    gi.pColorBlendState = &cb;
    gi.pDynamicState = &dy;
    gi.layout = g_helper_layout;
    VkPipeline p = VK_NULL_HANDLE;
    vkCreateGraphicsPipelines(g_dev, VK_NULL_HANDLE, 1, &gi, nullptr, &p);
    return p;
}

/* ------------------------------------------------------------------ records */
enum RecKind { REC_DRAW, REC_CLEAR_COLOR, REC_CLEAR_DS, REC_DEPTH_RESOLVE, REC_DEPTH_PACK, REC_COPY };
struct Rec {
    RecKind kind;
    u32 rt[RSX_BE_MAX_COLOR_TARGETS];
    u32 nrt;
    u32 depth;
    u32 pipeline;
    rsx_topology topo;
    u32 vb_off, stride, vertex_count;
    u32 ib_off, index_count;
    u32 vs_cb_off, vs_cb_bytes, ps_cb_off, ps_cb_bytes;
    u32 tex[RSX_BE_MAX_TEXTURES];
    int samp[RSX_BE_MAX_TEXTURES];
    u32 vtex[RSX_BE_MAX_VERTEX_TEXTURES];
    int vsamp[RSX_BE_MAX_VERTEX_TEXTURES];
    float vp[4];
    u32 sc[4];
    u32 stencil_ref;
    float clear_rgba[4];
    u32 clear_flags;
    float clear_depth;
    u8 clear_stencil;
    u32 resolve_dst;
    u32 copy_rect[8];   /* REC_COPY: source x y w h, destination x y w h */
    int tag;            /* REC_DRAW: game call chain that issued it (eu_fifo_tag.cpp) */
};
extern "C" int g_eu_draw_tag;
extern "C" const char* eu_fifo_tag_name(int id, char* buf, size_t cap);
Rec* g_rec;   /* MAX_RECORDS, heap */
u32 g_dropped;
Rec g_pending;
u8* g_stage;
u32 g_stage_used, g_stage_cap;
u32 g_guest_draws, g_last_guest_draws, g_skipped_draws;

void encode_and_submit(u32 present_surface);

/* A full record list is flushed to the GPU instead of dropping the rest of the
 * frame (build #20 lost the end of busy frames -- usually the final composite,
 * which left the screen black). */
u32 g_mid_flushes;
u32 g_frame_no = 1;
void mark_write(u32 h, const float* clear = nullptr)
{
    Obj* o = obj(h);
    if (!o) return;
    if (o->write_frame != g_frame_no) { o->write_frame = g_frame_no; o->frame_writes = 0; }
    o->frame_writes++;
    if (clear) memcpy(o->last_clear, clear, sizeof o->last_clear);
}

void rec_room()
{
    if (g_rec_count >= MAX_RECORDS) {
        g_mid_flushes++;
        encode_and_submit(0);
    }
}

bool stage_reserve(u32 bytes, u32* off)
{
    g_st_total += bytes;
    const u32 start = (g_stage_used + STAGE_ALIGN - 1u) & ~(STAGE_ALIGN - 1u);
    if ((u64)start + bytes > STAGE_MAX) {
        encode_and_submit(0);
        return stage_reserve(bytes, off);
    }
    if (start + bytes > g_stage_cap) {
        u32 cap = g_stage_cap ? g_stage_cap : (4u << 20);
        while (start + bytes > cap) cap *= 2u;
        u8* n = (u8*)realloc(g_stage, cap);
        if (!n) return false;
        g_stage = n;
        g_stage_cap = cap;
    }
    *off = start;
    g_stage_used = start + bytes;
    return true;
}

bool stage_copy(const void* src, u32 bytes, u32* off)
{
    if (!bytes) {
        *off = 0;
        return true;
    }
    if (!stage_reserve(bytes, off)) return false;
    memcpy(g_stage + *off, src, bytes);
    return true;
}

/* Depth images per size, for passes whose guest named no zeta. */
struct FallbackDepth { u32 w, h, handle; };
std::vector<FallbackDepth> g_fallback_depth;

u32 depth_create(u32 w, u32 h);

u32 fallback_depth(u32 w, u32 h)
{
    for (auto& f : g_fallback_depth) if (f.w == w && f.h == h) return f.handle;
    u32 hnd = depth_create(w, h);
    if (hnd) g_fallback_depth.push_back({w, h, hnd});
    return hnd;
}

/* ------------------------------------------------------------------ encode */
bool targets_are(const Rec& r, const u32* rt, u32 nrt)
{
    if (r.nrt != nrt) return false;
    for (u32 k = 0; k < nrt; k++) if (r.rt[k] != rt[k]) return false;
    return true;
}

void bind_draw(VkCommandBuffer cb, const Rec& r, Pipe& p, VkPipeline pl, u32 rw, u32 rh)
{
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pl);
    vkCmdSetPrimitiveTopology(cb, vk_topo(r.topo));
    /* D3D/Metal convention through a negative-height viewport (Vulkan 1.1). */
    float vx = r.vp[0], vy = r.vp[1], vw = r.vp[2], vh = r.vp[3];
    if (vw <= 0.0f || vh <= 0.0f) {
        vx = 0;
        vy = 0;
        vw = (float)rw;
        vh = (float)rh;
    }
    VkViewport v{vx, vy + vh, vw, -vh, 0.0f, 1.0f};
    vkCmdSetViewport(cb, 0, 1, &v);
    VkRect2D sc{{0, 0}, {rw, rh}};
    if (r.sc[2] && r.sc[3]) sc = {{(int32_t)r.sc[0], (int32_t)r.sc[1]}, {r.sc[2], r.sc[3]}};
    vkCmdSetScissor(cb, 0, 1, &sc);
    vkCmdSetStencilReference(cb, VK_STENCIL_FACE_FRONT_AND_BACK, r.stencil_ref & 0xFFu);

    VkDescriptorSet set = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool = g_dpool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &p.dsl;
    if (vkAllocateDescriptorSets(g_dev, &ai, &set) != VK_SUCCESS) return;

    std::vector<VkWriteDescriptorSet> writes;
    std::vector<VkDescriptorImageInfo> imgs;
    std::vector<VkDescriptorBufferInfo> bufs;
    imgs.reserve(64);
    bufs.reserve(4);
    for (const ResBinding& b : p.bindings) {
        VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        w.dstSet = set;
        w.dstBinding = (u32)b.binding;
        w.descriptorType = b.type;
        w.descriptorCount = b.count;
        if (b.type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
            const bool vs = (b.binding == B_VS_UBO);
            const u32 off = vs ? r.vs_cb_off : r.ps_cb_off;
            const u32 bytes = vs ? r.vs_cb_bytes : r.ps_cb_bytes;
            VkDescriptorBufferInfo bi{};
            if (bytes) {
                bi.buffer = g_stage_buf.buf;
                bi.offset = off;
                u32 range = bytes > b.ubo_size ? bytes : b.ubo_size;
                if (range > UBO_PAD) range = UBO_PAD;
                bi.range = range;
            } else {
                bi.buffer = g_zero_ubo.buf;
                bi.offset = 0;
                bi.range = b.ubo_size ? (b.ubo_size > UBO_PAD ? UBO_PAD : b.ubo_size) : VK_WHOLE_SIZE;
            }
            bufs.push_back(bi);
            w.pBufferInfo = &bufs.back();
        } else {
            const size_t first = imgs.size();
            for (u32 e = 0; e < b.count; e++) {
                VkDescriptorImageInfo ii{};
                ii.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
                const int bind = b.binding + (int)e;
                if (b.type == VK_DESCRIPTOR_TYPE_SAMPLER) {
                    int slot = -1;
                    if (bind >= PS_SAMP_SHIFT && bind < PS_SAMP_SHIFT + (int)RSX_BE_MAX_TEXTURES)
                        slot = r.samp[bind - PS_SAMP_SHIFT];
                    else if (bind >= VS_SAMP_SHIFT && bind < VS_SAMP_SHIFT + (int)RSX_BE_MAX_VERTEX_TEXTURES)
                        slot = r.vsamp[bind - VS_SAMP_SHIFT];
                    ii.sampler = slot >= 0 ? g_samp[slot].s : g_default_sampler;
                } else {
                    u32 h = 0;
                    if (bind >= PS_TEX_SHIFT && bind < PS_TEX_SHIFT + (int)RSX_BE_MAX_TEXTURES)
                        h = r.tex[bind - PS_TEX_SHIFT];
                    else if (bind >= VS_TEX_SHIFT + 16 && bind < VS_TEX_SHIFT + 16 + (int)RSX_BE_MAX_VERTEX_TEXTURES)
                        h = r.vtex[bind - VS_TEX_SHIFT - 16];
                    Obj* o = obj(h);
                    if (!o || !o->view || o->cube != b.cube) o = obj(b.cube ? g_nullcube : g_null2d);
                    ii.imageView = o ? o->view : VK_NULL_HANDLE;
                    if (b.type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) ii.sampler = g_default_sampler;
                }
                imgs.push_back(ii);
            }
            w.pImageInfo = &imgs[first];
        }
        writes.push_back(w);
    }
    /* imgs/bufs were reserved large enough not to move; re-point to be safe. */
    {
        size_t ii = 0, bi = 0;
        for (auto& w : writes) {
            if (w.descriptorType == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) w.pBufferInfo = &bufs[bi++];
            else {
                w.pImageInfo = &imgs[ii];
                ii += w.descriptorCount;
            }
        }
    }
    if (!writes.empty()) vkUpdateDescriptorSets(g_dev, (u32)writes.size(), writes.data(), 0, nullptr);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, p.layout, 0, 1, &set, 0, nullptr);
    VkDeviceSize vo = r.vb_off;
    vkCmdBindVertexBuffers(cb, 0, 1, &g_stage_buf.buf, &vo);
    if (r.index_count) {
        vkCmdBindIndexBuffer(cb, g_stage_buf.buf, r.ib_off, VK_INDEX_TYPE_UINT32);
        vkCmdDrawIndexed(cb, r.index_count, 1, 0, 0, 0);
    } else {
        vkCmdDraw(cb, r.vertex_count, 1, 0, 0);
    }
    g_guest_draws++;
}

void helper_pass(VkCommandBuffer cb, VkPipeline pl, VkImageView src, VkSampler smp, VkImageView dst,
                 u32 w, u32 h)
{
    VkRenderingAttachmentInfo ca{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
    ca.imageView = dst;
    ca.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    ca.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    ca.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    VkRenderingInfo ri{VK_STRUCTURE_TYPE_RENDERING_INFO};
    ri.renderArea = {{0, 0}, {w, h}};
    ri.layerCount = 1;
    ri.colorAttachmentCount = 1;
    ri.pColorAttachments = &ca;
    barrier_all(cb);
    vkCmdBeginRendering(cb, &ri);
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pl);
    VkViewport v{0, 0, (float)w, (float)h, 0, 1};
    VkRect2D sc{{0, 0}, {w, h}};
    vkCmdSetViewport(cb, 0, 1, &v);
    vkCmdSetScissor(cb, 0, 1, &sc);
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool = g_dpool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &g_helper_dsl;
    if (vkAllocateDescriptorSets(g_dev, &ai, &set) == VK_SUCCESS) {
        VkDescriptorImageInfo ii{VK_NULL_HANDLE, src, VK_IMAGE_LAYOUT_GENERAL};
        VkDescriptorImageInfo si{smp, VK_NULL_HANDLE, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet w[2] = {{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}, {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}};
        w[0].dstSet = set;
        w[0].dstBinding = 0;
        w[0].descriptorCount = 1;
        w[0].descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
        w[0].pImageInfo = &ii;
        w[1].dstSet = set;
        w[1].dstBinding = 1;
        w[1].descriptorCount = 1;
        w[1].descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER;
        w[1].pImageInfo = &si;
        vkUpdateDescriptorSets(g_dev, 2, w, 0, nullptr);
        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_helper_layout, 0, 1, &set, 0, nullptr);
        vkCmdDraw(cb, 3, 1, 0, 0);
    }
    vkCmdEndRendering(cb);
}

/* One pass per contiguous run of draws on the same attachments; clears
 * immediately ahead fold into load ops (mirrors eng_encode_records). */
/* ------------------------------------------------------------------ frame capture
 * The game screen's CAP button asks for one frame to be captured: every render
 * pass is described in the log (targets, formats, clears, draws, textures it
 * reads) and shrunk to a thumbnail, and every texture the frame reads is
 * thumbnailed too. The thumbnails go to two PNG contact sheets in the game
 * folder, so a dark or grey picture can be traced to the pass that goes wrong. */
constexpr u32 CAP_TW = 192, CAP_TH = 108;
constexpr u32 CAP_PASS_TILES = 160, CAP_TEX_TILES = 96;
constexpr u32 CAP_TILES = CAP_PASS_TILES + CAP_TEX_TILES;
struct Capture {
    bool active = false;
    unsigned req = 0;
    u32 passes = 0, pass_tiles = 0, tex_tiles = 0;
    std::vector<std::string> lines;
    std::vector<int> line_tile;       /* tile shown by each line, -1 = none */
    std::vector<u32> tex_seen;
};
Capture g_cap;
Buffer g_cap_buf;
Obj g_cap_thumb;
bool g_cap_ready;

const char* fmt_name(VkFormat f)
{
    switch (f) {
    case VK_FORMAT_R8G8B8A8_UNORM: return "RGBA8";
    case VK_FORMAT_B8G8R8A8_UNORM: return "BGRA8";
    case VK_FORMAT_R8G8B8A8_SRGB: return "RGBA8_sRGB";
    case VK_FORMAT_R16G16B16A16_SFLOAT: return "RGBA16F";
    case VK_FORMAT_R32G32B32A32_SFLOAT: return "RGBA32F";
    case VK_FORMAT_R16G16_SFLOAT: return "RG16F";
    case VK_FORMAT_R32_SFLOAT: return "R32F";
    case VK_FORMAT_R16_SFLOAT: return "R16F";
    case VK_FORMAT_R8_UNORM: return "R8";
    case VK_FORMAT_R8G8_UNORM: return "RG8";
    case VK_FORMAT_R5G6B5_UNORM_PACK16: return "RGB565";
    case VK_FORMAT_A1R5G5B5_UNORM_PACK16: return "ARGB1555";
    case VK_FORMAT_R4G4B4A4_UNORM_PACK16: return "RGBA4";
    case VK_FORMAT_A2B10G10R10_UNORM_PACK32: return "RGB10A2";
    case VK_FORMAT_B10G11R11_UFLOAT_PACK32: return "R11G11B10F";
    case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: return "BC1";
    case VK_FORMAT_BC2_UNORM_BLOCK: return "BC2";
    case VK_FORMAT_BC3_UNORM_BLOCK: return "BC3";
    case VK_FORMAT_D24_UNORM_S8_UINT: return "D24S8";
    case VK_FORMAT_D32_SFLOAT_S8_UINT: return "D32FS8";
    case VK_FORMAT_D32_SFLOAT: return "D32F";
    case VK_FORMAT_D16_UNORM: return "D16";
    default: return nullptr;
    }
}

std::string obj_desc(u32 h)
{
    char b[160];
    Obj* o = obj(h);
    if (!o) { snprintf(b, sizeof b, "h%u(gone)", h); return b; }
    const char* fn = fmt_name(o->fmt);
    char fbuf[24];
    if (!fn) { snprintf(fbuf, sizeof fbuf, "vk%d", (int)o->fmt); fn = fbuf; }
    snprintf(b, sizeof b, "h%u %ux%u %s%s%s(rsx fmt %d)", h, o->w, o->h, fn, o->cube ? " cube" : "",
             o->bc_decode ? " cpu-decoded " : " ", (int)o->bfmt);
    if (o->parent) {
        const size_t n = strlen(b);
        if (n > 0 && n < sizeof b - 24) snprintf(b + n - 1, sizeof b - n + 1, ", view of h%u)", o->parent);
    }
    return b;
}

bool cap_init()
{
    if (g_cap_ready) return true;
    if (!buffer_create(g_cap_buf, (VkDeviceSize)CAP_TILES * CAP_TW * CAP_TH * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT))
        return false;
    if (!image_create(g_cap_thumb, VK_FORMAT_R8G8B8A8_UNORM, CAP_TW, CAP_TH, 1, 1, false,
                      VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
        buffer_destroy(g_cap_buf);
        return false;
    }
    g_cap_thumb.att = make_view(g_cap_thumb.img, g_cap_thumb.fmt, VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_ASPECT_COLOR_BIT,
                                1, 1, IDENTITY);
    VkCommandBuffer cb = imm_begin();
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = g_cap_thumb.img;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr,
                         0, nullptr, 1, &b);
    imm_end();
    memset(g_cap_buf.map, 0, (size_t)CAP_TILES * CAP_TW * CAP_TH * 4);
    g_cap_ready = true;
    return true;
}

/* Shrink `src` into tile `tile` of the capture buffer (outside a render pass). */
void cap_thumb(VkCommandBuffer cb, VkImageView src, u32 tile)
{
    helper_pass(cb, g_blit_pipe, src, g_default_sampler, g_cap_thumb.att, CAP_TW, CAP_TH);
    barrier_all(cb);
    VkBufferImageCopy c{};
    c.bufferOffset = (VkDeviceSize)tile * CAP_TW * CAP_TH * 4;
    c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    c.imageExtent = {CAP_TW, CAP_TH, 1};
    vkCmdCopyImageToBuffer(cb, g_cap_thumb.img, VK_IMAGE_LAYOUT_GENERAL, g_cap_buf.buf, 1, &c);
    barrier_all(cb);
}

void cap_line(const std::string& l, int tile)
{
    g_cap.lines.push_back(l);
    g_cap.line_tile.push_back(tile);
}

/* Describe one pass and thumbnail its first colour target and any new textures. */
void cap_pass(VkCommandBuffer cb, const u32* rt, u32 nrt, u32 depth, bool fallback_z, u32 n_cc,
              const u32* cc_surf, const float (*cc_rgba)[4], bool depth_cleared, u32 d0, u32 d1, u32 drawn,
              bool skipped_all)
{
    const u32 pass = g_cap.passes++;
    int tile = -1;
    Obj* t0 = nrt ? obj(rt[0]) : nullptr;
    if (t0 && t0->view && !t0->depth && g_cap.pass_tiles < CAP_PASS_TILES) {
        tile = (int)g_cap.pass_tiles++;
        cap_thumb(cb, t0->view, (u32)tile);
    }
    std::string l;
    char b[256];
    snprintf(b, sizeof b, "pass %u", pass);
    l = b;
    if (tile >= 0) { snprintf(b, sizeof b, " [tile %d]", tile); l += b; }
    l += ":";
    if (!nrt) l += " depth-only";
    for (u32 k = 0; k < nrt; k++) {
        l += " colour " + obj_desc(rt[k]);
        for (u32 c = 0; c < n_cc; c++)
            if (cc_surf[c] == rt[k]) {
                snprintf(b, sizeof b, " cleared(%.2f %.2f %.2f %.2f)", cc_rgba[c][0], cc_rgba[c][1], cc_rgba[c][2],
                         cc_rgba[c][3]);
                l += b;
            }
        l += ";";
    }
    if (depth) l += " depth " + obj_desc(depth) + (depth_cleared ? " cleared;" : ";");
    else if (fallback_z) l += " depth (stand-in);";
    snprintf(b, sizeof b, " %u draw(s), %u drawn%s", d1 - d0, drawn, skipped_all ? " (ALL SKIPPED: attachments)" : "");
    l += b;
    /* programs and textures */
    std::vector<u32> pipes, texs;
    float vp[4] = {0, 0, 0, 0};
    for (u32 i = d0; i < d1; i++) {
        const Rec& r = g_rec[i];
        if (i == d0) memcpy(vp, r.vp, sizeof vp);
        bool seen = false;
        for (u32 x : pipes) seen |= x == r.pipeline;
        if (!seen) pipes.push_back(r.pipeline);
        for (u32 k = 0; k < RSX_BE_MAX_TEXTURES + RSX_BE_MAX_VERTEX_TEXTURES; k++) {
            const u32 h = k < RSX_BE_MAX_TEXTURES ? r.tex[k] : r.vtex[k - RSX_BE_MAX_TEXTURES];
            if (!h) continue;
            bool s2 = false;
            for (u32 x : texs) s2 |= x == h;
            if (!s2) texs.push_back(h);
        }
    }
    if (d1 > d0) {
        snprintf(b, sizeof b, " | viewport %.0f,%.0f %.0fx%.0f | pipelines", vp[0], vp[1], vp[2], vp[3]);
        l += b;
        for (size_t k = 0; k < pipes.size() && k < 10; k++) {
            snprintf(b, sizeof b, " %u", pipes[k]);
            l += b;
        }
        if (pipes.size() > 10) l += " ...";
        /* which game code issued these draws */
        int tags[4];
        u32 tagn[4], ntag = 0, other = 0;
        for (u32 i = d0; i < d1; i++) {
            const int t = g_rec[i].tag;
            u32 k = 0;
            while (k < ntag && tags[k] != t) k++;
            if (k < ntag) tagn[k]++;
            else if (ntag < 4) { tags[ntag] = t; tagn[ntag++] = 1; }
            else other++;
        }
        l += " | game code";
        for (u32 k = 0; k < ntag; k++) {
            char nb[96];
            snprintf(b, sizeof b, "%s %s x%u", k ? ";" : "", eu_fifo_tag_name(tags[k], nb, sizeof nb), tagn[k]);
            l += b;
        }
        if (other) { snprintf(b, sizeof b, "; +%u more", other); l += b; }
    }
    if (!texs.empty()) {
        l += " | reads";
        for (size_t k = 0; k < texs.size(); k++) {
            u32 h = texs[k];
            bool seen = false;
            for (u32 x : g_cap.tex_seen) seen |= x == h;
            int tt = -1;
            Obj* o = obj(h);
            if (!seen) {
                g_cap.tex_seen.push_back(h);
                if (o && o->view && !o->cube && !o->depth && g_cap.tex_tiles < CAP_TEX_TILES) {
                    tt = (int)(CAP_PASS_TILES + g_cap.tex_tiles++);
                    cap_thumb(cb, o->view, (u32)tt);
                }
            }
            if (k < 12) {
                l += " " + obj_desc(h);
                if (tt >= 0) { snprintf(b, sizeof b, " [tex tile %d]", tt - (int)CAP_PASS_TILES); l += b; }
                l += ",";
            }
        }
        if (texs.size() > 12) { snprintf(b, sizeof b, " +%zu more", texs.size() - 12); l += b; }
    }
    cap_line(l, tile);
}

bool write_png(const char* path, const uint8_t* rgb, u32 w, u32 h)
{
    std::vector<uint8_t> raw((size_t)(w * 3 + 1) * h);
    for (u32 y = 0; y < h; y++) {
        raw[(size_t)y * (w * 3 + 1)] = 0;
        memcpy(&raw[(size_t)y * (w * 3 + 1) + 1], rgb + (size_t)y * w * 3, (size_t)w * 3);
    }
    uLongf zlen = compressBound((uLong)raw.size());
    std::vector<uint8_t> z(zlen);
    if (compress2(z.data(), &zlen, raw.data(), (uLong)raw.size(), 6) != Z_OK) return false;
    FILE* f = fopen(path, "wb");
    if (!f) return false;
    auto be32 = [](uint8_t* p, uint32_t v) { p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v; };
    auto chunk = [&](const char* type, const uint8_t* data, uint32_t len) {
        uint8_t hd[8];
        be32(hd, len);
        memcpy(hd + 4, type, 4);
        fwrite(hd, 1, 8, f);
        if (len) fwrite(data, 1, len, f);
        uLong crc = crc32(0, (const Bytef*)type, 4);
        if (len) crc = crc32(crc, data, len);
        uint8_t c[4];
        be32(c, (uint32_t)crc);
        fwrite(c, 1, 4, f);
    };
    static const uint8_t sig[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    fwrite(sig, 1, 8, f);
    uint8_t ihdr[13];
    be32(ihdr, w);
    be32(ihdr + 4, h);
    ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
    chunk("IHDR", ihdr, 13);
    chunk("IDAT", z.data(), (uint32_t)zlen);
    chunk("IEND", nullptr, 0);
    const bool ok = ferror(f) == 0;
    fclose(f);
    return ok;
}

/* Lay tiles [first, first+count) out as a grid with 2-pixel gaps. */
bool write_sheet(const char* path, u32 first, u32 count, u32 cols)
{
    if (!count) return true;
    const u32 rows = (count + cols - 1) / cols, gap = 2;
    const u32 W = cols * (CAP_TW + gap), H = rows * (CAP_TH + gap);
    std::vector<uint8_t> img((size_t)W * H * 3, 40);
    const uint8_t* src = (const uint8_t*)g_cap_buf.map;
    for (u32 t = 0; t < count; t++) {
        const u32 ox = (t % cols) * (CAP_TW + gap), oy = (t / cols) * (CAP_TH + gap);
        const uint8_t* tp = src + (size_t)(first + t) * CAP_TW * CAP_TH * 4;
        for (u32 y = 0; y < CAP_TH; y++)
            for (u32 x = 0; x < CAP_TW; x++) {
                const uint8_t* s4 = tp + ((size_t)y * CAP_TW + x) * 4;
                uint8_t* d = &img[((size_t)(oy + y) * W + ox + x) * 3];
                d[0] = s4[0]; d[1] = s4[1]; d[2] = s4[2];
            }
    }
    return write_png(path, img.data(), W, H);
}

void cap_tile_stats(u32 tile, float* avg, u32* maxc)
{
    const uint8_t* tp = (const uint8_t*)g_cap_buf.map + (size_t)tile * CAP_TW * CAP_TH * 4;
    double sum[3] = {0, 0, 0};
    *maxc = 0;
    for (u32 i = 0; i < CAP_TW * CAP_TH; i++)
        for (int c = 0; c < 3; c++) {
            sum[c] += tp[i * 4 + c];
            if (tp[i * 4 + c] > *maxc) *maxc = tp[i * 4 + c];
        }
    for (int c = 0; c < 3; c++) avg[c] = (float)(sum[c] / (CAP_TW * CAP_TH));
}

void wait_all_slots();
void cap_finish(u32 present_surface)
{
    wait_all_slots();
    const char* root = getenv("PS3_VFS_ROOT");
    std::string base = std::string(root && *root ? root : ".") + "/eu_capture_" + std::to_string(g_cap.req);
    fprintf(stderr, "[capture] ===== frame capture %u: %u pass(es), %zu texture(s) read, presented %s =====\n",
            g_cap.req, g_cap.passes, g_cap.tex_seen.size(), obj_desc(present_surface).c_str());
    std::string report;
    for (size_t i = 0; i < g_cap.lines.size(); i++) {
        std::string l = g_cap.lines[i];
        if (g_cap.line_tile[i] >= 0) {
            float a[3];
            u32 mx;
            cap_tile_stats((u32)g_cap.line_tile[i], a, &mx);
            char b[96];
            snprintf(b, sizeof b, " || picture avg rgb %.0f %.0f %.0f, brightest %u", a[0], a[1], a[2], mx);
            l += b;
        }
        fprintf(stderr, "[capture] %s\n", l.c_str());
        report += l + "\n";
    }
    for (u32 t = 0; t < g_cap.tex_tiles; t++) {
        float a[3];
        u32 mx;
        cap_tile_stats(CAP_PASS_TILES + t, a, &mx);
        char b[128];
        snprintf(b, sizeof b, "texture tile %u: avg rgb %.0f %.0f %.0f, brightest %u", t, a[0], a[1], a[2], mx);
        fprintf(stderr, "[capture] %s\n", b);
        report += std::string(b) + "\n";
    }
    FILE* f = fopen((base + "_report.txt").c_str(), "w");
    if (f) { fputs(report.c_str(), f); fclose(f); }
    const bool p1 = write_sheet((base + "_passes.png").c_str(), 0, g_cap.pass_tiles, 10);
    const bool p2 = write_sheet((base + "_textures.png").c_str(), CAP_PASS_TILES, g_cap.tex_tiles, 8);
    fprintf(stderr, "[capture] files: %s_report.txt%s, %s_passes.png%s, %s_textures.png%s\n", base.c_str(),
            f ? "" : " (FAILED)", base.c_str(), p1 ? "" : " (FAILED)", base.c_str(), p2 ? "" : " (FAILED)");
    if (eu_bridge_capture_done) eu_bridge_capture_done(g_cap.req);
    g_cap = Capture{};
}

void encode_records(VkCommandBuffer cb)
{
    u32 i = 0;
    while (i < g_rec_count) {
        if (g_rec[i].kind == REC_DEPTH_PACK) {
            const Rec& r = g_rec[i++];
            Obj* src = obj(r.depth);
            Obj* dst = obj(r.resolve_dst);
            if (!src || !dst || !src->view || !dst->att || !g_depthpack_pipe) continue;
            barrier_all(cb);
            helper_pass(cb, g_depthpack_pipe, src->view, g_point_sampler, dst->att, dst->w, dst->h);
            if (g_cap.active) {
                char b[64];
                snprintf(b, sizeof b, "pass %u: depth packed as colour ", g_cap.passes++);
                cap_line(std::string(b) + obj_desc(r.depth) + " -> " + obj_desc(r.resolve_dst), -1);
            }
            continue;
        }
        if (g_rec[i].kind == REC_COPY) {
            const Rec& r = g_rec[i++];
            Obj* src = obj(r.depth);
            Obj* dst = obj(r.resolve_dst);
            if (!src || !dst || !src->img || !dst->img) continue;
            const u32* c = r.copy_rect;
            VkImageBlit b{};
            b.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            b.dstSubresource = b.srcSubresource;
            b.srcOffsets[0] = {(int32_t)c[0], (int32_t)c[1], 0};
            b.srcOffsets[1] = {(int32_t)(c[0] + c[2]), (int32_t)(c[1] + c[3]), 1};
            b.dstOffsets[0] = {(int32_t)c[4], (int32_t)c[5], 0};
            b.dstOffsets[1] = {(int32_t)(c[4] + c[6]), (int32_t)(c[5] + c[7]), 1};
            const bool scaled = c[2] != c[6] || c[3] != c[7];
            init_layouts(cb);   /* the destination may be brand new */
            barrier_all(cb);
            vkCmdBlitImage(cb, src->img, VK_IMAGE_LAYOUT_GENERAL, dst->img, VK_IMAGE_LAYOUT_GENERAL, 1, &b,
                           scaled ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            barrier_all(cb);
            if (g_cap.active) {
                char b2[128];
                snprintf(b2, sizeof b2, "pass %u: copy %ux%u at %u,%u -> %ux%u at %u,%u: ", g_cap.passes++, c[2], c[3],
                         c[0], c[1], c[6], c[7], c[4], c[5]);
                cap_line(std::string(b2) + obj_desc(r.depth) + " -> " + obj_desc(r.resolve_dst), -1);
            }
            continue;
        }
        if (g_rec[i].kind == REC_DEPTH_RESOLVE) {
            const Rec& r = g_rec[i++];
            Obj* src = obj(r.depth);
            Obj* dst = obj(r.resolve_dst);
            if (!src || !dst || !src->view || !dst->att || !g_depth_pipe) continue;
            /* The zeta may have been an attachment a moment ago, even in the
             * same guest pass (a game sampling its bound depth buffer). */
            barrier_all(cb);
            helper_pass(cb, g_depth_pipe, src->view, g_point_sampler, dst->att, dst->w, dst->h);
            if (g_cap.active) {
                int tile = -1;
                if (g_cap.pass_tiles < CAP_PASS_TILES) {
                    barrier_all(cb);
                    tile = (int)g_cap.pass_tiles++;
                    cap_thumb(cb, dst->view, (u32)tile);
                }
                char b[64];
                snprintf(b, sizeof b, "pass %u [tile %d]: depth copy ", g_cap.passes++, tile);
                cap_line(std::string(b) + obj_desc(r.depth) + " -> " + obj_desc(r.resolve_dst), tile);
            }
            continue;
        }
        u32 n_cc = 0;
        u32 cc_surf[RSX_BE_MAX_COLOR_TARGETS];
        float cc_rgba[RSX_BE_MAX_COLOR_TARGETS][4];
        bool have_ds = false;
        u32 cd = 0, ds_flags = 0;
        float cdepth = 1.0f;
        u8 cstencil = 0;
        while (i < g_rec_count && g_rec[i].kind != REC_DRAW && g_rec[i].kind != REC_DEPTH_RESOLVE &&
               g_rec[i].kind != REC_DEPTH_PACK && g_rec[i].kind != REC_COPY) {
            const Rec& r = g_rec[i];
            if (r.kind == REC_CLEAR_COLOR) {
                u32 k = 0;
                while (k < n_cc && cc_surf[k] != r.rt[0]) k++;
                if (k == n_cc) {
                    if (n_cc == RSX_BE_MAX_COLOR_TARGETS) break;
                    cc_surf[n_cc++] = r.rt[0];
                }
                memcpy(cc_rgba[k], r.clear_rgba, sizeof cc_rgba[k]);
            } else {
                if (have_ds && r.depth != cd) break;
                cd = r.depth;
                ds_flags |= r.clear_flags;
                cdepth = r.clear_depth;
                cstencil = r.clear_stencil;
                have_ds = true;
            }
            i++;
        }
        u32 rt[RSX_BE_MAX_COLOR_TARGETS];
        u32 nrt = n_cc;
        for (u32 k = 0; k < n_cc; k++) rt[k] = cc_surf[k];
        u32 depth = have_ds ? cd : 0;
        if (i < g_rec_count && g_rec[i].kind == REC_DRAW && (!have_ds || g_rec[i].depth == cd)) {
            const Rec& d = g_rec[i];
            bool covered = true;
            for (u32 k = 0; k < n_cc && covered; k++) {
                bool found = false;
                for (u32 j = 0; j < d.nrt; j++) if (d.rt[j] == cc_surf[k]) found = true;
                covered = found;
            }
            if (covered) {
                nrt = d.nrt;
                for (u32 k = 0; k < nrt; k++) rt[k] = d.rt[k];
                depth = d.depth;
            }
        }
        Obj* tex[RSX_BE_MAX_COLOR_TARGETS];
        u32 attach = 0;
        for (u32 k = 0; k < nrt; k++) {
            Obj* t = obj(rt[k]);
            if (!t || !t->att) break;
            if (attach && (t->w != tex[0]->w || t->h != tex[0]->h)) break;
            tex[attach++] = t;
        }
        Obj* z = obj(depth);
        if (!attach && !z) {
            if (i < g_rec_count && g_rec[i].kind == REC_DRAW) i++;
            continue;
        }
        const u32 rw = attach ? tex[0]->w : z->w, rh = attach ? tex[0]->h : z->h;
        /* Pipelines always declare a depth attachment, so a draw pass needs one. */
        bool fallback_z = false;
        if (attach && (!z || z->w < rw || z->h < rh)) {
            z = obj(fallback_depth(rw, rh));
            fallback_z = true;
        }
        init_layouts(cb);   /* the fallback depth may be brand new */

        VkRenderingAttachmentInfo ca[RSX_BE_MAX_COLOR_TARGETS];
        for (u32 k = 0; k < attach; k++) {
            ca[k] = VkRenderingAttachmentInfo{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
            ca[k].imageView = tex[k]->att;
            ca[k].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            ca[k].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            int cleared = -1;
            for (u32 c = 0; c < n_cc; c++) if (cc_surf[c] == rt[k]) cleared = (int)c;
            if (cleared >= 0) {
                ca[k].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
                memcpy(ca[k].clearValue.color.float32, cc_rgba[cleared], sizeof(float) * 4);
            } else {
                ca[k].loadOp = VK_ATTACHMENT_LOAD_OP_LOAD;
            }
        }
        VkRenderingAttachmentInfo da{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
        VkRenderingAttachmentInfo sa{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
        const bool clear_here = have_ds && depth == cd && !fallback_z;
        if (z) {
            da.imageView = z->att;
            da.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            da.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            da.loadOp = (clear_here && (ds_flags & RSX_BE_CLEAR_DEPTH)) ? VK_ATTACHMENT_LOAD_OP_CLEAR
                                                                         : VK_ATTACHMENT_LOAD_OP_LOAD;
            da.clearValue.depthStencil = {cdepth, cstencil};
            sa = da;
            sa.loadOp = (clear_here && (ds_flags & RSX_BE_CLEAR_STENCIL)) ? VK_ATTACHMENT_LOAD_OP_CLEAR
                                                                           : VK_ATTACHMENT_LOAD_OP_LOAD;
        }
        VkRenderingInfo ri{VK_STRUCTURE_TYPE_RENDERING_INFO};
        ri.renderArea = {{0, 0}, {rw, rh}};
        ri.layerCount = 1;
        ri.colorAttachmentCount = attach;
        ri.pColorAttachments = ca;
        ri.pDepthAttachment = z ? &da : nullptr;
        ri.pStencilAttachment = (z && (full_aspect(*z) & VK_IMAGE_ASPECT_STENCIL_BIT)) ? &sa : nullptr;

        barrier_all(cb);
        vkCmdBeginRendering(cb, &ri);
        if (attach != nrt) {
            /* Could not bind every attachment the draws name: take the clears,
             * skip those draws (as the Metal engine does). */
            vkCmdEndRendering(cb);
            const u32 s0 = i;
            while (i < g_rec_count && g_rec[i].kind == REC_DRAW && targets_are(g_rec[i], rt, nrt)) i++;
            if (g_cap.active)
                cap_pass(cb, rt, nrt, depth, fallback_z, n_cc, cc_surf, cc_rgba, clear_here, s0, i, 0, true);
            continue;
        }
        const u32 d0 = i;
        u32 drawn = 0;
        while (i < g_rec_count && g_rec[i].kind == REC_DRAW && targets_are(g_rec[i], rt, nrt) &&
               (g_rec[i].depth == depth || (!g_rec[i].depth && fallback_z))) {
            const Rec& r = g_rec[i++];
            if (!r.pipeline || r.pipeline > g_pipe_count || !g_pipe[r.pipeline - 1].live) continue;
            Pipe& p = g_pipe[r.pipeline - 1];
            const bool donly = (attach == 0);
            if (!donly && p.rt_count != attach) { g_skipped_draws++; continue; }
            const int cls = topo_class(r.topo);
            if (!p.variant[cls][donly] && !p.failed[cls][donly]) {
                p.variant[cls][donly] = build_variant(p, cls, donly);
                p.failed[cls][donly] = !p.variant[cls][donly];
            }
            if (!p.variant[cls][donly]) continue;
            bind_draw(cb, r, p, p.variant[cls][donly], rw, rh);
            drawn++;
        }
        vkCmdEndRendering(cb);
        if (g_cap.active)
            cap_pass(cb, rt, attach ? nrt : 0, depth, fallback_z, n_cc, cc_surf, cc_rgba, clear_here, d0, i, drawn,
                     false);
    }
}

uint64_t now_ms()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

/* Retire slot `k` if its GPU work is done (or wait for it): publish its
 * present copy and recycle its descriptor pool. False = still running. */
bool slot_finish(u32 k, bool wait)
{
    Slot& sl = g_slot[k];
    if (!sl.in_flight) return true;
    VkResult r;
    if (wait) {
        r = vkWaitForFences(g_dev, 1, &sl.fence, VK_TRUE, 5ull * 1000 * 1000 * 1000);
        if (r != VK_SUCCESS) {
            fprintf(stderr, "[RSX vulkan] GPU did not finish (result %d) -- device lost?\n", (int)r);
            if (r == VK_ERROR_DEVICE_LOST) g_active = false;
        }
    } else {
        r = vkGetFenceStatus(g_dev, sl.fence);
        if (r == VK_NOT_READY) return false;
    }
    sl.in_flight = false;
    if (sl.serial > g_completed) g_completed = sl.serial;
    vkResetDescriptorPool(g_dev, sl.dpool, 0);
    if (sl.publish && r == VK_SUCCESS)
        eu_bridge_frame_rgba((const uint8_t*)sl.readback.map, sl.pw, sl.ph, sl.pw * 4);
    sl.publish = false;
    return true;
}

void wait_all_slots()
{
    for (u32 k = 0; k < NUM_SLOTS; k++) slot_finish(k, true);
    collect_retired();
}

uint64_t g_gpu_wait_us;   /* time the CPU spent blocked on a busy slot */

/* Encode everything recorded, optionally scale `present_surface` into the
 * RGBA8 present image and read it back, then submit WITHOUT waiting: the
 * next submission goes to the other slot, and this one is collected (and its
 * picture published) once its fence signals. */
void encode_and_submit(u32 present_surface)
{
    if (!g_active) return;
    Obj* src = obj(present_surface);
    static uint64_t next_publish;
    const bool publish = src && src->view && eu_bridge_frame_rgba && now_ms() >= next_publish;

    /* the slot we are about to fill must be idle */
    if (g_slot[g_cur].in_flight) {
        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        slot_finish(g_cur, true);
        clock_gettime(CLOCK_MONOTONIC, &t1);
        g_gpu_wait_us += (uint64_t)(t1.tv_sec - t0.tv_sec) * 1000000u + (uint64_t)(t1.tv_nsec - t0.tv_nsec) / 1000u;
        if (!g_active) return;
    }

    if (g_stage_used && !buffer_ensure(g_stage_buf, (VkDeviceSize)g_stage_used + UBO_PAD,
                                       VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
                                           VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)) {
        fprintf(stderr, "[RSX vulkan] staging allocation failed (%u bytes)\n", g_stage_used);
        g_rec_count = 0;
        g_stage_used = 0;
        return;
    }
    if (g_stage_used) memcpy(g_stage_buf.map, g_stage, g_stage_used);

    vkResetCommandBuffer(g_cb, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(g_cb, &bi);
    init_layouts(g_cb);
    barrier_all(g_cb);   /* GPU work stays in order across slots; only the CPU overlaps */
    encode_records(g_cb);

    u32 pw = 0, ph = 0;
    if (publish) {
        pw = src->w < PRESENT_MAX_W ? src->w : PRESENT_MAX_W;
        ph = src->h < PRESENT_MAX_H ? src->h : PRESENT_MAX_H;
        helper_pass(g_cb, g_blit_pipe, src->view, g_default_sampler, g_present.att, pw, ph);
        barrier_all(g_cb);
        VkBufferImageCopy c{};
        c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        c.imageExtent = {pw, ph, 1};
        vkCmdCopyImageToBuffer(g_cb, g_present.img, VK_IMAGE_LAYOUT_GENERAL, g_readback_buf.buf, 1, &c);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(g_cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0,
                             nullptr, 0, nullptr);
    }
    vkEndCommandBuffer(g_cb);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &g_cb;
    vkResetFences(g_dev, 1, &g_fence);
    VkResult qr = vkQueueSubmit(g_queue, 1, &si, g_fence);
    Slot& sl = g_slot[g_cur];
    if (qr == VK_SUCCESS) {
        sl.in_flight = true;
        sl.serial = ++g_serial;
        sl.publish = publish;
        sl.pw = pw;
        sl.ph = ph;
        if (publish) next_publish = now_ms() + 33;
    } else {
        fprintf(stderr, "[RSX vulkan] queue submit failed (%d)\n", (int)qr);
        if (qr == VK_ERROR_DEVICE_LOST) g_active = false;
        vkResetDescriptorPool(g_dev, sl.dpool, 0);
    }
    g_rec_count = 0;
    g_stage_used = 0;
    g_cur = (g_cur + 1) % NUM_SLOTS;
    for (u32 k = 0; k < NUM_SLOTS; k++) slot_finish(k, false);   /* publish whatever is done */
    collect_retired();
}

/* ------------------------------------------------------------------ backend vtable */
int be_init(void*, u32, u32) { return 0; }
void be_shutdown(void*) {}
void be_submit_and_wait(void*, u32) { encode_and_submit(0); }

u32 be_texture_create(void*, rsx_be_format fmt, u32 w, u32 h, u32 mips, u32 faces, u32 remap, u32 rsx_fmt)
{
    if (!w || !h) return 0;
    Obj o;
    o.bfmt = fmt;
    o.bc_decode = is_bc(fmt) && !g_bc_ok;
    const bool cube = (faces == 6);
    if (!image_create(o, vk_format(fmt), w, h, mips ? mips : 1, cube ? 6 : 1, cube,
                      VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)) return 0;
    o.view = make_view(o.img, o.fmt, cube ? VK_IMAGE_VIEW_TYPE_CUBE : VK_IMAGE_VIEW_TYPE_2D,
                       VK_IMAGE_ASPECT_COLOR_BIT, o.mips, o.layers, remap_mapping(remap, rsx_fmt));
    const u32 hnd = obj_add(o);
    if (!hnd) obj_destroy_now(o);
    return hnd;
}

void be_texture_upload(void*, u32 handle, u32 face, u32 mip, u32 w, u32 h, const void* src, u32 row_bytes,
                       u32 rows)
{
    Obj* o = obj(handle);
    if (!o || !src || !row_bytes || !w || !h) return;
    if (o->bc_decode) {
        std::vector<u8> rgba((size_t)w * h * 4);
        bc_decode(o->bfmt, (const u8*)src, row_bytes, w, h, rgba.data());
        upload_now(*o, face, mip, w, h, rgba.data(), w * 4, h);
    } else {
        upload_now(*o, face, mip, w, h, src, row_bytes, rows ? rows : h);
    }
}

void be_obj_release(void*, u32 handle)
{
    Obj* o = obj(handle);
    if (!o || o->retired) return;
    o->retired = true;
    o->retire_after = g_serial + 1;   /* pending records go out in the next submission */
    for (u32 i = 0; i < g_view_count; i++)
        if (g_views[i].surface == handle) {
            be_obj_release(nullptr, g_views[i].view);
            g_views[i] = g_views[--g_view_count];
            i--;
        }
    collect_retired();
}

u32 be_color_target_create(void*, rsx_be_format fmt, u32 w, u32 h, const void* seed, u32 seed_row_bytes)
{
    if (!w || !h) return 0;
    Obj o;
    o.bfmt = fmt;
    if (!image_create(o, vk_format(fmt), w, h, 1, 1, false,
                      VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                          VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)) return 0;
    o.view = make_view(o.img, o.fmt, VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_ASPECT_COLOR_BIT, 1, 1, IDENTITY);
    o.att = make_view(o.img, o.fmt, VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_ASPECT_COLOR_BIT, 1, 1, IDENTITY);
    const u32 hnd = obj_add(o);
    if (!hnd) {
        obj_destroy_now(o);
        return 0;
    }
    if (seed && seed_row_bytes) upload_now(g_obj[hnd - 1], 0, 0, w, h, seed, seed_row_bytes, h);
    return hnd;
}

u32 be_surface_view(void*, u32 surface, u32 remap, u32 rsx_format)
{
    Obj* s = obj(surface);
    if (!s || s->depth) return 0;
    for (u32 i = 0; i < g_view_count; i++)
        if (g_views[i].surface == surface && g_views[i].remap == remap && g_views[i].format == rsx_format)
            return g_views[i].view;
    if (g_view_count >= MAX_VIEWS) return 0;
    Obj v;
    v.owns = false;
    v.img = s->img;
    v.fmt = s->fmt;
    v.bfmt = s->bfmt;
    v.w = s->w;
    v.h = s->h;
    v.parent = surface;
    v.initialised = true;
    v.view = make_view(s->img, s->fmt, VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_ASPECT_COLOR_BIT, 1, 1,
                       remap_mapping(remap, rsx_format));
    const u32 hnd = obj_add(v);
    if (!hnd) {
        obj_destroy_now(v);
        return 0;
    }
    g_views[g_view_count++] = {surface, remap, rsx_format, hnd};
    return hnd;
}

u32 depth_create(u32 w, u32 h)
{
    if (!w || !h) return 0;
    Obj o;
    o.depth = true;
    if (!image_create(o, g_depth_fmt, w, h, 1, 1, false,
                      VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)) return 0;
    o.att = make_view(o.img, o.fmt, VK_IMAGE_VIEW_TYPE_2D, full_aspect(o), 1, 1, IDENTITY);
    o.view = make_view(o.img, o.fmt, VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_ASPECT_DEPTH_BIT, 1, 1, IDENTITY);
    const u32 hnd = obj_add(o);
    if (!hnd) obj_destroy_now(o);
    return hnd;
}

u32 be_depth_target_create(void*, u32 w, u32 h) { return depth_create(w, h); }

u32 be_depth_snapshot(void*, u32 depth, u32 w, u32 h)
{
    if (!obj(depth) || !g_depth_pipe) return 0;
    rec_room();
    const u32 dst = be_color_target_create(nullptr, RSX_BE_FMT_R32F, w, h, nullptr, 0);
    if (!dst) return 0;
    Rec& r = g_rec[g_rec_count++];
    memset(&r, 0, sizeof r);
    r.kind = REC_DEPTH_RESOLVE;
    r.depth = depth;
    r.resolve_dst = dst;
    return dst;
}

/* The engine (Android patch) asks for a depth snapshot packed into an RGBA8
 * colour target when the game samples its depth buffer through a colour
 * texture format. Returns a colour target handle (0 = unavailable). */
extern "C" u32 eu_vk_depth_pack(u32 snapshot, u32 w, u32 h)
{
    if (!g_active || !obj(snapshot) || !g_depthpack_pipe) return 0;
    rec_room();
    const u32 dst = be_color_target_create(nullptr, RSX_BE_FMT_R8G8B8A8, w, h, nullptr, 0);
    if (!dst) return 0;
    rec_room();
    Rec& r = g_rec[g_rec_count++];
    memset(&r, 0, sizeof r);
    r.kind = REC_DEPTH_PACK;
    r.depth = snapshot;
    r.resolve_dst = dst;
    return dst;
}

/* The engine (Android patch) mirrors an NV3089 blit whose source is a live
 * render target: the game copies its lit scene into another buffer and samples
 * that, and the guest-memory copy alone is empty because the scene exists only
 * on the GPU. Same-format RGBA8 copies only (blit + linear filter are
 * guaranteed for it). Returns 1 when recorded. */
extern "C" int eu_vk_copy_surface(u32 src, u32 dst, u32 sx, u32 sy, u32 sw, u32 sh, u32 dx, u32 dy, u32 dw,
                                  u32 dh)
{
    if (!g_active || src == dst) return 0;
    Obj* s = obj(src);
    Obj* d = obj(dst);
    if (!s || !d || s->depth || d->depth || s->parent || d->parent || s->cube || d->cube) return 0;
    if (s->fmt != VK_FORMAT_R8G8B8A8_UNORM || d->fmt != VK_FORMAT_R8G8B8A8_UNORM) return 0;
    if (!sw || !sh || !dw || !dh || sx >= s->w || sy >= s->h || dx >= d->w || dy >= d->h) return 0;
    if (sx + sw > s->w) sw = s->w - sx;
    if (sy + sh > s->h) sh = s->h - sy;
    if (dx + dw > d->w) dw = d->w - dx;
    if (dy + dh > d->h) dh = d->h - dy;
    rec_room();
    Rec& r = g_rec[g_rec_count++];
    memset(&r, 0, sizeof r);
    r.kind = REC_COPY;
    r.depth = src;
    r.resolve_dst = dst;
    const u32 c[8] = {sx, sy, sw, sh, dx, dy, dw, dh};
    memcpy(r.copy_rect, c, sizeof c);
    return 1;
}

u32 be_pipeline_create(void*, const char* vs_hlsl, const char* ps_hlsl, const rsx_be_render_state* rs,
                       const rsx_vertex_layout_plan* layout, u32 vertex_stride, rsx_be_format rt_fmt,
                       u32 rt_count)
{
    if (!vs_hlsl || !ps_hlsl || !vertex_stride || g_pipe_count >= MAX_PIPES) return 0;
    if (!rt_count) rt_count = 1;
    if (rt_count > RSX_BE_MAX_COLOR_TARGETS) rt_count = RSX_BE_MAX_COLOR_TARGETS;
    const Func* vs = function_for(vs_hlsl, true);
    if (!vs) return 0;
    const Func* fs = function_for(ps_hlsl, false);
    if (!fs) return 0;

    Pipe p;
    p.live = true;
    p.vs = vs->mod;
    p.fs = fs->mod;
    p.rs = *rs;
    p.attr_count = layout ? layout->count : 0;
    p.stride = vertex_stride;
    p.rt_count = rt_count;
    p.rt_fmt = vk_format(rt_fmt);
    /* Union of both stages' reflected resources, by binding. */
    for (const auto* list : {&vs->refl, &fs->refl}) {
        for (const ResBinding& b : *list) {
            bool merged = false;
            for (ResBinding& e : p.bindings)
                if (e.binding == b.binding) {
                    e.stages |= b.stages;
                    if (b.count > e.count) e.count = b.count;
                    if (b.ubo_size > e.ubo_size) e.ubo_size = b.ubo_size;
                    merged = true;
                }
            if (!merged) p.bindings.push_back(b);
        }
    }
    std::vector<VkDescriptorSetLayoutBinding> lb;
    for (const ResBinding& b : p.bindings)
        lb.push_back({(u32)b.binding, b.type, b.count, b.stages, nullptr});
    VkDescriptorSetLayoutCreateInfo dci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dci.bindingCount = (u32)lb.size();
    dci.pBindings = lb.data();
    if (vkCreateDescriptorSetLayout(g_dev, &dci, nullptr, &p.dsl) != VK_SUCCESS) return 0;
    VkPipelineLayoutCreateInfo lci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    lci.setLayoutCount = 1;
    lci.pSetLayouts = &p.dsl;
    if (vkCreatePipelineLayout(g_dev, &lci, nullptr, &p.layout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(g_dev, p.dsl, nullptr);
        return 0;
    }
    /* Build the triangle variant now so a bad pipeline reports at creation. */
    p.variant[2][0] = build_variant(p, 2, false);
    if (!p.variant[2][0]) {
        vkDestroyPipelineLayout(g_dev, p.layout, nullptr);
        vkDestroyDescriptorSetLayout(g_dev, p.dsl, nullptr);
        return 0;
    }
    g_pipe[g_pipe_count] = std::move(p);
    return ++g_pipe_count;
}

void be_pipeline_release(void*, u32 pipeline)
{
    if (!pipeline || pipeline > g_pipe_count) return;
    Pipe& p = g_pipe[pipeline - 1];
    if (!p.live) return;
    if (g_rec_count) encode_and_submit(0);   /* records may still name it */
    for (auto& row : p.variant)
        for (VkPipeline& v : row) if (v) vkDestroyPipeline(g_dev, v, nullptr);
    if (p.layout) vkDestroyPipelineLayout(g_dev, p.layout, nullptr);
    if (p.dsl) vkDestroyDescriptorSetLayout(g_dev, p.dsl, nullptr);
    p = Pipe{};
}

void be_bind_targets(void*, const u32* surfaces, u32 count, u32 depth)
{
    if (count > RSX_BE_MAX_COLOR_TARGETS) count = RSX_BE_MAX_COLOR_TARGETS;
    for (u32 i = 0; i < RSX_BE_MAX_COLOR_TARGETS; i++) g_pending.rt[i] = (i < count) ? surfaces[i] : 0;
    g_pending.nrt = count;
    g_pending.depth = depth;
}
void be_bind_pipeline(void*, u32 pipeline) { g_pending.pipeline = pipeline; }
void be_bind_vs_constants(void*, const void* data, u32 bytes)
{
    if (!stage_copy(data, bytes, &g_pending.vs_cb_off)) bytes = 0;
    g_pending.vs_cb_bytes = bytes;
}
void be_bind_ps_constants(void*, const void* data, u32 bytes)
{
    if (!stage_copy(data, bytes, &g_pending.ps_cb_off)) bytes = 0;
    g_pending.ps_cb_bytes = bytes;
}
void be_bind_textures(void*, const u32* textures, const rsx_be_sampler_desc* samplers, u32 mask)
{
    for (u32 u = 0; u < RSX_BE_MAX_TEXTURES; u++) {
        g_pending.tex[u] = ((mask >> u) & 1u) ? textures[u] : 0;
        g_pending.samp[u] = ((mask >> u) & 1u) ? sampler_slot(&samplers[u]) : -1;
    }
}
void be_bind_vertex_textures(void*, const u32* textures, const rsx_be_sampler_desc* samplers, u32 mask)
{
    for (u32 u = 0; u < RSX_BE_MAX_VERTEX_TEXTURES; u++) {
        g_pending.vtex[u] = ((mask >> u) & 1u) ? textures[u] : 0;
        g_pending.vsamp[u] = ((mask >> u) & 1u) ? sampler_slot(&samplers[u]) : -1;
    }
}
void be_set_viewport(void*, float x, float y, float w, float h)
{
    g_pending.vp[0] = x;
    g_pending.vp[1] = y;
    g_pending.vp[2] = w;
    g_pending.vp[3] = h;
}
void be_set_scissor(void*, u32 x, u32 y, u32 w, u32 h)
{
    g_pending.sc[0] = x;
    g_pending.sc[1] = y;
    g_pending.sc[2] = w;
    g_pending.sc[3] = h;
}
void be_set_stencil_ref(void*, u32 ref) { g_pending.stencil_ref = ref; }

void be_draw(void*, rsx_topology topology, const void* vertices, u32 vertex_count, u32 stride,
             const u32* indices, u32 index_count)
{
    if (!vertex_count || !stride) return;
    rec_room();
    u32 vb = 0, ib = 0;
    g_st_vb += (uint64_t)vertex_count * stride;
    if (indices && index_count) g_st_ib += (uint64_t)index_count * sizeof(u32);
    if (!stage_copy(vertices, vertex_count * stride, &vb)) return;
    if (indices && index_count && !stage_copy(indices, index_count * (u32)sizeof(u32), &ib)) return;
    Rec& r = g_rec[g_rec_count++];
    r = g_pending;
    r.kind = REC_DRAW;
    r.tag = g_eu_draw_tag;
    r.topo = topology;
    r.vb_off = vb;
    r.stride = stride;
    r.vertex_count = vertex_count;
    r.ib_off = ib;
    r.index_count = indices ? index_count : 0;
    for (u32 i = 0; i < r.nrt && i < RSX_BE_MAX_COLOR_TARGETS; i++) mark_write(r.rt[i]);
}

void be_clear_color(void*, u32 surface, const float rgba[4])
{
    rec_room();
    Rec& r = g_rec[g_rec_count++];
    memset(&r, 0, sizeof r);
    r.kind = REC_CLEAR_COLOR;
    r.rt[0] = surface;
    r.nrt = 1;
    memcpy(r.clear_rgba, rgba, sizeof r.clear_rgba);
    mark_write(surface, rgba);
}

void be_clear_depth_stencil(void*, u32 depth, u32 flags, float depth_value, u8 stencil)
{
    rec_room();
    Rec& r = g_rec[g_rec_count++];
    memset(&r, 0, sizeof r);
    r.kind = REC_CLEAR_DS;
    r.depth = depth;
    r.clear_flags = flags;
    r.clear_depth = depth_value;
    r.clear_stencil = stencil;
}

/* What is being shown: grey/black screens are usually a presented surface
 * nothing drew into this frame, so log that. */
u32 g_pres_unwritten, g_pres_cleared_only, g_pres_missing, g_pres_total;
void present_diag(u32 surface)
{
    static u32 last_surface, logged;
    g_pres_total++;
    Obj* o = obj(surface);
    if (!o) {
        g_pres_missing++;
    } else if (o->write_frame != g_frame_no) {
        g_pres_unwritten++;
    }
    if (surface != last_surface && logged < 60) {
        logged++;
        if (o)
            fprintf(stderr, "[RSX vulkan] presenting surface %u: %ux%u fmt %d, %u write(s) this frame%s, "
                    "last clear (%.2f %.2f %.2f %.2f)\n", surface, o->w, o->h, (int)o->bfmt,
                    o->write_frame == g_frame_no ? o->frame_writes : 0u,
                    o->write_frame == g_frame_no ? "" : " (NOT drawn this frame)",
                    o->last_clear[0], o->last_clear[1], o->last_clear[2], o->last_clear[3]);
        else
            fprintf(stderr, "[RSX vulkan] presenting surface %u: no such surface\n", surface);
    }
    last_surface = surface;
}

void be_present(void*, u32 surface)
{
    present_diag(surface);
    encode_and_submit(surface);
    g_frame_no++;
    if (g_cap.active) {
        cap_finish(surface);
    } else if (eu_bridge_capture_pending) {
        const unsigned req = eu_bridge_capture_pending();
        if (req) {
            if (cap_init()) {
                g_cap = Capture{};
                g_cap.active = true;
                g_cap.req = req;
                fprintf(stderr, "[capture] capturing the next frame (request %u)\n", req);
            } else {
                fprintf(stderr, "[capture] could not set up the capture buffers\n");
                if (eu_bridge_capture_done) eu_bridge_capture_done(req);
            }
        }
    }
    g_last_guest_draws = g_guest_draws;
    g_guest_draws = 0;
    static uint64_t next_report;
    const uint64_t t = now_ms();
    if (&g_eu_eng_drop[0] != nullptr && t >= next_report) {
        next_report = t + 5000;
        unsigned d[16];
        for (int i = 0; i < 16; i++) { d[i] = g_eu_eng_drop[i]; g_eu_eng_drop[i] = 0; }
        fprintf(stderr, "[RSX vulkan] engine draws (5s): %u calls, %u drawn | dropped: no packets %u, "
                "topology %u, vertex fetch %u, rebuild %u, zero count %u, no colour target %u, "
                "no pipeline %u, index reserve %u, FP constants %u | immediate-mode %u\n",
                d[0], d[10], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9], d[12]);
        fprintf(stderr, "[RSX vulkan] frames (5s): %u presented, %u of an undrawn surface, %u of no surface | "
                "%u mid-frame flushes | CPU waited %.0f ms for the GPU\n", g_pres_total, g_pres_unwritten,
                g_pres_missing, g_mid_flushes, (double)g_gpu_wait_us / 1000.0);
        fprintf(stderr, "[RSX vulkan] data (5s): staged %.1f MB (vertices %.1f, indices %.1f, rest %.1f) | "
                "%u texture uploads, %.1f MB\n", g_st_total / 1048576.0, g_st_vb / 1048576.0, g_st_ib / 1048576.0,
                (double)(g_st_total - (g_st_vb + g_st_ib < g_st_total ? g_st_vb + g_st_ib : g_st_total)) / 1048576.0,
                g_up_count, g_up_bytes / 1048576.0);
        {   /* memory readout: is anything still growing? */
            long rss_pages = 0, page = sysconf(_SC_PAGESIZE);
            if (FILE* f = fopen("/proc/self/statm", "r")) { long sz; if (fscanf(f, "%ld %ld", &sz, &rss_pages) != 2) rss_pages = 0; fclose(f); }
            long avail_kb = -1, total_kb = -1;
            if (FILE* f = fopen("/proc/meminfo", "r")) {
                char ln[128];
                while (fgets(ln, sizeof ln, f)) {
                    if (!strncmp(ln, "MemTotal:", 9)) total_kb = atol(ln + 9);
                    else if (!strncmp(ln, "MemAvailable:", 13)) avail_kb = atol(ln + 13);
                }
                fclose(f);
            }
            u32 live = 0, imgs = 0, views = 0, retired = 0;
            for (u32 i = 0; i < g_obj_count; i++) {
                const Obj& o = g_obj[i];
                if (!o.live) continue;
                live++;
                if (o.retired) retired++;
                if (o.owns && o.img) imgs++; else if (o.parent) views++;
            }
            fprintf(stderr, "[mem] app %.0f MB resident | graphics: images %.0f MB, buffers %.0f MB | objects %u live "
                    "(%u images, %u views, %u waiting to be freed), %u surface views | phone: %.0f of %.0f MB available\n",
                    rss_pages * (double)page / 1048576.0, g_mem_img / 1048576.0, g_mem_buf / 1048576.0, live, imgs,
                    views, retired, g_view_count, avail_kb / 1024.0, total_kb / 1024.0);
        }
        g_st_total = g_st_vb = g_st_ib = g_up_bytes = 0;
        g_up_count = 0;
        g_pres_total = g_pres_unwritten = g_pres_missing = g_mid_flushes = 0;
        g_gpu_wait_us = 0;
    }
    static u32 presents;
    if (++presents == 1 || presents % 600 == 0)
        fprintf(stderr, "[RSX vulkan] present #%u: %u guest draws, %u skipped (attachment mismatch), "
                "%zu shader programs (%u failed), %u pipelines\n",
                presents, g_last_guest_draws, g_skipped_draws, g_funcs.size(), g_shader_failures, g_pipe_count);
    g_skipped_draws = 0;
}

void be_readback(void*, u32 surface, u32 x, u32 y, u32 w, u32 h, void* out, u32 out_pitch)
{
    Obj* o = obj(surface);
    if (!o || !out || !out_pitch || !w || !h || x + w > o->w || y + h > o->h || o->depth) return;
    if (g_rec_count) encode_and_submit(0);
    wait_all_slots();
    const u32 bpp = bytes_per_pixel(o->fmt);
    if (!buffer_ensure(g_upload_buf, (VkDeviceSize)w * h * bpp, VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                                                                   VK_BUFFER_USAGE_TRANSFER_DST_BIT)) return;
    VkCommandBuffer cb = imm_begin();
    VkBufferImageCopy c{};
    c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    c.imageOffset = {(int32_t)x, (int32_t)y, 0};
    c.imageExtent = {w, h, 1};
    barrier_all(cb);
    vkCmdCopyImageToBuffer(cb, o->img, VK_IMAGE_LAYOUT_GENERAL, g_upload_buf.buf, 1, &c);
    barrier_all(cb);
    imm_end();
    for (u32 row = 0; row < h; row++)
        memcpy((u8*)out + (size_t)row * out_pitch, (u8*)g_upload_buf.map + (size_t)row * w * bpp, (size_t)w * bpp);
}

rsx_draw_backend g_backend = {
    nullptr,
    be_init,
    be_shutdown,
    be_submit_and_wait,
    be_texture_create,
    be_texture_upload,
    be_obj_release,
    be_color_target_create,
    be_obj_release,
    be_surface_view,
    be_depth_target_create,
    be_obj_release,
    be_depth_snapshot,
    be_pipeline_create,
    be_pipeline_release,
    be_bind_targets,
    be_bind_pipeline,
    be_bind_vs_constants,
    be_bind_ps_constants,
    be_bind_textures,
    be_bind_vertex_textures,
    be_set_viewport,
    be_set_scissor,
    be_set_stencil_ref,
    be_draw,
    be_clear_color,
    be_clear_depth_stencil,
    be_present,
    be_readback,
};

/* ------------------------------------------------------------------ init */
bool init_device()
{
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "inFAMOUS Android runner";
    app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    if (!load_global()) return false;
    u32 inst_ver = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion) vkEnumerateInstanceVersion(&inst_ver);
    if (inst_ver < VK_API_VERSION_1_3) {
        fprintf(stderr, "[RSX vulkan] Vulkan loader is %u.%u; 1.3 needed\n",
                VK_API_VERSION_MAJOR(inst_ver), VK_API_VERSION_MINOR(inst_ver));
        return false;
    }
    VkResult r = vkCreateInstance(&ici, nullptr, &g_inst);
    if (r != VK_SUCCESS) {
        fprintf(stderr, "[RSX vulkan] vkCreateInstance failed (%d)\n", (int)r);
        return false;
    }
    if (!load_instance(g_inst)) return false;
    u32 n = 0;
    vkEnumeratePhysicalDevices(g_inst, &n, nullptr);
    if (!n) {
        fprintf(stderr, "[RSX vulkan] no Vulkan device\n");
        return false;
    }
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(g_inst, &n, devs.data());
    g_phys = devs[0];
    vkGetPhysicalDeviceProperties(g_phys, &g_props);
    vkGetPhysicalDeviceMemoryProperties(g_phys, &g_memprops);
    if (g_props.apiVersion < VK_API_VERSION_1_3) {
        fprintf(stderr, "[RSX vulkan] %s supports Vulkan %u.%u; 1.3 needed\n", g_props.deviceName,
                VK_API_VERSION_MAJOR(g_props.apiVersion), VK_API_VERSION_MINOR(g_props.apiVersion));
        return false;
    }
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    f13.pNext = &f12;
    VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    f2.pNext = &f13;
    vkGetPhysicalDeviceFeatures2(g_phys, &f2);
    if (!f13.dynamicRendering) {
        fprintf(stderr, "[RSX vulkan] dynamic rendering not supported\n");
        return false;
    }
    g_bc_ok = f2.features.textureCompressionBC == VK_TRUE;
    g_mirror_clamp_ok = f12.samplerMirrorClampToEdge == VK_TRUE;

    u32 qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(g_phys, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(g_phys, &qn, qf.data());
    g_qfam = UINT32_MAX;
    for (u32 i = 0; i < qn; i++)
        if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            g_qfam = i;
            break;
        }
    if (g_qfam == UINT32_MAX) return false;

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = g_qfam;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkPhysicalDeviceVulkan12Features en12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    en12.samplerMirrorClampToEdge = g_mirror_clamp_ok ? VK_TRUE : VK_FALSE;
    VkPhysicalDeviceVulkan13Features en13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    en13.dynamicRendering = VK_TRUE;
    en13.pNext = &en12;
    VkPhysicalDeviceFeatures2 en{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    en.pNext = &en13;
    en.features.textureCompressionBC = g_bc_ok ? VK_TRUE : VK_FALSE;
    en.features.samplerAnisotropy = VK_FALSE;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &en;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    r = vkCreateDevice(g_phys, &dci, nullptr, &g_dev);
    if (r != VK_SUCCESS) {
        fprintf(stderr, "[RSX vulkan] vkCreateDevice failed (%d)\n", (int)r);
        return false;
    }
    vkGetDeviceQueue(g_dev, g_qfam, 0, &g_queue);

    for (VkFormat f : {VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT}) {
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(g_phys, f, &fp);
        if (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) {
            g_depth_fmt = f;
            break;
        }
    }
    if (g_depth_fmt == VK_FORMAT_UNDEFINED) return false;

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = g_qfam;
    if (vkCreateCommandPool(g_dev, &pci, nullptr, &g_cmdpool) != VK_SUCCESS) return false;
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = g_cmdpool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    vkAllocateCommandBuffers(g_dev, &cai, &g_cb_imm);
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    vkCreateFence(g_dev, &fci, nullptr, &g_fence_imm);
    for (u32 k = 0; k < NUM_SLOTS; k++) {
        vkAllocateCommandBuffers(g_dev, &cai, &g_slot[k].cb);
        vkCreateFence(g_dev, &fci, nullptr, &g_slot[k].fence);
    }

    VkDescriptorPoolSize sizes[] = {
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, MAX_RECORDS * 2 + 64},
        {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, MAX_RECORDS * 20 + 64 + CAP_TILES},
        {VK_DESCRIPTOR_TYPE_SAMPLER, MAX_RECORDS * 20 + 64 + CAP_TILES},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1024},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 64},
    };
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = MAX_RECORDS + 64 + CAP_TILES;
    dpi.poolSizeCount = 5;
    dpi.pPoolSizes = sizes;
    for (u32 k = 0; k < NUM_SLOTS; k++)
        if (vkCreateDescriptorPool(g_dev, &dpi, nullptr, &g_slot[k].dpool) != VK_SUCCESS) return false;
    return true;
}

bool init_resources()
{
    if (!buffer_create(g_zero_ubo, UBO_PAD, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)) return false;
    memset(g_zero_ubo.map, 0, UBO_PAD);
    for (u32 k = 0; k < NUM_SLOTS; k++)
        if (!buffer_create(g_slot[k].readback, (VkDeviceSize)PRESENT_MAX_W * PRESENT_MAX_H * 4,
                           VK_BUFFER_USAGE_TRANSFER_DST_BIT)) return false;

    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = si.minFilter = VK_FILTER_LINEAR;
    si.addressModeU = si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.maxLod = 0.25f;
    if (vkCreateSampler(g_dev, &si, nullptr, &g_default_sampler) != VK_SUCCESS) return false;
    si.magFilter = si.minFilter = VK_FILTER_NEAREST;
    if (vkCreateSampler(g_dev, &si, nullptr, &g_point_sampler) != VK_SUCCESS) return false;

    /* Placeholder textures: transparent black, 2D and cube. */
    const u8 zero[4 * 6] = {};
    g_null2d = be_texture_create(nullptr, RSX_BE_FMT_R8G8B8A8, 1, 1, 1, 1, 0, 0);
    g_nullcube = be_texture_create(nullptr, RSX_BE_FMT_R8G8B8A8, 1, 1, 1, 6, 0, 0);
    if (!g_null2d || !g_nullcube) return false;
    /* No crossbar on placeholders: identity views. */
    for (u32 hnd : {g_null2d, g_nullcube}) {
        Obj* o = obj(hnd);
        vkDestroyImageView(g_dev, o->view, nullptr);
        o->view = make_view(o->img, o->fmt, o->cube ? VK_IMAGE_VIEW_TYPE_CUBE : VK_IMAGE_VIEW_TYPE_2D,
                            VK_IMAGE_ASPECT_COLOR_BIT, 1, o->layers, IDENTITY);
    }
    upload_now(*obj(g_null2d), 0, 0, 1, 1, zero, 4, 1);
    for (u32 f = 0; f < 6; f++) upload_now(*obj(g_nullcube), f, 0, 1, 1, zero, 4, 1);

    /* Present image. */
    if (!image_create(g_present, VK_FORMAT_R8G8B8A8_UNORM, PRESENT_MAX_W, PRESENT_MAX_H, 1, 1, false,
                      VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) return false;
    g_present.att = make_view(g_present.img, g_present.fmt, VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_ASPECT_COLOR_BIT,
                              1, 1, IDENTITY);
    {
        VkCommandBuffer cb = imm_begin();
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = g_present.img;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0,
                             nullptr, 0, nullptr, 1, &b);
        imm_end();
    }

    /* Helper pipelines (GLSL through glslang). */
    VkDescriptorSetLayoutBinding hb[2] = {
        {0, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr},
        {1, VK_DESCRIPTOR_TYPE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr},
    };
    VkDescriptorSetLayoutCreateInfo dci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dci.bindingCount = 2;
    dci.pBindings = hb;
    if (vkCreateDescriptorSetLayout(g_dev, &dci, nullptr, &g_helper_dsl) != VK_SUCCESS) return false;
    VkPipelineLayoutCreateInfo lci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    lci.setLayoutCount = 1;
    lci.pSetLayouts = &g_helper_dsl;
    if (vkCreatePipelineLayout(g_dev, &lci, nullptr, &g_helper_layout) != VK_SUCCESS) return false;
    std::vector<u32> spv;
    if (!compile_spirv(kFullscreenVS, EShLangVertex, false, spv, nullptr)) {
        fprintf(stderr, "[RSX vulkan] helper VS: %s\n", g_log);
        return false;
    }
    g_fullscreen_vs = make_module(spv);
    spv.clear();
    if (!compile_spirv(kBlitFS, EShLangFragment, false, spv, nullptr)) {
        fprintf(stderr, "[RSX vulkan] helper blit FS: %s\n", g_log);
        return false;
    }
    g_blit_fs = make_module(spv);
    spv.clear();
    if (!compile_spirv(kDepthFS, EShLangFragment, false, spv, nullptr)) {
        fprintf(stderr, "[RSX vulkan] helper depth FS: %s\n", g_log);
        return false;
    }
    g_depth_fs = make_module(spv);
    spv.clear();
    if (compile_spirv(kDepthPackFS, EShLangFragment, false, spv, nullptr)) g_depthpack_fs = make_module(spv);
    else fprintf(stderr, "[RSX vulkan] helper depth-pack FS: %s\n", g_log);
    g_blit_pipe = helper_pipeline(g_blit_fs, VK_FORMAT_R8G8B8A8_UNORM);
    g_depth_pipe = helper_pipeline(g_depth_fs, VK_FORMAT_R32_SFLOAT);
    if (g_depthpack_fs) g_depthpack_pipe = helper_pipeline(g_depthpack_fs, VK_FORMAT_R8G8B8A8_UNORM);
    return g_blit_pipe && g_depth_pipe;
}

} // namespace

/* ------------------------------------------------------------------ public */
extern "C" int eu_vk_rsx_active(void) { return g_active ? 1 : 0; }

extern "C" int eu_vk_rsx_init(u32 width, u32 height)
{
    const char* sel = getenv("EU_RSX");
    if (sel && *sel && strcmp(sel, "vulkan") != 0) return -1;
    g_rec = (Rec*)calloc(MAX_RECORDS, sizeof(Rec));
    if (!g_rec) return -1;
    if (!init_device()) {
        fprintf(stderr, "[RSX vulkan] device unavailable -- using the CPU software preview\n");
        return -1;
    }
    if (!init_resources()) {
        fprintf(stderr, "[RSX vulkan] resource setup failed -- using the CPU software preview\n");
        return -1;
    }
    g_active = true;
    rsx_draw_engine_set_backend(&g_backend);
    rsx_draw_engine_set_default(1);
    if (!rsx_draw_engine_enabled() || rsx_draw_engine_init(width ? width : 1280, height ? height : 720) != 0) {
        fprintf(stderr, "[RSX vulkan] draw engine did not start -- using the CPU software preview\n");
        rsx_draw_engine_set_backend(nullptr);
        g_active = false;
        return -1;
    }
    rsx_set_backend(&g_noop_vtable);
    fprintf(stderr, "[RSX vulkan] GPU renderer active on %s (Vulkan %u.%u, BC textures %s, depth format %d)\n",
            g_props.deviceName, VK_API_VERSION_MAJOR(g_props.apiVersion), VK_API_VERSION_MINOR(g_props.apiVersion),
            g_bc_ok ? "native" : "CPU-decoded", (int)g_depth_fmt);
    return 0;
}
