// SPDX-License-Identifier: GPL-3.0-or-later
#include "lfg_avif.h"

#include <stdio.h>

#include "avif/avif.h"

int lfg_avif_write(const uint8_t* rgba, uint32_t width, uint32_t height, uint32_t stride, int quality, int speed,
                   int threads, const char* path) {
  if (rgba == NULL || path == NULL || width == 0 || height == 0 || stride < width * 4) return -1;
  avifImage* image = avifImageCreate(width, height, 8, AVIF_PIXEL_FORMAT_YUV420);
  avifEncoder* encoder = avifEncoderCreate();
  avifRWData output = AVIF_DATA_EMPTY;
  avifResult result = AVIF_RESULT_OUT_OF_MEMORY;
  if (image == NULL || encoder == NULL) goto done;

  // A screen is sRGB; said so, a viewer need not guess.
  image->colorPrimaries = AVIF_COLOR_PRIMARIES_BT709;
  image->transferCharacteristics = AVIF_TRANSFER_CHARACTERISTICS_SRGB;
  image->matrixCoefficients = AVIF_MATRIX_COEFFICIENTS_BT601;
  image->yuvRange = AVIF_RANGE_FULL;

  avifRGBImage rgb;
  avifRGBImageSetDefaults(&rgb, image);
  rgb.format = AVIF_RGB_FORMAT_RGBA;
  rgb.depth = 8;
  rgb.ignoreAlpha = AVIF_TRUE;  // a screenshot is opaque: no alpha plane is written
  rgb.pixels = (uint8_t*)rgba;  // only read
  rgb.rowBytes = stride;
  result = avifImageRGBToYUV(image, &rgb);
  if (result != AVIF_RESULT_OK) goto done;

  encoder->quality = quality;
  encoder->speed = speed;
  encoder->maxThreads = threads;
  encoder->autoTiling = AVIF_TRUE;  // tiles are what let several threads share one picture
  result = avifEncoderWrite(encoder, image, &output);
  if (result != AVIF_RESULT_OK) goto done;

  FILE* file = fopen(path, "wb");
  if (file == NULL) {
    result = AVIF_RESULT_IO_ERROR;
    goto done;
  }
  const int written = fwrite(output.data, 1, output.size, file) == output.size;
  if (fclose(file) != 0 || !written) {
    remove(path);
    result = AVIF_RESULT_IO_ERROR;
  }

done:
  avifRWDataFree(&output);
  if (encoder != NULL) avifEncoderDestroy(encoder);
  if (image != NULL) avifImageDestroy(image);
  return result == AVIF_RESULT_OK ? 0 : (int)result;
}
