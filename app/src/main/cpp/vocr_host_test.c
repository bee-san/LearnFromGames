/* SPDX-License-Identifier: GPL-3.0-or-later */
/* lfg_vocr_host <recogniser.vocr> <spotter.vtsp> <frame.argb>
 * The frame is two big-endian int32 (width, height) then big-endian ARGB ints,
 * i.e. a gunzipped app/src/test/resources/screens file. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#include "lfg_vocr.h"

static void *slurp(const char *path, size_t *bytes) {
    FILE *f = fopen(path, "rb");
    void *data;
    long n;
    if (f == NULL) return NULL;
    fseek(f, 0, SEEK_END);
    n = ftell(f);
    fseek(f, 0, SEEK_SET);
    data = malloc((size_t)n);
    if (data != NULL && fread(data, 1, (size_t)n, f) != (size_t)n) {
        free(data);
        data = NULL;
    }
    fclose(f);
    *bytes = (size_t)n;
    return data;
}

static uint32_t be32(const unsigned char *p) { return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3]; }

int main(int argc, char **argv) {
    size_t rec_bytes, spot_bytes, frame_bytes, i;
    void *rec, *spot;
    unsigned char *raw;
    int32_t *argb;
    unsigned width, height;
    lfg_vocr *v;
    char out[16384];
    int count, run;
    struct timespec a, b;

    if (argc != 4) {
        fprintf(stderr, "usage: %s recogniser.vocr spotter.vtsp frame.argb\n", argv[0]);
        return 2;
    }
    rec = slurp(argv[1], &rec_bytes);
    spot = slurp(argv[2], &spot_bytes);
    raw = slurp(argv[3], &frame_bytes);
    if (rec == NULL || spot == NULL || raw == NULL || frame_bytes < 8) return 1;
    width = be32(raw);
    height = be32(raw + 4);
    argb = malloc((size_t)width * height * 4);
    for (i = 0; i < (size_t)width * height; i++) argb[i] = (int32_t)be32(raw + 8 + i * 4);
    v = lfg_vocr_open(rec, rec_bytes, spot, spot_bytes);
    if (v == NULL) {
        fprintf(stderr, "could not open the models\n");
        return 1;
    }
    count = lfg_vocr_read(v, argb, width, height, out, sizeof out);
    clock_gettime(CLOCK_MONOTONIC, &a);
    for (run = 0; run < 20; run++) lfg_vocr_read(v, argb, width, height, out, sizeof out);
    clock_gettime(CLOCK_MONOTONIC, &b);
    printf("%d boxes, %.1f ms a frame\n%s", count,
           ((b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6) / 20, out);
    lfg_vocr_close(v);
    return 0;
}
