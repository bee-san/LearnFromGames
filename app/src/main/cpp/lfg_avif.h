// SPDX-License-Identifier: GPL-3.0-or-later
// A still AVIF of an opaque 8-bit picture, through libavif and libaom. Free of JNI, so a host build can try it.
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Writes [rgba] (bytes R G B A, [stride] bytes a row, alpha ignored) to [path] as a still AVIF, 4:2:0,
// sRGB. [quality] is libavif's 0-100, [speed] its 0 (slowest) to 10, [threads] how many it may use.
// Returns 0 when written; otherwise a libavif error code (or -1), and nothing is left at [path].
int lfg_avif_write(const uint8_t* rgba, uint32_t width, uint32_t height, uint32_t stride, int quality, int speed,
                   int threads, const char* path);

#ifdef __cplusplus
}
#endif
