#!/usr/bin/env bash
# Builds libchromescreenai.so for Android from AuroraWright/screenai_ocr (MPL-2.0), a C++ reimplementation of the
# OCR engine in Chrome's Screen AI component, on top of TensorFlow Lite 2.21.0.
#
#   tools/build_screenai.sh [WORK_DIR] [OUT_DIR]
#
#   WORK_DIR  downloads, sources and build trees (default: ${XDG_CACHE_HOME:-$HOME/.cache}/lfg-screenai)
#   OUT_DIR   receives <abi>/libchromescreenai.so (default: <repo>/app/src/main/jniLibs)
#
# Environment:
#   ANDROID_NDK or ANDROID_NDK_HOME   the NDK to build with (required; developed with r29)
#   ABIS                              space separated, default "arm64-v8a x86_64"
#   ANDROID_PLATFORM                  default android-33 (the app's minSdk)
#   JOBS                              parallel compile jobs, default: number of CPUs
#
# Needs on the host: bash, curl, tar, patch, sha256sum, python3 (XNNPACK's cmake uses it), cmake >= 3.20, ninja
# and a C/C++ compiler (only for flatc, which TFLite's cross build runs on the host).
#
# The recipe is the one in screenai_ocr's README, which matters for the results: TensorFlow 2.21.0's
# tensorflow/lite, the exact XNNPACK revision of the original library with that revision's cpuinfo, pthreadpool
# and kleidiai pins, everything compiled with -ffp-contract=off (no fused multiply-adds). Deviations from it are
# marked "DEVIATION" below. The Screen AI model files are NOT built, downloaded or bundled by this script.
#
# Every step leaves a stamp in WORK_DIR and is skipped on the next run; delete WORK_DIR (or WORK_DIR/build) to
# start over. If tools/screenai/*.patch changes after a source tree was patched, the script stops and asks for
# that tree to be deleted rather than patching it twice.
#
# Patches (tools/screenai/):
#   tflite-0001-android-do-not-force-ruy.patch            TFLite: keep Eigen for the builtin kernels on Android
#   0001-keep-first-pass-when-script-recognizer-missing   screenai_ocr: a line whose script recogniser is not
#                                                         installed keeps its first-pass text; the page still reads
#   0002-eigen-subset-without-vectorization-off-arm64     screenai_ocr: its Eigen subset lacks the x86 headers
#   0003-fast-mode-and-gray-input                         screenai_ocr: SetOCRFastMode, the same lines, text and
#                                                         boxes for about half the work; gray pixels as they are
#   0004-recogniser-threads-off-the-slow-cores            screenai_ocr: fast mode's recognizer threads ask for
#                                                         the faster cores of a phone's processor
#   0005-language-given-and-lines-remembered              screenai_ocr: SetOCRLanguage, one pass by the language's
#                                                         recognizer; fast mode remembers the lines it read
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PATCH_DIR="$SCRIPT_DIR/screenai"

WORK="${1:-${XDG_CACHE_HOME:-$HOME/.cache}/lfg-screenai}"
OUT="${2:-$REPO_DIR/app/src/main/jniLibs}"
ABIS="${ABIS:-arm64-v8a x86_64}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-33}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"
NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}"

die() { echo "build_screenai: $*" >&2; exit 1; }
log() { echo "==> $*" >&2; }

[ -n "$NDK" ] || die "set ANDROID_NDK (or ANDROID_NDK_HOME) to an Android NDK directory"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[ -f "$TOOLCHAIN" ] || die "no build/cmake/android.toolchain.cmake under $NDK"
for tool in curl tar patch sha256sum python3 cmake ninja; do
  command -v "$tool" >/dev/null || die "missing host tool: $tool"
done
# `command -v` resolves the real binary even where an interactive shell aliases ninja.
NINJA="$(command -v ninja)"
LLVM_BIN="$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin)"
[ -x "$LLVM_BIN/llvm-strip" ] || die "no llvm-strip under $NDK/toolchains/llvm/prebuilt"

mkdir -p "$WORK" "$OUT"
WORK="$(cd "$WORK" && pwd)"
OUT="$(cd "$OUT" && pwd)"
DL="$WORK/dl" SRC="$WORK/src" BUILD="$WORK/build"
mkdir -p "$DL" "$SRC" "$BUILD"

# ---------------------------------------------------------------------------------------------------------------
# Pinned sources. Each is fetched as the archive of one exact tag/commit and checked against its SHA-256.
# The cpuinfo/pthreadpool/kleidiai URLs and hashes are the ones XNNPACK 28d1fc6d itself pins in cmake/Download*.
# ---------------------------------------------------------------------------------------------------------------
TF_VERSION=2.21.0
XNNPACK_REV=28d1fc6d908bc4d80920be989177b7eb1a4aebc8
CPUINFO_REV=66ee79c038d70dad9f08705b2c9b3e58f6d8f512
PTHREADPOOL_REV=15a6644ba1c45f1acc16ac1e883efc3e56c6bed2
KLEIDIAI_REV=5147983e14600ce7f8a6583dc04ba07753ac54c4
SCREENAI_REV=b81bd14168ddf93082024a787a86b21166d416cd

TF_DIR="$SRC/tensorflow-$TF_VERSION"
XNNPACK_DIR="$SRC/XNNPACK-$XNNPACK_REV"
CPUINFO_DIR="$SRC/cpuinfo-$CPUINFO_REV"
PTHREADPOOL_DIR="$SRC/pthreadpool-$PTHREADPOOL_REV"
KLEIDIAI_DIR="$SRC/kleidiai-$KLEIDIAI_REV"
SCREENAI_DIR="$SRC/screenai_ocr-$SCREENAI_REV"

# fetch <file name> <url> <sha256>: download into $DL unless a file with the right hash is already there.
fetch() {
  local file="$DL/$1" url="$2" sha="$3"
  if [ -f "$file" ] && echo "$sha  $file" | sha256sum -c --quiet - 2>/dev/null; then return; fi
  log "downloading $1"
  curl -fL --retry 3 -o "$file.part" "$url"
  echo "$sha  $file.part" | sha256sum -c --quiet - || die "checksum mismatch for $url"
  mv "$file.part" "$file"
}

# unpack <archive> <resulting dir> [members...]: extract into $SRC once (cmake -E tar reads .tar.gz and .zip).
unpack() {
  local archive="$DL/$1" dir="$2"
  shift 2
  [ -f "$dir/.unpacked" ] && return
  log "unpacking $(basename "$archive")"
  rm -rf "$dir"
  (cd "$SRC" && cmake -E tar xf "$archive" "$@")
  [ -d "$dir" ] || die "$(basename "$archive") did not contain $(basename "$dir")"
  touch "$dir/.unpacked"
}

# apply_patches <dir> <glob>: apply tools/screenai patches once; a changed patch set re-extracts nothing, so it is
# refused rather than applied on top of an already patched tree.
apply_patches() {
  local dir="$1" pattern="$2" sum p
  sum="$(cat "$PATCH_DIR"/$pattern 2>/dev/null | sha256sum | cut -d' ' -f1)"
  if [ -f "$dir/.patched" ]; then
    [ "$(cat "$dir/.patched")" = "$sum" ] && return
    die "$dir was patched with a different patch set; delete it and run again"
  fi
  for p in "$PATCH_DIR"/$pattern; do
    [ -f "$p" ] || continue
    log "applying $(basename "$p")"
    patch -d "$dir" -p1 --forward --no-backup-if-mismatch <"$p"
  done
  echo "$sum" >"$dir/.patched"
}

fetch "tensorflow-$TF_VERSION.tar.gz" "https://github.com/tensorflow/tensorflow/archive/refs/tags/v$TF_VERSION.tar.gz" \
  ef3568bb4865d6c1b2564fb5689c19b6b9a5311572cd1f2ff9198636a8520921
fetch "XNNPACK-$XNNPACK_REV.tar.gz" "https://github.com/google/XNNPACK/archive/$XNNPACK_REV.tar.gz" \
  4402e92cd6432de6cc527a62d7131063772d2e61f37eed19dc0f51b22d0f3a70
fetch "cpuinfo-$CPUINFO_REV.zip" "https://github.com/pytorch/cpuinfo/archive/$CPUINFO_REV.zip" \
  b93044263ee072e9be3577bda52f48d5a4dd9fcb80091529471c8d2bfe59219d
fetch "pthreadpool-$PTHREADPOOL_REV.zip" "https://github.com/google/pthreadpool/archive/$PTHREADPOOL_REV.zip" \
  9b9fb1179b71021c0c048504eab636424c58e9c8374404754ece6d7cd90f26d4
fetch "kleidiai-$KLEIDIAI_REV.zip" \
  "https://gitlab.arm.com/kleidi/kleidiai/-/archive/$KLEIDIAI_REV/kleidiai-$KLEIDIAI_REV.zip" \
  a424e81535ebb6d0117e9282fba45d70aee0bedb481f603df55e1805bed0e21b
fetch "screenai_ocr-$SCREENAI_REV.tar.gz" "https://github.com/AuroraWright/screenai_ocr/archive/$SCREENAI_REV.tar.gz" \
  75df0a005d0ca4a7f4bdfaacc6fbf1275d23d054e1fb6aadb45fdf82708e15c8

# Only the parts of the TensorFlow tree that tensorflow/lite's CMake build reads (the whole tree is 400 MB).
unpack "tensorflow-$TF_VERSION.tar.gz" "$TF_DIR" \
  "tensorflow-$TF_VERSION/LICENSE" \
  "tensorflow-$TF_VERSION/tensorflow/lite" \
  "tensorflow-$TF_VERSION/tensorflow/core" \
  "tensorflow-$TF_VERSION/tensorflow/compiler/mlir/lite" \
  "tensorflow-$TF_VERSION/third_party"
unpack "XNNPACK-$XNNPACK_REV.tar.gz" "$XNNPACK_DIR"
unpack "cpuinfo-$CPUINFO_REV.zip" "$CPUINFO_DIR"
unpack "pthreadpool-$PTHREADPOOL_REV.zip" "$PTHREADPOOL_DIR"
unpack "kleidiai-$KLEIDIAI_REV.zip" "$KLEIDIAI_DIR"
unpack "screenai_ocr-$SCREENAI_REV.tar.gz" "$SCREENAI_DIR"

# tools/screenai/tflite-*.patch -> TensorFlow, tools/screenai/0*.patch -> screenai_ocr (see the files' headers).
apply_patches "$TF_DIR" 'tflite-*.patch'
apply_patches "$SCREENAI_DIR" '0*.patch'

TFLITE_SRC="$TF_DIR/tensorflow/lite"

# Arguments shared by the host flatc build and both TFLite builds.
#  - CMAKE_POLICY_VERSION_MINIMUM: some of TFLite's dependencies still declare cmake_minimum_required < 3.5,
#    which CMake 4 refuses.
#  - OVERRIDABLE_FETCH_CONTENT_GIT_REPOSITORY_AND_TAG_TO_URL: TFLite's remaining dependencies (abseil, eigen,
#    flatbuffers, ruy, protobuf, ...) are fetched by its own CMake at the tags/commits TF 2.21.0 pins; this makes
#    it download each as an archive of that commit instead of cloning the repository.
COMMON_ARGS=(
  -G Ninja "-DCMAKE_MAKE_PROGRAM=$NINJA"
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5
  -DOVERRIDABLE_FETCH_CONTENT_GIT_REPOSITORY_AND_TAG_TO_URL=ON
  -Wno-dev -Wno-deprecated
)

# ---------------------------------------------------------------------------------------------------------------
# Host flatc. TFLite generates a flatbuffers header during its build, and a cross build cannot run its own flatc.
# TFLite ships a small CMake project for exactly this; it installs to <build>/flatbuffers-flatc/bin/flatc.
# ---------------------------------------------------------------------------------------------------------------
FLATC_BUILD="$BUILD/host-flatc"
if [ ! -x "$FLATC_BUILD/flatbuffers-flatc/bin/flatc" ]; then
  log "building host flatc"
  cmake -S "$TFLITE_SRC/tools/cmake/native_tools/flatbuffers" -B "$FLATC_BUILD" "${COMMON_ARGS[@]}"
  cmake --build "$FLATC_BUILD" -j "$JOBS"
fi
[ -x "$FLATC_BUILD/flatbuffers-flatc/bin/flatc" ] || die "host flatc was not built"

# ---------------------------------------------------------------------------------------------------------------
# Per ABI: TensorFlow Lite (static libraries), then libchromescreenai.so.
# ---------------------------------------------------------------------------------------------------------------
build_abi() {
  local abi="$1"
  local tflite_build="$BUILD/$abi/tflite" screenai_build="$BUILD/$abi/screenai"
  local android_args=(
    "-DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN"
    "-DANDROID_ABI=$abi"
    "-DANDROID_PLATFORM=$ANDROID_PLATFORM"
    # libc++ is linked statically into the one shared library, so the APK needs no libc++_shared.so.
    -DANDROID_STL=c++_static
  )

  # README step 1. Beyond the README's arguments:
  #  - TENSORFLOW_SOURCE_DIR: DEVIATION. Without it TFLite's CMake clones TensorFlow v2.19.0 for the few
  #    non-lite files it compiles (tensorflow/compiler/mlir/lite: model loading and schema helpers). Here they come
  #    from the same 2.21.0 tree. No kernel or delegate code is involved.
  #  - TFLITE_ENABLE_RUY=OFF, honoured thanks to tflite-0001-android-do-not-force-ruy.patch: upstream forces Ruy
  #    on for Android, which would make the builtin (non-XNNPACK) kernels use other matrix code than the desktop
  #    builds the README describes. With the patch they use Eigen, as there.
  #  - NNAPI, GPU and the external delegate are not used by screenai_ocr and are left out.
  if [ ! -f "$tflite_build/.configured" ]; then
    log "[$abi] configuring TensorFlow Lite"
    cmake -S "$TFLITE_SRC" -B "$tflite_build" "${COMMON_ARGS[@]}" "${android_args[@]}" \
      -DCMAKE_C_FLAGS=-ffp-contract=off -DCMAKE_CXX_FLAGS=-ffp-contract=off \
      "-DTENSORFLOW_SOURCE_DIR=$TF_DIR" \
      "-DTFLITE_HOST_TOOLS_DIR=$FLATC_BUILD" \
      -DTFLITE_ENABLE_XNNPACK=ON -DTFLITE_ENABLE_RUY=OFF \
      -DTFLITE_ENABLE_NNAPI=OFF -DTFLITE_ENABLE_GPU=OFF -DTFLITE_ENABLE_EXTERNAL_DELEGATE=OFF \
      "-DFETCHCONTENT_SOURCE_DIR_XNNPACK=$XNNPACK_DIR" \
      "-DCPUINFO_SOURCE_DIR=$CPUINFO_DIR" \
      "-DPTHREADPOOL_SOURCE_DIR=$PTHREADPOOL_DIR" \
      "-DKLEIDIAI_SOURCE_DIR=$KLEIDIAI_DIR"
    touch "$tflite_build/.configured"
  fi
  log "[$abi] building TensorFlow Lite"
  cmake --build "$tflite_build" --target tensorflow-lite -j "$JOBS"

  # README step 2, with only the `chromescreenai` target. screenai_ocr's CMake links every static library found
  # under TFLITE_BUILD_DIR, so nothing else may be built in that tree.
  #  - -llog: TFLite logs through the Android log on Android.
  #  - max-page-size=16384: 16 KB page alignment (the NDK default since r28, stated here so older NDKs comply).
  #  - the project's own version script (cpp/capi/exports.map) keeps only the C API visible.
  if [ ! -f "$screenai_build/.configured" ]; then
    log "[$abi] configuring screenai_ocr"
    cmake -S "$SCREENAI_DIR/cpp" -B "$screenai_build" -G Ninja "-DCMAKE_MAKE_PROGRAM=$NINJA" \
      -DCMAKE_BUILD_TYPE=Release "${android_args[@]}" \
      "-DTFLITE_SOURCE_DIR=$TF_DIR" "-DTFLITE_BUILD_DIR=$tflite_build" \
      -DCMAKE_CXX_STANDARD_LIBRARIES=-llog \
      "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--build-id=none"
    touch "$screenai_build/.configured"
  fi
  log "[$abi] building libchromescreenai.so"
  cmake --build "$screenai_build" --target chromescreenai -j "$JOBS"

  mkdir -p "$OUT/$abi"
  "$LLVM_BIN/llvm-strip" --strip-unneeded -o "$OUT/$abi/libchromescreenai.so" "$screenai_build/libchromescreenai.so"
  check_library "$abi" "$OUT/$abi/libchromescreenai.so"
  log "[$abi] wrote $OUT/$abi/libchromescreenai.so ($(wc -c <"$OUT/$abi/libchromescreenai.so") bytes)"
}

# The checks a broken build would fail: right machine, 16 KB LOAD alignment, the C API exported, and no
# dependency on a library Android does not ship.
check_library() {
  local abi="$1" so="$2" machine sym needed
  case "$abi" in
    arm64-v8a) machine=AArch64 ;;
    x86_64) machine=X86-64 ;;
    armeabi-v7a) machine=ARM ;;
    x86) machine=Intel\ 80386 ;;
    *) die "unknown ABI $abi" ;;
  esac
  # Each tool's output is read whole before it is searched: under pipefail, `tool | grep -q` fails whenever grep
  # stops at its match before the tool has finished writing.
  local header segments symbols
  header="$("$LLVM_BIN/llvm-readelf" -h "$so")"
  grep -q "Machine:.*$machine" <<<"$header" || die "$so is not $machine"
  segments="$("$LLVM_BIN/llvm-readelf" -lW "$so")"
  if awk '$1 == "LOAD" && $NF != "0x4000" { bad = 1 } END { exit !bad }' <<<"$segments"; then
    die "$so has a LOAD segment that is not 16 KB aligned"
  fi
  symbols="$("$LLVM_BIN/llvm-nm" -D --defined-only "$so")"
  for sym in SetFileContentFunctions InitOCRUsingCallback SetOCRLightMode SetOCRFastMode SetOCRLanguage GetMaxImageDimension PerformOCR \
    FreeLibraryAllocatedCharArray UninitializeOCR; do
    grep -q " T $sym\$" <<<"$symbols" || die "$so does not export $sym"
  done
  needed="$("$LLVM_BIN/llvm-readelf" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
  for lib in $needed; do
    case "$lib" in
      libc.so | libm.so | libdl.so | liblog.so | libandroid.so) ;;
      *) die "$so needs $lib, which is not an Android system library" ;;
    esac
  done
}

for abi in $ABIS; do
  build_abi "$abi"
done
log "done"
