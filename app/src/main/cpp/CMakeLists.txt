cmake_minimum_required(VERSION 3.31)

project(lfg_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/hoshidicts hoshidicts)

# The engine boundary, free of JNI so the same code runs in the host test.
add_library(lfg_dict STATIC lfg_dict.cpp)
target_include_directories(lfg_dict PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(lfg_dict PUBLIC hoshidicts)
set_target_properties(lfg_dict PROPERTIES POSITION_INDEPENDENT_CODE ON)

# VOCR: the cheap first pass. Its runtime asks for these flags (its Makefile).
set(VOCR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/vocr)
add_library(lfg_vocr STATIC
    lfg_vocr.c
    ${VOCR_DIR}/vocr.c
    ${VOCR_DIR}/vocr_textspot6.c
    ${VOCR_DIR}/vocr_lines.c
    ${VOCR_DIR}/vocr_lines_rows.c
)
target_include_directories(lfg_vocr PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${VOCR_DIR})
target_compile_options(lfg_vocr PRIVATE -O2 -ffp-contract=off)
set_target_properties(lfg_vocr PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 99)
target_link_libraries(lfg_vocr PUBLIC m)

# LFG_HOST_TESTS=ON builds the command-line tests instead of the app's library, for
# any target: with the NDK and -static they run on arm64 under qemu-aarch64.
option(LFG_HOST_TESTS "Build the command-line tests, not the JNI library" OFF)
if(NOT ANDROID)
  set(LFG_HOST_TESTS ON)
endif()

if(NOT LFG_HOST_TESTS)
  # libwebp, for the animated picture: Android writes a still WebP itself, not a moving one.
  foreach(tool ANIM_UTILS CWEBP DWEBP GIF2WEBP IMG2WEBP VWEBP WEBPINFO WEBPMUX EXTRAS)
    set(WEBP_BUILD_${tool} OFF CACHE BOOL "" FORCE)
  endforeach()
  set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)
  add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libwebp libwebp EXCLUDE_FROM_ALL)

  add_library(lfg SHARED lfg_jni.cpp lfg_screenai.cpp lfg_webp.cpp)
  target_include_directories(lfg PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libwebp/src)
  target_link_libraries(lfg PRIVATE lfg_dict lfg_vocr libwebpmux webp dl)
  target_link_options(lfg PRIVATE -Wl,-z,max-page-size=16384)
else()
  # Host build: `cmake -S app/src/main/cpp -B host-build && cmake --build host-build`
  add_executable(lfg_dict_host host_test.cpp)
  target_link_libraries(lfg_dict_host PRIVATE lfg_dict)
  add_executable(lfg_vocr_host vocr_host_test.c)
  target_link_libraries(lfg_vocr_host PRIVATE lfg_vocr)
endif()
