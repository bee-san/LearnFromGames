// SPDX-License-Identifier: GPL-3.0-or-later
// The JNI side of anki/Avif.kt: a bitmap's pixels, read where Android keeps them, written as a still AVIF.
#include <android/bitmap.h>
#include <jni.h>
#include <stdlib.h>

#include "lfg_avif.h"

// Returns 0 when [path] holds the picture, else an error code (nothing is left at [path]).
// The path crosses as UTF-8 bytes, never as jstring (modified UTF-8), as lfg_jni.cpp does.
JNIEXPORT jint JNICALL Java_io_github_beesan_lfg_anki_Avif_nativeWrite(JNIEnv* env, jclass clazz, jobject bitmap,
                                                                       jbyteArray path, jint quality, jint speed,
                                                                       jint threads) {
  (void)clazz;
  if (bitmap == NULL || path == NULL) return -1;
  const jsize length = (*env)->GetArrayLength(env, path);
  char* name = malloc((size_t)length + 1);
  if (name == NULL) return -1;
  (*env)->GetByteArrayRegion(env, path, 0, length, (jbyte*)name);
  name[length] = '\0';

  int result = -1;
  AndroidBitmapInfo info;
  void* pixels = NULL;
  if (AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
      info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
      AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
    result = lfg_avif_write((const uint8_t*)pixels, info.width, info.height, info.stride, quality, speed, threads, name);
    AndroidBitmap_unlockPixels(env, bitmap);
  }
  free(name);
  return result;
}
