#!/usr/bin/env python3
"""Draws the game screens the reader tests run on.

Android unit tests cannot use java.awt or javax.imageio, so each screen is
stored as gzip'd raw pixels: two big-endian int32 (width, height), then
width*height big-endian ARGB ints.

    python3 tools/make_test_screens.py
"""
import gzip
import struct
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "app/src/test/resources/screens"
FIRST = "昨日の夜、森の奥で不思議な光を見たんだ。"
SECOND = "でも、近づいたら消えてしまった。"


def font(size):
    path = subprocess.run(
        ["fc-match", "-f", "%{file}", "Noto Sans CJK JP:lang=ja"], capture_output=True, text=True, check=True
    ).stdout
    return ImageFont.truetype(path, size)


def screen(lines, vertical=None):
    image = Image.new("RGB", (1920, 1080), (0x1B, 0x2A, 0x36))
    draw = ImageDraw.Draw(image)
    draw.rectangle((80, 760, 1840, 1020), fill=(0x0C, 0x14, 0x1B))
    f = font(56)
    for i, line in enumerate(lines):
        draw.text((140, 800 + i * 90), line, font=f, fill=(0xF3, 0xF5, 0xF7))
    if vertical:
        for i, ch in enumerate(vertical):
            draw.text((1700, 80 + i * 62), ch, font=f, fill=(0xF3, 0xF5, 0xF7))
    return image


def save(name, image):
    rgba = image.convert("RGBA")
    r, g, b, a = rgba.split()
    argb = Image.merge("RGBA", (a, r, g, b)).tobytes()
    OUT.mkdir(parents=True, exist_ok=True)
    with gzip.GzipFile(OUT / f"{name}.argb.gz", "wb", mtime=0) as out:
        out.write(struct.pack(">ii", *image.size))
        out.write(argb)


save("dialogue", screen([FIRST, SECOND]))
save("vertical", screen(["今日はいい天気ですね。"], vertical="不思議な光を見た"))
save("empty", screen([]))
