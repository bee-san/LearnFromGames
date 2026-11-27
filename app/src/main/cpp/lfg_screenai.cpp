// SPDX-License-Identifier: GPL-3.0-or-later
// ScreenAI through screenai_ocr's libchromescreenai.so (tools/build_screenai.sh).
// The library is optional: it is opened at run time, so the app builds without
// it, though nothing then reads the screen.
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
  int32_t color_type;  // 14: Gray_8
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
  void (*fast_mode)(bool) = nullptr;
  void (*language)(const char*) = nullptr;
  void (*text_height)(int32_t) = nullptr;
  char* (*perform)(const Bitmap*, uint32_t*) = nullptr;
  void (*free_chars)(char*) = nullptr;
};

std::mutex g_lock;
Api g_api;
bool g_loaded = false;
bool g_ready = false;
std::string g_dir;
std::string g_language;

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
  g_api.fast_mode = reinterpret_cast<void (*)(bool)>(dlsym(lib, "SetOCRFastMode"));
  g_api.language = reinterpret_cast<void (*)(const char*)>(dlsym(lib, "SetOCRLanguage"));
  g_api.text_height = reinterpret_cast<void (*)(int32_t)>(dlsym(lib, "SetOCRTextHeight"));
  g_api.perform = reinterpret_cast<char* (*)(const Bitmap*, uint32_t*)>(dlsym(lib, "PerformOCR"));
  g_api.free_chars = reinterpret_cast<void (*)(char*)>(dlsym(lib, "FreeLibraryAllocatedCharArray"));
  if (g_api.set_files == nullptr || g_api.init == nullptr || g_api.perform == nullptr || g_api.free_chars == nullptr) {
    g_api = Api{};
    return false;
  }
  return true;
}

std::string bytes_of(JNIEnv* env, jbyteArray array) {
  const jsize len = env->GetArrayLength(array);
  std::string out(static_cast<size_t>(len), '\0');
  env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte*>(out.data()));
  return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_io_github_beesan_lfg_ocr_ScreenAi_nativeAvailable(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> guard(g_lock);
  return load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_io_github_beesan_lfg_ocr_ScreenAi_nativeInit(JNIEnv* env, jclass, jbyteArray dir,
                                                                               jbyteArray language) {
  std::lock_guard<std::mutex> guard(g_lock);
  if (!load()) return JNI_FALSE;
  const std::string path = bytes_of(env, dir);
  const std::string tag = bytes_of(env, language);
  // The language on screen, when the app knows it (tools/screenai/0005): each line is read once, as that
  // language, and given before init the recognisers it does not need are never loaded. A library built without
  // it guesses each line's script, as before.
  if (g_api.language != nullptr && tag != g_language) {
    g_language = tag;
    g_api.language(tag.c_str());
  }
  if (g_ready && path == g_dir) return JNI_TRUE;
  g_dir = path;
  g_api.set_files(file_size, file_content);
  g_ready = g_api.init();
  // Light mode skips the script-specific second pass, which is the one that reads Japanese.
  if (g_ready && g_api.light_mode != nullptr) g_api.light_mode(false);
  // Fast mode (tools/screenai/0003): the same lines, text and boxes for about half the work, in the order
  // detected instead of reading order, which Blocks does not go by. A library built without it reads as before.
  if (g_ready && g_api.fast_mode != nullptr) g_api.fast_mode(true);
  return g_ready ? JNI_TRUE : JNI_FALSE;
}

// Returns the serialized chrome_screen_ai.VisualAnnotation; empty for no text, null when the read failed.
JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_ocr_ScreenAi_nativeRead(JNIEnv* env, jclass, jintArray pixels,
                                                                               jint width, jint height,
                                                                               jint text_height) {
  std::lock_guard<std::mutex> guard(g_lock);
  if (!g_ready || pixels == nullptr || width <= 0 || height <= 0) return nullptr;
  const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
  if (static_cast<size_t>(env->GetArrayLength(pixels)) < count) return nullptr;

  // The library reads only the gray of a picture, so that is what it is given: its own formula, a quarter of
  // the bytes, and read where the array lies rather than from a copy of it.
  std::vector<uint8_t> gray(count);
  const jint* argb = static_cast<const jint*>(env->GetPrimitiveArrayCritical(pixels, nullptr));
  if (argb == nullptr) return nullptr;
  for (size_t i = 0; i < count; ++i) {
    const uint32_t p = static_cast<uint32_t>(argb[i]);
    gray[i] = static_cast<uint8_t>((77 * ((p >> 16) & 0xFF) + 150 * ((p >> 8) & 0xFF) + 29 * (p & 0xFF) + 128) >> 8);
  }
  env->ReleasePrimitiveArrayCritical(pixels, const_cast<jint*>(argb), JNI_ABORT);

  // How tall the smallest text is, when the probe saw it (tools/screenai/0006): text is looked for at the scale
  // that keeps it just tall enough to find, often a third of the pixels. A library built without it reads as before.
  if (g_api.text_height != nullptr) g_api.text_height(text_height > 0 ? text_height : 0);

  Bitmap bitmap{};
  bitmap.pixels = gray.data();
  bitmap.row_bytes = static_cast<size_t>(width);
  bitmap.color_type = 14;
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
