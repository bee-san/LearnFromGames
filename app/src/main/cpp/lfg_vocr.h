/* SPDX-License-Identifier: GPL-3.0-or-later */
/* VOCR as the cheap first pass over a whole frame: the spotter finds text
 * boxes, the recogniser reads each horizontal one. JNI-free, so it runs in the
 * host test too. */
#ifndef LFG_VOCR_H
#define LFG_VOCR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct lfg_vocr lfg_vocr;

/* Copies both blobs. NULL when either is not a valid model. */
lfg_vocr *lfg_vocr_open(const void *recogniser, size_t recogniser_bytes, const void *spotter, size_t spotter_bytes);
void lfg_vocr_close(lfg_vocr *v);

/* Reads a frame of packed ARGB ints (as android.graphics.Bitmap.getPixels gives
 * them). The frame is shrunk by a whole factor until it fits 960 x 544, the
 * size the models were made for. Writes one line of UTF-8 per text box in
 * reading order, each as "x,y,w,h\ttext\n" in frame pixels; a vertical box has
 * no text. Returns the number of boxes, or a negative number on an error. */
int lfg_vocr_read(lfg_vocr *v, const int32_t *argb, unsigned width, unsigned height, char *out, size_t cap);

#ifdef __cplusplus
}
#endif

#endif
