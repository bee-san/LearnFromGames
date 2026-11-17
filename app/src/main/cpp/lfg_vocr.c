/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "lfg_vocr.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "vocr.h"
#include "vocr_lines.h"
#include "vocr_textspot6.h"

#define FIT_WIDTH 960u
#define FIT_HEIGHT 544u

struct lfg_vocr {
    void *rec_blob;
    void *spot_blob;
    vocr_model model;
    vocr_ctx ctx;
    void *rec_arena;
    vocr_textspot6 spotter;
    void *spot_arena;
    size_t spot_arena_bytes;
    unsigned char *grey;
    size_t grey_bytes;
    VocrTextspot6Set set;
};

static void *aligned(size_t bytes) {
    void *p = NULL;
    if (bytes == 0) bytes = 16;
    return posix_memalign(&p, 16, (bytes + 15) & ~(size_t)15) == 0 ? p : NULL;
}

lfg_vocr *lfg_vocr_open(const void *recogniser, size_t recogniser_bytes, const void *spotter, size_t spotter_bytes) {
    lfg_vocr *v;
    vocr_opts opts;
    size_t arena_bytes;

    if (recogniser == NULL || spotter == NULL) return NULL;
    v = calloc(1, sizeof *v);
    if (v == NULL) return NULL;
    v->rec_blob = aligned(recogniser_bytes);
    v->spot_blob = aligned(spotter_bytes);
    if (v->rec_blob == NULL || v->spot_blob == NULL) goto fail;
    memcpy(v->rec_blob, recogniser, recogniser_bytes);
    memcpy(v->spot_blob, spotter, spotter_bytes);

    if (vocr_load(&v->model, v->rec_blob, recogniser_bytes) != VOCR_OK) goto fail;
    if (vocr_check_crc(v->rec_blob, recogniser_bytes) != VOCR_OK) goto fail;
    memset(&opts, 0, sizeof opts);
    opts.mode = VOCR_MODE_W8I;
    /* Speed-only sections some blobs carry; the text is the same without them. */
    if (vocr_has_gate(&v->model)) opts.head_gate = VOCR_HEAD_GATE_ON;
    if (vocr_has_two_level(&v->model)) opts.two_level = 1;
    arena_bytes = vocr_arena_bytes(&v->model, &opts);
    if (arena_bytes == 0) goto fail;
    v->rec_arena = aligned(arena_bytes);
    if (v->rec_arena == NULL) goto fail;
    if (vocr_init(&v->ctx, &v->model, &opts, v->rec_arena, arena_bytes) != VOCR_OK) goto fail;

    if (vocr_textspot6_load(&v->spotter, v->spot_blob, spotter_bytes) != VOCR_LINES_OK) goto fail;
    v->spot_arena_bytes = vocr_textspot6_arena_bytes(&v->spotter, FIT_WIDTH, FIT_HEIGHT);
    if (v->spot_arena_bytes == 0) goto fail;
    v->spot_arena = aligned(v->spot_arena_bytes);
    if (v->spot_arena == NULL) goto fail;
    return v;

fail:
    lfg_vocr_close(v);
    return NULL;
}

void lfg_vocr_close(lfg_vocr *v) {
    if (v == NULL) return;
    free(v->rec_blob);
    free(v->spot_blob);
    free(v->rec_arena);
    free(v->spot_arena);
    free(v->grey);
    free(v);
}

/* Grey, as the runtime defines it, of the mean of each factor x factor block. */
static void shrink(const int32_t *argb, unsigned width, unsigned factor, unsigned w, unsigned h, unsigned char *grey) {
    unsigned x, y, dx, dy;
    const unsigned n = factor * factor;
    for (y = 0; y < h; y++) {
        for (x = 0; x < w; x++) {
            unsigned r = 0, g = 0, b = 0;
            for (dy = 0; dy < factor; dy++) {
                const int32_t *row = argb + (size_t)(y * factor + dy) * width + (size_t)x * factor;
                for (dx = 0; dx < factor; dx++) {
                    const uint32_t p = (uint32_t)row[dx];
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                }
            }
            grey[(size_t)y * w + x] = (unsigned char)((77u * (r / n) + 150u * (g / n) + 29u * (b / n)) >> 8);
        }
    }
}

int lfg_vocr_read(lfg_vocr *v, const int32_t *argb, unsigned width, unsigned height, char *out, size_t cap) {
    unsigned factor = 1, w, h, i;
    size_t used = 0;
    VocrLinesImage frame;

    if (v == NULL || argb == NULL || out == NULL || cap == 0 || width == 0 || height == 0) return -1;
    out[0] = 0;
    while ((width + factor - 1) / factor > FIT_WIDTH || (height + factor - 1) / factor > FIT_HEIGHT) factor++;
    w = width / factor;
    h = height / factor;
    if (w < 8 || h < 8) return 0;
    if (v->grey_bytes < (size_t)w * h) {
        free(v->grey);
        v->grey = malloc((size_t)w * h);
        v->grey_bytes = v->grey != NULL ? (size_t)w * h : 0;
        if (v->grey == NULL) return -2;
    }
    shrink(argb, width, factor, w, h, v->grey);

    frame.pixels = v->grey;
    frame.width = w;
    frame.height = h;
    frame.stride = w;
    frame.bpp = VOCR_LINES_GREY;
    if (vocr_textspot6_find(&v->spotter, &frame, 0, v->spot_arena, v->spot_arena_bytes, &v->set) != VOCR_LINES_OK) return -3;

    for (i = 0; i < v->set.count; i++) {
        const VocrTextspot6Box *box = &v->set.boxes[i];
        char text[1024];
        int n;
        text[0] = 0;
        if (!box->vertical) {
            vocr_image line;
            line.pixels = v->grey + (size_t)box->y * w + box->x;
            line.width = box->w;
            line.height = box->h;
            line.stride = w;
            line.format = VOCR_GREY8 | (box->dark ? VOCR_INVERT : 0);
            /* A line the recogniser cannot take (too wide, say) still counts as a box with no text. */
            if (vocr_run(&v->ctx, &line, text, sizeof text, NULL) != VOCR_OK) text[0] = 0;
        }
        n = snprintf(out + used, cap - used, "%u,%u,%u,%u\t%s\n", box->x * factor, box->y * factor, box->w * factor,
                     box->h * factor, text);
        if (n < 0 || (size_t)n >= cap - used) {
            out[used] = 0;
            break;
        }
        used += (size_t)n;
    }
    return (int)v->set.count;
}
