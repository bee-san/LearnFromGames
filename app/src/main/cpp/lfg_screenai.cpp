// SPDX-License-Identifier: GPL-3.0-or-later
// ScreenAI through screenai_ocr's libchromescreenai.so (tools/build_screenai.sh).
// The library is optional: it is opened at run time, so an app built without
// it still works and reads with meikiocr.
#include <dlfcn.h>
#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

// The start of Skia's SkBitmap, as the library reads it.
struct Bitmap {
  void* pixel_ref;
  const uint8_t* pixels;
  size_t row_bytes;
  void* color_space;
  int32_t color_type;  // 4: RGBA_8888
  int32_t alpha_type;
  int32_t width;
  int32_t height;
};

using SizeFn = uint32_t (*)(const char*);
using ContentFn = void (*)(const char*, uint32_t, char*);

struct Api {
  void (*set_files)(SizeFn, ContentFn) = nullptr;
  bool (*init)() = nullptr;
  void (*light_mode)(bool) = nullptr;
  char* (*perform)(const Bitmap*, uint32_t*) = nullptr;
  void (*free_chars)(char*) = nullptr;
};

std::mutex g_lock;
Api g_api;
bool g_loaded = false;
bool g_ready = false;
std::string g_dir;

// Files are asked for by their path under the component's resources directory.
bool safe(const char* path) { return path != nullptr && path[0] != '/' && std::strstr(path, "..") == nullptr; }

uint32_t file_size(const char* path) {
  if (!safe(path)) return 0;
  FILE* f = std::fopen((g_dir + "/" + path).c_str(), "rb");
  if (f == nullptr) return 0;
  std::fseek(f, 0, SEEK_END);
  const long size = std::ftell(f);
  std::fclose(f);
  return size > 0 ? static_cast<uint32_t>(size) : 0;
}

void file_content(const char* path, uint32_t size, char* buffer) {
  if (!safe(path) || buffer == nullptr) return;
  FILE* f = std::fopen((g_dir + "/" + path).c_str(), "rb");
  if (f == nullptr) return;
  const size_t got = std::fread(buffer, 1, size, f);
  if (got < size) std::memset(buffer + got, 0, size - got);
  std::fclose(f);
}

bool load() {
  if (g_loaded) return g_api.perform != nullptr;
  g_loaded = true;
  void* lib = dlopen("libchromescreenai.so", RTLD_NOW | RTLD_LOCAL);
  if (lib == nullptr) return false;
  g_api.set_files = reinterpret_cast<void (*)(SizeFn, ContentFn)>(dlsym(lib, "SetFileContentFunctions"));
  g_api.init = reinterpret_cast<bool (*)()>(dlsym(lib, "InitOCRUsingCallback"));
  g_api.light_mode = reinterpret_cast<void (*)(bool)>(dlsym(lib, "SetOCRLightMode"));
  g_api.perform = reinterpret_cast<char* (*)(const Bitmap*, uint32_t*)>(dlsym(lib, "PerformOCR"));
  g_api.free_chars = reinterpret_cast<void (*)(char*)>(dlsym(lib, "FreeLibraryAllocatedCharArray"));
  if (g_api.set_files == nullptr || g_api.init == nullptr || g_api.perform == nullptr || g_api.free_chars == nullptr) {
    g_api = Api{};
    return false;
  }
  return true;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_io_github_beesan_lfg_ocr_ScreenAi_nativeAvailable(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> guard(g_lock);
  return load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_beesan_lfg_ocr_ScreenAi_nativeInit(JNIEnv* env, jclass, jbyteArray dir) {
  std::lock_guard<std::mutex> guard(g_lock);
  if (!load()) return JNI_FALSE;
  const jsize len = env->GetArrayLength(dir);
  std::string path(static_cast<size_t>(len), '\0');
  env->GetByteArrayRegion(dir, 0, len, reinterpret_cast<jbyte*>(path.data()));
  if (g_ready && path == g_dir) return JNI_TRUE;
  g_dir = path;
  g_api.set_files(file_size, file_content);
  g_ready = g_api.init();
  // Light mode skips the script-specific second pass, which is the one that reads Japanese.
  if (g_ready && g_api.light_mode != nullptr) g_api.light_mode(false);
  return g_ready ? JNI_TRUE : JNI_FALSE;
}

// Returns the serialized chrome_screen_ai.VisualAnnotation; empty for no text, null when the read failed.
JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_ocr_ScreenAi_nativeRead(JNIEnv* env, jclass, jintArray pixels,
                                                                               jint width, jint height) {
  std::lock_guard<std::mutex> guard(g_lock);
  if (!g_ready || pixels == nullptr || width <= 0 || height <= 0) return nullptr;
  const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
  if (static_cast<size_t>(env->GetArrayLength(pixels)) < count) return nullptr;

  std::vector<uint8_t> rgba(count * 4);
  jint* argb = env->GetIntArrayElements(pixels, nullptr);
  if (argb == nullptr) return nullptr;
  for (size_t i = 0; i < count; ++i) {
    const uint32_t p = static_cast<uint32_t>(argb[i]);
    rgba[i * 4] = static_cast<uint8_t>(p >> 16);
    rgba[i * 4 + 1] = static_cast<uint8_t>(p >> 8);
    rgba[i * 4 + 2] = static_cast<uint8_t>(p);
    rgba[i * 4 + 3] = 0xFF;
  }
  env->ReleaseIntArrayElements(pixels, argb, JNI_ABORT);

  Bitmap bitmap{};
  bitmap.pixels = rgba.data();
  bitmap.row_bytes = static_cast<size_t>(width) * 4;
  bitmap.color_type = 4;
  bitmap.alpha_type = 1;
  bitmap.width = width;
  bitmap.height = height;
  uint32_t length = 0;
  char* out = g_api.perform(&bitmap, &length);
  if (out == nullptr) return nullptr;
  jbyteArray result = env->NewByteArray(static_cast<jsize>(length));
  if (result != nullptr && length > 0) env->SetByteArrayRegion(result, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(out));
  g_api.free_chars(out);
  return result;
}

}  // extern "C"
