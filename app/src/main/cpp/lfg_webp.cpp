// SPDX-License-Identifier: GPL-3.0-or-later
// Animated WebP for card pictures, through libwebp's WebPAnimEncoder.
#include <jni.h>

#include <cstdint>
#include <vector>

#include "webp/encode.h"
#include "webp/mux.h"

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_capture_AnimatedWebp_nativeEncode(
    JNIEnv* env, jobject, jobjectArray frames, jint width, jint height, jintArray durations, jint quality) {
  const jsize count = env->GetArrayLength(frames);
  if (count < 1 || width < 1 || height < 1 || env->GetArrayLength(durations) < count) return nullptr;

  WebPAnimEncoderOptions options;
  WebPConfig config;
  if (!WebPAnimEncoderOptionsInit(&options) || !WebPConfigInit(&config)) return nullptr;
  options.anim_params.loop_count = 0;  // forever
  config.quality = static_cast<float>(quality);
  config.method = 2;  // quick: this runs while a game does
  WebPAnimEncoder* encoder = WebPAnimEncoderNew(width, height, &options);
  if (encoder == nullptr) return nullptr;

  jint* shown_for = env->GetIntArrayElements(durations, nullptr);
  const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);
  int timestamp = 0;
  bool ok = shown_for != nullptr;
  for (jsize i = 0; ok && i < count; ++i) {
    auto frame = static_cast<jintArray>(env->GetObjectArrayElement(frames, i));
    if (frame == nullptr || static_cast<size_t>(env->GetArrayLength(frame)) < pixels) {
      ok = false;
      break;
    }
    WebPPicture picture;
    if (!WebPPictureInit(&picture)) {
      ok = false;
      break;
    }
    picture.width = width;
    picture.height = height;
    picture.use_argb = 1;
    if (WebPPictureAlloc(&picture)) {
      // Java's ARGB ints are libwebp's argb layout exactly.
      for (jint y = 0; y < height; ++y) {
        env->GetIntArrayRegion(frame, y * width, width, reinterpret_cast<jint*>(picture.argb + y * picture.argb_stride));
      }
      ok = WebPAnimEncoderAdd(encoder, &picture, timestamp, &config) != 0;
    } else {
      ok = false;
    }
    WebPPictureFree(&picture);
    env->DeleteLocalRef(frame);
    timestamp += shown_for[i];
  }
  if (shown_for != nullptr) env->ReleaseIntArrayElements(durations, shown_for, JNI_ABORT);

  jbyteArray result = nullptr;
  WebPData data;
  WebPDataInit(&data);
  if (ok && WebPAnimEncoderAdd(encoder, nullptr, timestamp, nullptr) && WebPAnimEncoderAssemble(encoder, &data)) {
    result = env->NewByteArray(static_cast<jsize>(data.size));
    if (result != nullptr) env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size), reinterpret_cast<const jbyte*>(data.bytes));
  }
  WebPDataClear(&data);
  WebPAnimEncoderDelete(encoder);
  return result;
}
