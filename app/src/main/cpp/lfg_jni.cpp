// SPDX-License-Identifier: GPL-3.0-or-later
// Thin JNI layer. Text crosses as UTF-8 byte arrays, never as jstring: JNI's
// string functions speak modified UTF-8, which mangles kanji outside the BMP.
#include <jni.h>

#include <string>

#include "lfg_dict.h"

namespace {

std::string to_string(JNIEnv* env, jbyteArray bytes) {
  if (bytes == nullptr) return {};
  const jsize len = env->GetArrayLength(bytes);
  std::string out(static_cast<size_t>(len), '\0');
  env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(out.data()));
  return out;
}

jbyteArray to_bytes(JNIEnv* env, const std::string& s) {
  jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
  if (out != nullptr) env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
  return out;
}

lfg::Session* session(jlong handle) { return reinterpret_cast<lfg::Session*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_dict_Hoshidicts_nativeImport(JNIEnv* env, jobject, jbyteArray zip,
                                                                                    jbyteArray out_dir, jboolean low_ram) {
  return to_bytes(env, lfg::import_dictionary(to_string(env, zip), to_string(env, out_dir), low_ram == JNI_TRUE));
}

JNIEXPORT jlong JNICALL Java_io_github_beesan_lfg_dict_Hoshidicts_nativeOpen(JNIEnv*, jobject) {
  auto* s = new lfg::Session();
  if (!s->ok()) {
    delete s;
    return 0;
  }
  return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL Java_io_github_beesan_lfg_dict_Hoshidicts_nativeClose(JNIEnv*, jobject, jlong handle) {
  delete session(handle);
}

JNIEXPORT jboolean JNICALL Java_io_github_beesan_lfg_dict_Hoshidicts_nativeAddDictionary(JNIEnv* env, jobject, jlong handle,
                                                                                         jbyteArray path, jint kind) {
  if (handle == 0 || kind < 0 || kind > 3) return JNI_FALSE;
  return session(handle)->add_dictionary(to_string(env, path), static_cast<lfg::DictKind>(kind)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_dict_Hoshidicts_nativeAddLanguage(JNIEnv* env, jobject, jlong handle,
                                                                                         jbyteArray language,
                                                                                         jbyteArray transforms) {
  if (handle == 0) return to_bytes(env, "The dictionary engine did not start.");
  return to_bytes(env, session(handle)->add_language(to_string(env, language), to_string(env, transforms)));
}

JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_dict_Hoshidicts_nativeLookup(JNIEnv* env, jobject, jlong handle,
                                                                                    jbyteArray text, jint max_results,
                                                                                    jint scan_length, jbyteArray language) {
  if (handle == 0) return to_bytes(env, "{\"results\":[]}");
  return to_bytes(env, session(handle)->lookup(to_string(env, text), max_results, static_cast<size_t>(scan_length),
                                               to_string(env, language)));
}

JNIEXPORT jint JNICALL Java_io_github_beesan_lfg_dict_Hoshidicts_nativeMatchLength(JNIEnv* env, jobject, jlong handle,
                                                                                    jbyteArray text, jint scan_length,
                                                                                    jbyteArray language) {
  if (handle == 0) return 0;
  return session(handle)->match_length(to_string(env, text), static_cast<size_t>(scan_length), to_string(env, language));
}

JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_dict_Hoshidicts_nativeStyles(JNIEnv* env, jobject, jlong handle) {
  if (handle == 0) return to_bytes(env, "[]");
  return to_bytes(env, session(handle)->styles());
}

JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_dict_Hoshidicts_nativeMedia(JNIEnv* env, jobject, jlong handle,
                                                                                   jbyteArray dictionary, jbyteArray path) {
  if (handle == 0) return to_bytes(env, "");
  return to_bytes(env, session(handle)->media(to_string(env, dictionary), to_string(env, path)));
}

}  // extern "C"

// ── VOCR: the first pass ─────────────────────────────────────────────────────

#include <vector>

#include "lfg_vocr.h"

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_beesan_lfg_ocr_Vocr_nativeOpen(JNIEnv* env, jobject, jbyteArray recogniser,
                                                                      jbyteArray spotter) {
  const std::string rec = to_string(env, recogniser);
  const std::string spot = to_string(env, spotter);
  return reinterpret_cast<jlong>(lfg_vocr_open(rec.data(), rec.size(), spot.data(), spot.size()));
}

JNIEXPORT void JNICALL Java_io_github_beesan_lfg_ocr_Vocr_nativeClose(JNIEnv*, jobject, jlong handle) {
  lfg_vocr_close(reinterpret_cast<lfg_vocr*>(handle));
}

JNIEXPORT jbyteArray JNICALL Java_io_github_beesan_lfg_ocr_Vocr_nativeRead(JNIEnv* env, jobject, jlong handle,
                                                                           jintArray pixels, jint width, jint height) {
  if (handle == 0 || pixels == nullptr || width <= 0 || height <= 0) return to_bytes(env, "");
  if (static_cast<jlong>(env->GetArrayLength(pixels)) < static_cast<jlong>(width) * height) return to_bytes(env, "");
  std::vector<char> out(32 * 1024);
  jint* argb = env->GetIntArrayElements(pixels, nullptr);
  if (argb == nullptr) return to_bytes(env, "");
  const int count = lfg_vocr_read(reinterpret_cast<lfg_vocr*>(handle), reinterpret_cast<const int32_t*>(argb),
                                  static_cast<unsigned>(width), static_cast<unsigned>(height), out.data(), out.size());
  env->ReleaseIntArrayElements(pixels, argb, JNI_ABORT);
  return to_bytes(env, count > 0 ? std::string(out.data()) : std::string());
}

}  // extern "C"
