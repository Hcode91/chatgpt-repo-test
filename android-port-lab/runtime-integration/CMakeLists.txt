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
