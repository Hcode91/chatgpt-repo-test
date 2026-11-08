#include <jni.h>
#include <stdint.h>
#include <cstring>
#include <string>
#include <cstdio>
#include "ppu_recomp.h"

// Minimal host bridge. Unexpected guest runtime calls are counted as errors,
// never silently accepted. This initial instruction test is register-only;
// full PS3 LV2, HLE, thread and RSX support come later.
static int unexpected_calls = 0;
extern "C" {
uint8_t* vm_base = nullptr;
uint8_t vm_read8(uint64_t) { ++unexpected_calls; return 0; }
uint16_t vm_read16(uint64_t) { ++unexpected_calls; return 0; }
uint32_t vm_read32(uint64_t) { ++unexpected_calls; return 0; }
uint64_t vm_read64(uint64_t) { ++unexpected_calls; return 0; }
void vm_write8(uint64_t, uint8_t) { ++unexpected_calls; }
void vm_write16(uint64_t, uint16_t) { ++unexpected_calls; }
void vm_write32(uint64_t, uint32_t) { ++unexpected_calls; }
void vm_write64(uint64_t, uint64_t) { ++unexpected_calls; }
int ppu_stwcx32(uint64_t, uint32_t, uint32_t) { ++unexpected_calls; return 0; }
int ppu_stdcx64(uint64_t, uint64_t, uint64_t) { ++unexpected_calls; return 0; }
void lv2_syscall(ppu_context*) { ++unexpected_calls; }
void ps3_hle_call(unsigned int, ppu_context*) { ++unexpected_calls; }
void ps3_indirect_call(ppu_context*) { ++unexpected_calls; }
uint64_t ppu_timebase_now() { ++unexpected_calls; return 0; }
}
PPU_THREAD_LOCAL void (*g_trampoline_fn)(void*) = nullptr;

static std::string execute_lifted_smoke() {
    unexpected_calls = 0;
    ppu_context ctx{};
    ctx.gpr[3] = 5;
    ctx.lr = 0;
    unsigned found = 0;
    for (uint64_t i = 0; i < function_table_count; ++i) {
        if (function_table[i].addr == 0x10000 && function_table[i].func) {
            found++;
            function_table[i].func(&ctx);
        }
    }
    uint64_t result = ctx.gpr[3];
    bool ok = found == 1 && result == 71 && unexpected_calls == 0;
    char details[512];
    snprintf(details, sizeof details,
        "Real PowerPC64 instruction lift on Android\\n"
        "Original guest instructions: addi; mulli; addi; blr\\n"
        "Host CPU: arm64-v8a\\n"
        "Input guest register r3: 5\\n"
        "Expected r3: 71\\n"
        "Actual r3: %llu\\n"
        "Lifted entries matched: %u\\n"
        "Unexpected HLE/memory calls: %d\\n"
        "Result: %s\\n"
        "NOTE: This is a clean-room single-function smoke test, "
        "NOT a full PS3 ELF boot and NOT inFAMOUS gameplay.",
        (unsigned long long)result, found, unexpected_calls, ok ? "PASS" : "FAIL");
    return details;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hcode91_infamousprobe_MainActivity_nativePpuSmoke(JNIEnv* env, jclass) {
    std::string output = execute_lifted_smoke();
    return env->NewStringUTF(output.c_str());
}

#ifdef PPU_SMOKE_HOST
int main() {
    auto result = execute_lifted_smoke();
    puts(result.c_str());
    return result.find("Result: PASS") == std::string::npos ? 1 : 0;
}
#endif
