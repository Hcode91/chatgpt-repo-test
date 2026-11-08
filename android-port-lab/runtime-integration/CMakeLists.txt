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

add_subdirectory("${UP_SOURCE}" "${CMAKE_BINARY_DIR}/ps3recomp")
message(STATUS "Android PS3 runtime integration targets are configured")
