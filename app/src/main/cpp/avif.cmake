# AVIF for card pictures: libavif over libaom's encoder, both from third_party, nothing fetched.
# Gives the static library lfg_avif_core (lfg_avif.c, free of JNI so a host build can try it).
# Included by CMakeLists.txt; a host build of its own can include it as well.

set(LFG_THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)

# libaom, encoder only, 8-bit only (card pictures are 8-bit sRGB), no programs, tests or documents.
# libavif's LocalAom builds it through FetchContent; pointing FetchContent at the submodule means
# nothing is downloaded, and LocalAom keeps aom's compiler flags off the app's own code.
set(FETCHCONTENT_SOURCE_DIR_LIBAOM ${LFG_THIRD_PARTY}/aom CACHE PATH "" FORCE)
set(CONFIG_AV1_DECODER 0 CACHE INTERNAL "")
set(CONFIG_AV1_HIGHBITDEPTH 0 CACHE INTERNAL "")
set(CONFIG_DENOISE 0 CACHE INTERNAL "")
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|i.86" AND ANDROID)
  # x86_64 is for the emulator only: plain C, so building needs no assembler (nasm) and speed does not matter.
  set(AOM_TARGET_CPU generic CACHE INTERNAL "")
endif()

set(AVIF_CODEC_AOM LOCAL CACHE STRING "" FORCE)
set(AVIF_CODEC_AOM_DECODE OFF CACHE BOOL "" FORCE)
set(AVIF_CODEC_AOM_ENCODE ON CACHE BOOL "" FORCE)
set(AVIF_LIBYUV OFF CACHE STRING "" FORCE)
set(AVIF_LIBSHARPYUV OFF CACHE STRING "" FORCE)
set(AVIF_LIBXML2 OFF CACHE STRING "" FORCE)
set(AVIF_ZLIBPNG OFF CACHE STRING "" FORCE)
set(AVIF_JPEG OFF CACHE STRING "" FORCE)
set(AVIF_BUILD_APPS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(_lfg_shared ${BUILD_SHARED_LIBS})
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(${LFG_THIRD_PARTY}/libavif libavif EXCLUDE_FROM_ALL)
set(BUILD_SHARED_LIBS ${_lfg_shared})
set_target_properties(avif_obj PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(lfg_avif_core STATIC ${CMAKE_CURRENT_LIST_DIR}/lfg_avif.c)
target_include_directories(lfg_avif_core PUBLIC ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(lfg_avif_core PUBLIC avif)
set_target_properties(lfg_avif_core PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 99)
