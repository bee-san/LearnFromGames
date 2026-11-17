#!/usr/bin/env python3
"""Draws a corpus of harder game screens with their true text, for comparing readers.

    python3 tools/make_ocr_corpus.py OUT_DIR

Each case writes NAME.png, NAME.argb.gz (see make_test_screens.py) and NAME.json:
{"blocks": [["line", "line"], ...], "ignore": ["furigana", ...]}. A block is
one piece of text as a reader of the game would take it: a wrapped sentence is
one block of several lines.
"""
import gzip
import json
import random
import struct
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)


def font_file(name):
    return subprocess.run(["fc-match", "-f", "%{file}", name], capture_output=True, text=True, check=True).stdout


SANS = font_file("Noto Sans CJK JP:lang=ja")
SERIF = font_file("Noto Serif CJK JP:lang=ja")


def font(size, serif=False):
    return ImageFont.truetype(SERIF if serif else SANS, size)


def scene(size=(1920, 1080), seed=1):
    """A busy backdrop: blurred blobs of colour, like a background CG."""
    rng = random.Random(seed)
    image = Image.new("RGB", size, (40, 60, 90))
    draw = ImageDraw.Draw(image)
    for _ in range(60):
        x, y = rng.randrange(size[0]), rng.randrange(size[1])
        r = rng.randrange(40, 260)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=tuple(rng.randrange(30, 235) for _ in range(3)))
    return image.filter(ImageFilter.GaussianBlur(18))


def box(image, rect, fill, alpha=255):
    overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
    ImageDraw.Draw(overlay).rounded_rectangle(rect, radius=18, fill=fill + (alpha,))
    return Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")


def save(name, image, blocks, ignore=()):
    image.save(OUT / f"{name}.png")
    rgba = image.convert("RGBA")
    r, g, b, a = rgba.split()
    with gzip.GzipFile(OUT / f"{name}.argb.gz", "wb", mtime=0) as out:
        out.write(struct.pack(">ii", *image.size))
        out.write(Image.merge("RGBA", (a, r, g, b)).tobytes())
    (OUT / f"{name}.json").write_text(json.dumps({"blocks": blocks, "ignore": list(ignore)}, ensure_ascii=False, indent=1))


def lines(draw, xy, rows, f, fill, spacing, **kw):
    x, y = xy
    for i, row in enumerate(rows):
        draw.text((x, y + i * spacing), row, font=f, fill=fill, **kw)


WHITE = (243, 245, 247)

# 1. A sentence wrapped over two lines, with a speaker's name.
img = Image.new("RGB", (1920, 1080), (27, 42, 54))
img = box(img, (80, 740, 1840, 1030), (12, 20, 27))
d = ImageDraw.Draw(img)
d.text((140, 760), "ユイ", font=font(34), fill=(143, 166, 181))
wrap = ["昨日の夜、森の奥で不思議な光を見たんだけど、近づい", "たら消えてしまって、結局何だったのか分からない。"]
lines(d, (140, 820), wrap, font(54), WHITE, 86)
save("wrap", img, [["ユイ"], wrap])

# 2. Furigana over some kanji.
img = Image.new("RGB", (1920, 1080), (27, 42, 54))
img = box(img, (80, 740, 1840, 1030), (12, 20, 27))
d = ImageDraw.Draw(img)
f, small = font(54), font(22)
text = "彼は憂鬱な表情で曖昧に頷いた。"
d.text((140, 860), text, font=f, fill=WHITE)
for word, ruby in (("憂鬱", "ゆううつ"), ("曖昧", "あいまい"), ("頷", "うなず")):
    start = d.textlength(text[: text.index(word)], font=f)
    width = d.textlength(word, font=f)
    d.text((140 + start + width / 2 - d.textlength(ruby, font=small) / 2, 832), ruby, font=small, fill=WHITE)
save("furigana", img, [[text]], ignore=["ゆううつ", "あいまい", "うなず"])

# 3. A busy background behind a half-transparent box, outlined text.
img = box(scene(seed=3), (80, 740, 1840, 1030), (0, 0, 0), alpha=120)
d = ImageDraw.Draw(img)
busy = ["「そんなこと、今さら言われても困るよ。」", "彼女は窓の外を眺めたまま、小さく笑った。"]
lines(d, (140, 790), busy, font(54, serif=True), WHITE, 90, stroke_width=3, stroke_fill=(20, 20, 30))
save("busy", img, [busy])

# 4. No box at all: outlined text straight over the picture.
img = scene(seed=4)
d = ImageDraw.Draw(img)
bare = ["明日の朝、駅前の喫茶店で待ち合わせしよう。"]
lines(d, (180, 900), bare, font(58), WHITE, 90, stroke_width=4, stroke_fill=(10, 10, 20))
save("nobox", img, [bare])

# 5. A 720p screen with small text.
img = Image.new("RGB", (1280, 720), (27, 42, 54))
img = box(img, (50, 500, 1230, 690), (12, 20, 27))
d = ImageDraw.Draw(img)
small_rows = ["この町に引っ越してきてから、もう三年が経つ。", "時間が過ぎるのは本当に早いものだ。"]
lines(d, (90, 540), small_rows, font(30), WHITE, 50)
save("small720", img, [small_rows])

# 6. A handheld picture shown with black bars: 960x544 scaled into 1920x1080.
inner = Image.new("RGB", (960, 544), (60, 40, 70))
inner = box(inner, (30, 380, 930, 524), (20, 14, 30))
d = ImageDraw.Draw(inner)
vita = ["約束の時間まで、あと少ししかない。"]
lines(d, (56, 420), vita, font(32), WHITE, 48)
img = Image.new("RGB", (1920, 1080), (0, 0, 0))
scaled = inner.resize((1906, 1080), Image.BILINEAR)
img.paste(scaled, (7, 0))
save("letterbox", img, [vita])

# 7. Choices: three centred buttons.
img = scene(seed=7)
choices = ["一緒に帰る", "図書室に寄る", "何も言わずに立ち去る"]
for i, choice in enumerate(choices):
    img = box(img, (560, 300 + i * 150, 1360, 410 + i * 150), (15, 25, 45), alpha=225)
d = ImageDraw.Draw(img)
f = font(50)
for i, choice in enumerate(choices):
    d.text((960 - d.textlength(choice, font=f) / 2, 322 + i * 150), choice, font=f, fill=WHITE)
save("choices", img, [[c] for c in choices])

# 8. Dark text in a light box.
img = scene(seed=8)
img = box(img, (80, 740, 1840, 1030), (245, 240, 228), alpha=240)
d = ImageDraw.Draw(img)
light = ["信じられないかもしれないけど、全部本当の話だ。", "だから最後まで聞いてほしい。"]
lines(d, (140, 790), light, font(54, serif=True), (40, 30, 30), 90)
save("darktext", img, [light])

# 9. Novel style: a paragraph down the whole screen.
img = box(scene(seed=9), (0, 0, 1920, 1080), (0, 0, 0), alpha=170)
d = ImageDraw.Draw(img)
novel_a = ["　雨は夜になっても止まなかった。街灯の光が濡れた", "路面に滲んで、遠くの信号が赤から青に変わる。"]
novel_b = ["　傘を持たずに出てきたことを、少しだけ後悔した。", "それでも引き返す気にはなれなかった。"]
lines(d, (160, 150), novel_a, font(52, serif=True), WHITE, 84)
lines(d, (160, 150 + 84 * 2 + 60), novel_b, font(52, serif=True), WHITE, 84)
save("novel", img, [[r.strip("　") for r in novel_a], [r.strip("　") for r in novel_b]])

# 10. Vertical text in two columns, read right to left.
img = Image.new("RGB", (1920, 1080), (27, 42, 54))
d = ImageDraw.Draw(img)
columns = ["静かな夜だった。", "月だけが見ていた。"]
f = font(56)
for c, column in enumerate(columns):
    for i, ch in enumerate(column):
        d.text((1700 - c * 110, 120 + i * 64), ch, font=f, fill=WHITE)
save("vertical2", img, [columns])

# 11. Status text: Latin, digits and kana together.
img = Image.new("RGB", (1920, 1080), (20, 28, 40))
d = ImageDraw.Draw(img)
status = ["レベル 27", "HP 1280/3400", "所持金 52,300 G", "次のレベルまであと 415"]
lines(d, (200, 200), status, font(52), WHITE, 110)
save("status", img, [[s] for s in status])

# 12. A name plate beside, not above, the text, and a long line that fills the box.
img = Image.new("RGB", (1920, 1080), (27, 42, 54))
img = box(img, (80, 780, 1840, 1030), (12, 20, 27))
img = box(img, (80, 700, 420, 770), (60, 80, 110))
d = ImageDraw.Draw(img)
d.text((120, 708), "田中先生", font=font(40), fill=WHITE)
plate = ["君たちには期待しているから、決して途中で諦めないで", "ほしい。それが私からのたった一つのお願いだ。"]
lines(d, (140, 815), plate, font(54), WHITE, 86)
save("nameplate", img, [["田中先生"], plate])

print("wrote", len(list(OUT.glob("*.json"))), "cases to", OUT)
