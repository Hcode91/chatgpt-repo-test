// Narrow, bounds-checked ELF64/PowerPC guest image loader for the clean-room
// smoke title. NOT the upstream ppu_load_elf implementation. We need to
// integrate upstream loader/HLE/RSX before any attempt at inFAMOUS.
#include <jni.h>
#include <stdint.h>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

extern "C" uint8_t* vm_base;
extern "C" int ppu_smoke_execute_entry(uint64_t pc, uint64_t arg,
                                       uint64_t* out_value, int* out_calls);

namespace {
constexpr uint64_t MAX_GUEST = 16ull * 1024 * 1024; // deliberately tiny, smoke only
uint16_t be16(const uint8_t* b) { return (uint16_t(b[0]) << 8) | b[1]; }
uint32_t be32(const uint8_t* b) {
    return (uint32_t(b[0]) << 24) | (uint32_t(b[1]) << 16) |
           (uint32_t(b[2]) << 8) | b[3];
}
uint64_t be64(const uint8_t* b) {
    return (uint64_t(be32(b)) << 32) | be32(b + 4);
}
bool within(uint64_t off, uint64_t len, uint64_t cap) {
    return off <= cap && len <= cap - off;
}
std::string fail(const char* why) {
    return std::string("PS3-format ELF boot: FAIL\nReason: ") + why;
}
struct Segment { uint64_t off, addr, fileSize, memSize; };
std::string run_elf(const uint8_t* bytes, size_t count) {
    if (!bytes || count < 64) return fail("missing ELF64 header");
    if (std::memcmp(bytes, "\x7f" "ELF", 4) != 0 || bytes[4] != 2
           || bytes[5] != 2 || bytes[6] != 1)
        return fail("not a big-endian ELF64 image");
    if (be16(bytes + 16) != 2 || be16(bytes + 18) != 21)
        return fail("not PowerPC64 ET_EXEC");
    uint64_t opd = be64(bytes + 24);
    uint64_t phoff = be64(bytes + 32);
    uint16_t phentsize = be16(bytes + 54);
    uint16_t phnum = be16(bytes + 56);
    if (phentsize != 56 || phnum == 0 || phnum > 32 ||
        !within(phoff, uint64_t(phentsize) * phnum, count))
        return fail("invalid ELF program-header table");
    std::vector<Segment> segs;
    uint64_t guestEnd = 0;
    for (uint32_t i = 0; i < phnum; ++i) {
        const uint8_t* p = bytes + phoff + uint64_t(i) * phentsize;
        if (be32(p) != 1) continue; // PT_LOAD
        Segment seg { be64(p + 8), be64(p + 16),
                      be64(p + 32), be64(p + 40) };
        if (seg.fileSize > seg.memSize ||
            !within(seg.off, seg.fileSize, count) ||
            !within(seg.addr, seg.memSize, MAX_GUEST))
            return fail("unsafe PT_LOAD segment");
        segs.push_back(seg);
        uint64_t end = seg.addr + seg.memSize;
        if (end > guestEnd) guestEnd = end;
    }
    if (segs.size() != 2 || !within(opd, 8, guestEnd))
        return fail("expected two loadable segments and a valid OPD");
    std::vector<uint8_t> guest(static_cast<size_t>(guestEnd), 0);
    for (const auto& seg : segs) {
        std::memcpy(guest.data() + seg.addr, bytes + seg.off,
                    static_cast<size_t>(seg.fileSize));
    }
    const uint32_t pc = be32(guest.data() + opd);
    const uint32_t toc = be32(guest.data() + opd + 4);
    if (!within(pc, 16, guestEnd)) return fail("OPD points outside guest memory");

    // The recompilation is ahead-of-time. Reject a different program: never
    // pretend an arbitrary EBOOT can be run by this particular lifted code.
    constexpr uint32_t expected[] = { 0x38630007, 0x1C630006,
                                       0x3863FFFF, 0x4E800020 };
    for (unsigned i = 0; i < 4; ++i) {
        if (be32(guest.data() + pc + i * 4) != expected[i])
            return fail("ELF code does not match compiled lifted function");
    }

    vm_base = guest.data();
    uint64_t value = 0;
    int unexpected = 0;
    int found = ppu_smoke_execute_entry(pc, 5, &value, &unexpected);
    vm_base = nullptr;

    char report[800];
    const bool pass = found == 1 && value == 71 && unexpected == 0;
    std::snprintf(report, sizeof(report),
       "PS3-format ELF loader + native PPU execution\n"
       "ELF: big-endian PowerPC64, 2 PT_LOAD segments\n"
       "Image bytes: %zu\n"
       "Entry OPD address: 0x%llX\n"
       "Resolved PPU entry code: 0x%X\n"
       "Entry TOC: 0x%X\n"
       "ELF code matches lifted function: yes\n"
       "Expected r3: 71\n"
       "Actual r3: %llu\n"
       "Unexpected HLE/memory calls: %d\n"
       "Result: %s\n"
       "Scope: synthetic ELF, limited loader, no system calls.\n"
       "NOT the full ps3recomp boot loader or inFAMOUS.",
       count, (unsigned long long)opd, pc, toc, (unsigned long long)value,
       unexpected, pass ? "PASS" : "FAIL");
    return report;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_hcode91_infamousprobe_MainActivity_nativeElfSmoke(JNIEnv* env,
                                                            jclass,
                                                            jbyteArray input) {
    if (!input) return env->NewStringUTF("ELF boot: FAIL - missing asset");
    jsize len = env->GetArrayLength(input);
    if (len < 0 || len > 1024 * 1024)
        return env->NewStringUTF("ELF boot: FAIL - asset exceeds size limit");
    std::vector<uint8_t> bytes(static_cast<size_t>(len));
    if (len) env->GetByteArrayRegion(input, 0, len,
                                    reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return nullptr;
    std::string report = run_elf(bytes.data(), bytes.size());
    return env->NewStringUTF(report.c_str());
}

#ifdef PPU_ELF_HOST
int main(int argc, char** argv) {
    if (argc < 2) return 2;
    std::ifstream in(argv[1], std::ios::binary);
    if (!in) return 2;
    std::vector<uint8_t> image((std::istreambuf_iterator<char>(in)),
                               std::istreambuf_iterator<char>());
    bool mutated = argc > 2 && std::strcmp(argv[2], "--reject-mutated") == 0;
    if (mutated && image.size() > 0x1000)
        image[0x1000] ^= 0x01; // mismatch precompiled game code
    auto result = run_elf(image.data(), image.size());
    std::puts(result.c_str());
    bool pass = result.find("Result: PASS") != std::string::npos;
    return mutated ? (pass ? 1 : 0) : (pass ? 0 : 1);
}
#endif
