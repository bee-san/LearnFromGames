// SPDX-License-Identifier: GPL-3.0-or-later
// A screen's pixels read where Android keeps them, without copying them into Java first:
// a fingerprint for "is this the last picture again?", and a still WebP at a chosen speed.
#include <android/bitmap.h>
#include <jni.h>
#include <cstdint>
#include <cstring>

#include "webp/encode.h"

namespace {

// Locks an ARGB_8888 bitmap's pixels for the life of the object.
struct Locked {
  JNIEnv* env;
  jobject bitmap;
  AndroidBitmapInfo info{};
  const uint8_t* pixels = nullptr;

  Locked(JNIEnv* e, jobject b) : env(e), bitmap(b) {
    void* p = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
        info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
        AndroidBitmap_lockPixels(env, bitmap, &p) == ANDROID_BITMAP_RESULT_SUCCESS) {
      pixels = static_cast<const uint8_t*>(p);
    }
  }
  ~Locked() {
    if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
  }
};

}  // namespace

// FNV-1a over 64-bit words of every [step]th row. Two pictures alike in every sampled row are
// taken as the same picture: text is taller than four rows, so no change to it hides between them.
extern "C" JNIEXPORT jlong JNICALL Java_io_github_beesan_lfg_capture_Pixels_nativeRowsHash(
    JNIEnv* env, jclass, jobject bitmap, jint step) {
  Locked locked(env, bitmap);
  if (!locked.pixels || step < 1) return 0;
  uint64_t hash = 1469598103934665603ULL;
  const size_t words = locked.info.width * 4 / 8;
  for (uint32_t y = 0; y < locked.info.height; y += step) {
    const uint8_t* row = locked.pixels + static_cast<size_t>(y) * locked.info.stride;
    for (size_t i = 0; i < words; i++) {
      uint64_t w;
      std::memcpy(&w, row + i * 8, 8);
      hash = (hash ^ w) * 1099511628211ULL;
    }
    // An odd width leaves one pixel past the last whole word.
    if (locked.info.width % 2) {
      uint32_t last;
      std::memcpy(&last, row + words * 8, 4);
      hash = (hash ^ last) * 1099511628211ULL;
    }
  }
  return static_cast<jlong>(hash | 1);  // never 0, which means "could not read"
}

// A still lossy WebP. [method] is libwebp's speed-for-size trade, 0 (fastest) to 6; Android's own
// encoder always takes the slowest. Returns null when the bitmap cannot be read or encoded.
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_capture_Pixels_nativeEncodeWebp(
    JNIEnv* env, jclass, jobject bitmap, jint quality, jint method) {
  WebPConfig config;
  WebPPicture picture;
  if (!WebPConfigInit(&config) || !WebPPictureInit(&picture)) return nullptr;
  config.quality = static_cast<float>(quality);
  config.method = method;
  if (!WebPValidateConfig(&config)) return nullptr;
  WebPMemoryWriter writer;
  WebPMemoryWriterInit(&writer);
  bool ok = false;
  {
    Locked locked(env, bitmap);
    if (!locked.pixels) return nullptr;
    picture.width = static_cast<int>(locked.info.width);
    picture.height = static_cast<int>(locked.info.height);
    // A screenshot is opaque, and libwebp encodes faster with no alpha to keep.
    ok = WebPPictureImportRGBX(&picture, locked.pixels, static_cast<int>(locked.info.stride)) != 0;
  }
  if (ok) {
    picture.writer = WebPMemoryWrite;
    picture.custom_ptr = &writer;
    ok = WebPEncode(&config, &picture) != 0;
  }
  WebPPictureFree(&picture);
  jbyteArray out = nullptr;
  if (ok) {
    out = env->NewByteArray(static_cast<jsize>(writer.size));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(writer.size), reinterpret_cast<const jbyte*>(writer.mem));
  }
  WebPMemoryWriterClear(&writer);
  return out;
}
