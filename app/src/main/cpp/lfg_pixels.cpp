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

// The pixels as they are stored, each as 0xAARRGGBB, written into [out] in one pass. Bitmap.getPixels
// gives the same for an opaque sRGB picture, but goes over a screen's two million one by one through
// Skia, converting a wide-gamut screen's colours to sRGB on the way, which took three times as long as
// looking for text in them and changes nothing a reader of text needs. Returns 0 for a picture that
// cannot be read or does not fit [out], 1 for an opaque one, 2 for one see-through somewhere (whose
// colours are stored multiplied by their alpha, and are left so).
extern "C" JNIEXPORT jint JNICALL Java_io_github_beesan_lfg_capture_Pixels_nativeArgb(
    JNIEnv* env, jclass, jobject bitmap, jintArray out) {
  Locked locked(env, bitmap);
  if (!locked.pixels) return 0;
  const size_t width = locked.info.width;
  const size_t height = locked.info.height;
  if (static_cast<size_t>(env->GetArrayLength(out)) < width * height) return 0;
  auto* to = static_cast<uint32_t*>(env->GetPrimitiveArrayCritical(out, nullptr));
  if (!to) return 0;
  uint32_t opaque = 0xFF000000u;
  for (size_t y = 0; y < height; y++) {
    const uint8_t* row = locked.pixels + y * locked.info.stride;
    uint32_t* line = to + y * width;
    for (size_t x = 0; x < width; x++) {
      // The bytes are R G B A, which a little-endian machine reads as 0xAABBGGRR: red and blue change places.
      uint32_t p;
      std::memcpy(&p, row + x * 4, 4);
      opaque &= p;
      line[x] = (p & 0xFF00FF00u) | ((p & 0x000000FFu) << 16) | ((p >> 16) & 0x000000FFu);
    }
  }
  env->ReleasePrimitiveArrayCritical(out, to, 0);
  return opaque == 0xFF000000u ? 1 : 2;
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
