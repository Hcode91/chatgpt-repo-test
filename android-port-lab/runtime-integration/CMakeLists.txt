cmake_minimum_required(VERSION 3.22.1)
project(infamous_android_upstream_integration LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "Use the Android NDK CMake toolchain for this target.")
endif()

# Unlike the tiny standalone APK smoke tests, this attempts to build and
# link the actual upstream ps3recomp runtime as one ARM64 CMake dependency.
set(SDL_SHARED OFF CACHE BOOL "" FORCE)
set(SDL_STATIC ON CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_INSTALL ON CACHE BOOL "" FORCE)

set(SDL_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/SDL2-src")
set(UP_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../upstream/ps3recomp")
if(NOT EXISTS "${SDL_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "SDL2 source checkout missing")
endif()
if(NOT EXISTS "${UP_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "upstream ps3recomp checkout missing")
endif()

add_subdirectory("${SDL_SOURCE}" "${CMAKE_BINARY_DIR}/SDL2")
# SDL2's generated CMake targets differ between versions; provide a stable
# alias only if the public static SDL2::SDL2 alias was not exported.
if(NOT TARGET SDL2::SDL2)
    if(TARGET SDL2-static)
        add_library(SDL2::SDL2 ALIAS SDL2-static)
    else()
        message(FATAL_ERROR "SDL2 static target missing")
    endif()
endif()

# Bionic omits the POSIX getcontext/makecontext/swapcontext APIs.
# libucontext provides an ARM64 implementation with an ISC-style license.
# This uses a project-scoped copy, not the system's ucontext_t ABI.
set(UCTX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libucontext")
if(NOT EXISTS "${UCTX_ROOT}/arch/aarch64/getcontext.S")
    message(FATAL_ERROR "Missing libucontext checkout")
endif()
enable_language(ASM)
add_library(ps3recomp_ucontext STATIC
    "${UCTX_ROOT}/arch/aarch64/getcontext.S"
    "${UCTX_ROOT}/arch/aarch64/setcontext.S"
    "${UCTX_ROOT}/arch/aarch64/swapcontext.S"
    "${UCTX_ROOT}/arch/aarch64/makecontext.c"
    "${UCTX_ROOT}/arch/aarch64/trampoline.c")
target_include_directories(ps3recomp_ucontext PRIVATE
    "${UCTX_ROOT}/arch/aarch64"
    "${UCTX_ROOT}/arch/common"
    "${UCTX_ROOT}/arch/aarch64/include"
    "${UCTX_ROOT}/include")
target_compile_definitions(ps3recomp_ucontext PRIVATE FREESTANDING=1)
set_target_properties(ps3recomp_ucontext PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_subdirectory("${UP_SOURCE}" "${CMAKE_BINARY_DIR}/ps3recomp")
target_include_directories(ps3recomp_runtime PRIVATE
    "${UCTX_ROOT}/include" "${UCTX_ROOT}/arch/aarch64/include")
target_link_libraries(ps3recomp_runtime PUBLIC ps3recomp_ucontext)
# The upstream boot-smoke executable intentionally omits the title's own host
# runner. Supply only the runner-owned services and explicitly disabled
# optional title-specific plugin hooks, never impersonate full gameplay.
target_sources(ps3recomp_boot_smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/smoke_host_services.cpp")
message(STATUS "Android PS3 runtime integration targets are configured")


# PRIVATE BCES00220 game-boot target. This is disabled in PUBLIC CI and requires
# game-derived source generated locally using prepare_eu_game_lift.py.
set(INFAMOUS_PRIVATE_RECOMP "" CACHE PATH "Private BCES00220 PPU C++ lift")
if(INFAMOUS_PRIVATE_RECOMP)
    get_filename_component(INFAMOUS_PRIVATE_RECOMP "${INFAMOUS_PRIVATE_RECOMP}" ABSOLUTE)
    if(NOT EXISTS "${INFAMOUS_PRIVATE_RECOMP}/ppu_recomp.h")
        message(FATAL_ERROR "Private PPU lift missing ppu_recomp.h")
    endif()
    file(GLOB PRIVATE_PPU_SOURCES CONFIGURE_DEPENDS
        "${INFAMOUS_PRIVATE_RECOMP}/ppu_recomp*.cpp"
        "${INFAMOUS_PRIVATE_RECOMP}/ppu_recomp*.c")
    if(NOT PRIVATE_PPU_SOURCES)
        message(FATAL_ERROR "Private lift has no generated source")
    endif()
    # ps3recomp emits C++ (extern "C", inline templates) even when named .c.
    # Without this, Android NDK clang compiles the generated code as plain C.
    set_source_files_properties(${PRIVATE_PPU_SOURCES} PROPERTIES LANGUAGE CXX)
    # Same flags as upstream infamous-recomp/CMakeLists.txt off Windows.
    # -ffp-contract=off is a CORRECTNESS flag on arm64: clang otherwise fuses
    # a*b+c into one fmadd, so lifted PPC float maths would round differently
    # from the console (a PPC fmadd is emitted as an explicit fma() call).
    set_source_files_properties(${PRIVATE_PPU_SOURCES} PROPERTIES
        COMPILE_OPTIONS "-w;-ffp-contract=off")
    if(EXISTS "${INFAMOUS_PRIVATE_RECOMP}/ppu_hle_nids.cpp")
        set(PRIVATE_HLE_NID_TABLE "${INFAMOUS_PRIVATE_RECOMP}/ppu_hle_nids.cpp")
    else()
        find_package(Python3 COMPONENTS Interpreter REQUIRED)
        set(PRIVATE_HLE_NID_TABLE "${CMAKE_CURRENT_BINARY_DIR}/eu/ppu_hle_nids.cpp")
        add_custom_command(
            OUTPUT "${PRIVATE_HLE_NID_TABLE}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory
                "${CMAKE_CURRENT_BINARY_DIR}/eu"
            COMMAND "${Python3_EXECUTABLE}"
                "${UP_SOURCE}/tools/gen_hle_nids.py"
                --out "${PRIVATE_HLE_NID_TABLE}" --all
            DEPENDS "${UP_SOURCE}/tools/gen_hle_nids.py"
            COMMENT "Generate firmware import NID table for private game boot")
    endif()
    add_executable(infamous_eu_private_boot
        "${UP_SOURCE}/runtime/ppu/tests/boot_main.cpp"
        "${UP_SOURCE}/runtime/ppu/ppu_loader.cpp"
        "${UP_SOURCE}/runtime/ppu/ppu_hle.cpp"
        "${UP_SOURCE}/runtime/ppu/ppu_fs.cpp"
        "${UP_SOURCE}/runtime/ppu/ppu_sysprx.cpp"
        "${PRIVATE_HLE_NID_TABLE}"
        "${CMAKE_CURRENT_SOURCE_DIR}/smoke_host_services.cpp"
        ${PRIVATE_PPU_SOURCES})
    target_include_directories(infamous_eu_private_boot PRIVATE
        "${INFAMOUS_PRIVATE_RECOMP}"
        "${UP_SOURCE}/include"
        "${UP_SOURCE}/runtime/ppu"
        "${UP_SOURCE}/runtime/spu"
        "${UP_SOURCE}/runtime/memory"
        "${UP_SOURCE}/runtime/syscalls"
        "${UP_SOURCE}/runtime/platform"
        "${UP_SOURCE}/libs/video")
    target_link_libraries(infamous_eu_private_boot PRIVATE
        ps3recomp_runtime ps3recomp_ucontext)
    set_target_properties(infamous_eu_private_boot PROPERTIES
        OUTPUT_NAME "libps3eu" SUFFIX ".so" LINKER_LANGUAGE CXX)
    target_link_options(infamous_eu_private_boot PRIVATE
        "-Wl,-z,max-page-size=16384")
    list(LENGTH PRIVATE_PPU_SOURCES PRIVATE_PPU_COUNT)
    message(STATUS "Private BCES00220 boot: ${PRIVATE_PPU_COUNT} game PPU translation units")
endif()


# Upstream-FIRST European game title target. This deliberately uses the REAL
# inFAMOUSreborn game runner (main.cpp, stubs.cpp, trainer.cpp), NOT the SDK's
# synthetic boot_main.cpp or our instruction-by-instruction Android tracer.
# Off by default: public CI has only a synthetic ELF; enable only in a
# private workspace with locally generated code from the verified BCES00220.
option(INFAMOUS_EU_UPSTREAM_TITLE
       "Compile actual inFAMOUSreborn title runner with private lifted sources" OFF)
set(INFAMOUS_EU_SPU_DIR "" CACHE PATH
    "Private path to SPU and WWS job modules generated by the upstream manifest")
if(INFAMOUS_EU_UPSTREAM_TITLE)
    if(NOT INFAMOUS_PRIVATE_RECOMP)
        message(FATAL_ERROR "Upstream inFAMOUS title requires INFAMOUS_PRIVATE_RECOMP")
    endif()
    set(EU_TITLE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../upstream/infamous-recomp")
    foreach(_f IN ITEMS main.cpp stubs.cpp trainer.cpp)
        if(NOT EXISTS "${EU_TITLE_DIR}/${_f}")
            message(FATAL_ERROR "Missing upstream game-specific source: ${_f}")
        endif()
    endforeach()
    # SPU is optional during first PPU-linked boot; the runner's missing
    # workloads are a logged blocker, not something we pretend to emulate.
    # Mirrors upstream infamous-recomp/CMakeLists.txt (PORT_ENABLE_SPU,
    # PORT_SPU_REGCACHE=ON): every <dir>/spu_recomp.c, its register-cached
    # twin where present, the generated registration TUs and the musttail
    # dispatcher. INFAMOUS_EU_SPU_DIR is the private, lock-verified
    # <copy>/infamous-recomp/spu produced by run_upstream_pipeline.py.
    set(EU_SPU_SOURCES "")
    if(INFAMOUS_EU_SPU_DIR)
        file(GLOB _eu_spu_plain CONFIGURE_DEPENDS
            "${INFAMOUS_EU_SPU_DIR}/*/spu_recomp.c")
        set(_eu_spu_rc "")
        foreach(_f IN LISTS _eu_spu_plain)
            get_filename_component(_d "${_f}" DIRECTORY)
            if(EXISTS "${_d}/spu_recomp_rc.c")
                list(APPEND _eu_spu_rc "${_d}/spu_recomp_rc.c")
            endif()
        endforeach()
        set(EU_SPU_SOURCES ${_eu_spu_plain} ${_eu_spu_rc})
        if(EU_SPU_SOURCES)
            list(LENGTH _eu_spu_plain _eu_spu_n)
            list(LENGTH _eu_spu_rc _eu_spu_rc_n)
            message(STATUS "European game: ${_eu_spu_n} lifted SPU image(s) + ${_eu_spu_rc_n} register-cached twin(s)")
            # The real title's many lifted SPU functions need the upstream
            # musttail indirect dispatch trampoline (not the stubbed runner).
            list(APPEND EU_SPU_SOURCES
                "${UP_SOURCE}/runtime/spu/spu_dispatch_mt.c")
            foreach(_registration IN ITEMS
                spu_workloads.c jobmods_register.c taskset_policy_bytes.c)
                if(EXISTS "${INFAMOUS_EU_SPU_DIR}/${_registration}")
                    list(APPEND EU_SPU_SOURCES "${INFAMOUS_EU_SPU_DIR}/${_registration}")
                else()
                    message(STATUS "  (no ${_registration} -- not registered)")
                endif()
            endforeach()
            # Upstream scopes these to the SPU TUs only; a target-wide define
            # would change (and rebuild) every huge lifted PPU TU. The x86
            # -msse4.1/-mavx2 flags have no ARM64 equivalent and are dropped;
            # -w as upstream does for job modules keeps CI logs readable.
            # The lifted WWS plugin code (and upstream patches to it) calls
            # Win32 helpers -- Interlocked*, GetTickCount64 -- that upstream's
            # MSVC/clang-cl build gets from <windows.h>. Off Windows, the
            # runtime's own win32_compat.h provides them (C11 _Generic in C),
            # exactly as it already does for the title's main.cpp.
            set_source_files_properties(${EU_SPU_SOURCES} PROPERTIES
                INCLUDE_DIRECTORIES "${UP_SOURCE}/runtime/spu"
                COMPILE_DEFINITIONS SPU_USE_DISPATCH_MT
                COMPILE_OPTIONS "-O2;-w;-ferror-limit=0;-include;${UP_SOURCE}/runtime/platform/win32_compat.h")
        endif()
    endif()
    add_executable(infamous_eu_upstream_title
        # Android-owned host services needed by the actual title runner.
        # Weak fallbacks do NOT implement real RSX, overlay or SPU backends.
        "${CMAKE_CURRENT_SOURCE_DIR}/eu_android_host_bridge.c"
        # Shared-memory screen + controller channel to the Android app.
        "${CMAKE_CURRENT_SOURCE_DIR}/eu_android_bridge.c"
        "${EU_TITLE_DIR}/main.cpp"
        "${EU_TITLE_DIR}/stubs.cpp"
        "${EU_TITLE_DIR}/trainer.cpp"
        "${UP_SOURCE}/runtime/ppu/ppu_loader.cpp"
        "${UP_SOURCE}/runtime/ppu/ppu_hle.cpp"
        "${UP_SOURCE}/runtime/ppu/ppu_fs.cpp"
        "${UP_SOURCE}/runtime/ppu/ppu_sysprx.cpp"
        "${PRIVATE_HLE_NID_TABLE}"
        ${PRIVATE_PPU_SOURCES}
        ${EU_SPU_SOURCES}
    )
    # Game-provided hooks take the place of smoke_host_services.cpp.
    target_include_directories(infamous_eu_upstream_title PRIVATE
        "${INFAMOUS_PRIVATE_RECOMP}"
        "${EU_TITLE_DIR}"
        "${UP_SOURCE}/include"
        "${UP_SOURCE}/runtime/ppu"
        "${UP_SOURCE}/runtime/spu"
        "${UP_SOURCE}/runtime/memory"
        "${UP_SOURCE}/runtime/syscalls"
        "${UP_SOURCE}/runtime/platform"
        "${UP_SOURCE}/libs/video")
    if(EU_SPU_SOURCES)
        message(STATUS "European game: generated real SPU workloads enabled")
    else()
        message(WARNING "European game: no SPU workloads available; game boot cannot complete")
    endif()
    target_link_libraries(infamous_eu_upstream_title PRIVATE
        ps3recomp_runtime ps3recomp_ucontext)

    # GPU renderer: Vulkan 1.3 backend for the toolkit's register-file draw
    # engine, compiling the decompiled HLSL with glslang (+ SPIRV-Tools for the
    # HLSL legalization passes). Entry points are loaded at run time, so no
    # libvulkan link. Without a glslang checkout the CPU preview stays.
    set(EU_GLSLANG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/glslang")
    if(EXISTS "${EU_GLSLANG_DIR}/CMakeLists.txt")
        set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
        set(ENABLE_SPVREMAPPER OFF CACHE BOOL "" FORCE)
        set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
        set(GLSLANG_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
        set(ENABLE_HLSL ON CACHE BOOL "" FORCE)
        set(ENABLE_OPT ON CACHE BOOL "" FORCE)
        set(ENABLE_CTEST OFF CACHE BOOL "" FORCE)
        set(SPIRV_SKIP_TESTS ON CACHE BOOL "" FORCE)
        set(SPIRV_SKIP_EXECUTABLES ON CACHE BOOL "" FORCE)
        set(SPIRV_WERROR OFF CACHE BOOL "" FORCE)
        set(SKIP_SPIRV_TOOLS_INSTALL ON CACHE BOOL "" FORCE)
        set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
        add_subdirectory("${EU_GLSLANG_DIR}" "${CMAKE_BINARY_DIR}/glslang" EXCLUDE_FROM_ALL)
        target_sources(infamous_eu_upstream_title PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/eu_vk_backend.cpp")
        set_source_files_properties("${CMAKE_CURRENT_SOURCE_DIR}/eu_vk_backend.cpp" PROPERTIES
            INCLUDE_DIRECTORIES "${EU_GLSLANG_DIR}"
            COMPILE_OPTIONS "-std=c++17")
        target_link_libraries(infamous_eu_upstream_title PRIVATE
            glslang glslang-default-resource-limits dl)
        if(TARGET SPIRV)
            target_link_libraries(infamous_eu_upstream_title PRIVATE SPIRV)
        endif()
        message(STATUS "European game: Vulkan GPU renderer enabled (glslang ${EU_GLSLANG_DIR})")
        # Crash reports (eu_android_bridge.c) name functions via dladdr, which
        # only sees exported symbols; Gradle strips .symtab but keeps .dynsym.
        target_link_options(infamous_eu_upstream_title PRIVATE "-Wl,--export-dynamic")
    else()
        message(WARNING "European game: no glslang checkout -- CPU software preview only")
    endif()
    target_link_options(infamous_eu_upstream_title PRIVATE
        "-Wl,-z,max-page-size=16384")
    set_target_properties(infamous_eu_upstream_title PROPERTIES
        OUTPUT_NAME "libps3eu_title" SUFFIX ".so" LINKER_LANGUAGE CXX)
    message(STATUS "Upstream European game-specific ARM64 runner enabled")
    message(STATUS "No title data in public CI; title backend and SPU require validation")
endif()
