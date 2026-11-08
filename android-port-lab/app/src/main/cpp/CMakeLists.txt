cmake_minimum_required(VERSION 3.22.1)
project(infamous_android_port_lab LANGUAGES C CXX)
set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)
set(UPSTREAM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../upstream/ps3recomp")
if(NOT EXISTS "${UPSTREAM_ROOT}/runtime/milestone.c")
  message(FATAL_ERROR "Missing upstream ps3recomp checkout at ${UPSTREAM_ROOT}; see README")
endif()
add_library(infamousprobe SHARED
    native_probe.cpp
    ppu_smoke.cpp
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../generated/ppu_recomp.c"
    "${UPSTREAM_ROOT}/runtime/milestone.c"
)
set_source_files_properties("${CMAKE_CURRENT_SOURCE_DIR}/../../../../generated/ppu_recomp.c"
    PROPERTIES LANGUAGE CXX)
target_include_directories(infamousprobe PRIVATE
    "${UPSTREAM_ROOT}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../generated")
find_library(ANDROID_LOG_LIB log REQUIRED)
find_library(VULKAN_LIB vulkan REQUIRED)
target_link_libraries(infamousprobe PRIVATE ${ANDROID_LOG_LIB} ${VULKAN_LIB})
