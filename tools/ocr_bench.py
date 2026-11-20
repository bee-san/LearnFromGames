#!/usr/bin/env python3
"""Scores LFG's readers on real game screens whose text is known.

The screens and their text come from psandvita's paired data: Vita and PSP
pages captured with the game's own text hooked, so every labelled line has
its true text and its box (`<run>/exports/lines-vocr.jsonl`).

    tools/ocr_bench.py prepare PAIRED_DATA OUT [--train-pages 4] [--test-pages 150] [--seed 1]
    adb push OUT/pages/. /sdcard/Android/data/io.github.beesan.lfg/files/ocrbench/
    adb shell am instrument -w io.github.beesan.lfg/.OcrBench      # debug build
    adb pull /sdcard/Android/data/io.github.beesan.lfg/files/ocrbench/results.jsonl OUT/
    tools/ocr_bench.py score OUT [--show 20]

Each page is letterboxed into 1920x1080, as a handheld's screen shows the
game, and the labelled boxes are moved with it. A labelled line counts as read
from the characters (or, for VOCR, the lines) a reader placed on it; the
score is the character error rate after vita-vn-ocr's normalisation.

The split is psandvita's: VOCR6 was fine-tuned on the hooked games, so only
the `test` games are fair to it. ScreenAI and meikiocr never saw any of them.
"""
import argparse
import json
import random
import re
import statistics
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

SCREEN = (1920, 1080)

# vita-vn-ocr eval/metrics.py's norm(), with the two-dot leader folded too.
_TRANS = str.maketrans({**{c: "―" for c in "―—─━–‐‑‒−-"}, **{c: "~" for c in "〜～~∼"}})
_SPACE = re.compile(r"\s+")
_DOTS = re.compile(r"・{2,}")


def norm(s):
    s = unicodedata.normalize("NFKC", s or "")
    s = _SPACE.sub("", s)
    s = s.replace("...", "…").replace("..", "‥")
    s = _DOTS.sub(lambda m: "…" * max(1, len(m.group()) // 3), s)
    return s.translate(_TRANS).replace("‥", "…")


def lev(a, b):
    if a == b:
        return 0
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def prepare(args):
    from PIL import Image

    rng = random.Random(args.seed)
    out = Path(args.out)
    (out / "pages").mkdir(parents=True, exist_ok=True)
    truth = []
    for export in sorted(Path(args.data).glob("*/exports/lines-vocr.jsonl")):
        run = export.parent.parent
        pages = defaultdict(list)
        for row in map(json.loads, export.open(encoding="utf-8")):
            if row.get("kind") == "line" and row.get("split") in ("train", "test"):
                pages[row["image"]].append(row)
        if not pages:
            continue
        split = next(iter(pages.values()))[0]["split"]
        count = args.test_pages if split == "test" else args.train_pages
        for image in sorted(rng.sample(sorted(pages), min(count, len(pages)))):
            source = run / image
            if not source.is_file():
                continue
            picture = Image.open(source).convert("RGB")
            scale = min(SCREEN[0] / picture.width, SCREEN[1] / picture.height)
            size = (round(picture.width * scale), round(picture.height * scale))
            dx, dy = (SCREEN[0] - size[0]) // 2, (SCREEN[1] - size[1]) // 2
            screen = Image.new("RGB", SCREEN)
            screen.paste(picture.resize(size, Image.BILINEAR), (dx, dy))
            name = f"{run.name.split('-')[2]}_{Path(image).stem}.png"
            screen.save(out / "pages" / name)
            for row in pages[image]:
                x, y, w, h = row["box"]
                truth.append({
                    "file": name, "id": row["id"], "game": row["title"], "platform": row["platform"],
                    "split": split, "vertical": row["vertical"], "text": row["text"],
                    "box": [dx + x * scale, dy + y * scale, dx + (x + w) * scale, dy + (y + h) * scale],
                })
    with (out / "truth.jsonl").open("w", encoding="utf-8") as f:
        for row in truth:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"{len({r['file'] for r in truth})} pages, {len(truth)} lines -> {out}")


def inside(point, box, margin):
    x, y = point
    return box[0] - margin <= x <= box[2] + margin and box[1] - margin <= y <= box[3] + margin


def overlap(a, b):
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    return max(0, w) * max(0, h)


def area(b):
    return max(1, (b[2] - b[0]) * (b[3] - b[1]))


def read_on(line_box, vertical, lines):
    """What a reader read on one labelled line, in its reading order."""
    across = (line_box[2] - line_box[0]) if vertical else (line_box[3] - line_box[1])
    margin = across * 0.1
    pieces = []
    for line in lines:
        if line["chars"]:
            text = line["text"]
            for start, end, l, t, r, b in line["chars"]:
                if inside(((l + r) / 2, (t + b) / 2), line_box, margin):
                    pieces.append(((t + b) / 2 if vertical else (l + r) / 2, (l + r) / 2, (t + b) / 2, text[start:end]))
        elif overlap(line["box"], line_box) >= 0.5 * min(area(line["box"]), area(line_box)):
            b = line["box"]
            pieces.append(((b[1] if vertical else b[0]), b[0], b[1], line["text"]))
    # The reader's own order: its lines as it gave them, each line's characters in its text.
    return "".join(p[3] for p in pieces)


def score(args):
    out = Path(args.out)
    truth = [json.loads(l) for l in (out / "truth.jsonl").open(encoding="utf-8")]
    results = defaultdict(dict)
    times = defaultdict(list)
    for row in map(json.loads, (out / "results.jsonl").open(encoding="utf-8")):
        results[row["engine"]][row["file"]] = row["lines"]
        times[row["engine"]].append(row["ms"])
    engines = sorted(results)

    def group_key(row):
        return (row["split"], "vertical" if row["vertical"] else "horizontal")

    table = defaultdict(lambda: {"lines": 0, "chars": 0, "edits": 0, "exact": 0, "missed": 0})
    worst = defaultdict(list)
    per_game = defaultdict(lambda: defaultdict(lambda: [0, 0]))
    for row in truth:
        want = norm(row["text"])
        if not want:
            continue
        for engine in engines:
            lines = results[engine].get(row["file"])
            if lines is None:
                continue
            got = norm(read_on(row["box"], row["vertical"], lines))
            edits = min(lev(want, got), len(want) + len(got))
            for key in (group_key(row), ("all", "all")):
                t = table[(engine,) + key]
                t["lines"] += 1
                t["chars"] += len(want)
                t["edits"] += edits
                t["exact"] += want == got
                t["missed"] += not got
            per_game[engine][row["game"]][0] += edits
            per_game[engine][row["game"]][1] += len(want)
            if want != got:
                worst[engine].append((edits / len(want), row["file"], want, got))

    print("Character error rate (lower is better), exact lines, lines with nothing read\n")
    print(f"{'engine':10}{'split':7}{'text':12}{'lines':>7}{'CER':>8}{'exact':>8}{'missed':>8}")
    for key in sorted(table):
        t = table[key]
        engine, split, kind = key
        print(f"{engine:10}{split:7}{kind:12}{t['lines']:7}{t['edits'] / t['chars']:8.1%}{t['exact'] / t['lines']:8.1%}{t['missed'] / t['lines']:8.1%}")

    print("\nTime per 1920x1080 screen on this device (ms)")
    print(f"{'engine':10}{'median':>8}{'p90':>8}{'max':>8}{'n':>6}")
    for engine in engines:
        ms = sorted(times[engine])
        print(f"{engine:10}{statistics.median(ms):8.0f}{ms[int(len(ms) * 0.9)]:8.0f}{ms[-1]:8.0f}{len(ms):6}")

    for engine in engines:
        games = sorted(per_game[engine].items(), key=lambda g: -g[1][0] / max(1, g[1][1]))
        print(f"\n{engine}: hardest games " + ", ".join(f"{g} {e / max(1, c):.0%}" for g, (e, c) in games[:5]))
        if args.show:
            for cer, file, want, got in sorted(worst[engine], reverse=True)[: args.show]:
                print(f"  {cer:5.0%} {file}\n        want {want}\n        got  {got}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("data")
    p.add_argument("out")
    p.add_argument("--train-pages", type=int, default=4)
    p.add_argument("--test-pages", type=int, default=150)
    p.add_argument("--seed", type=int, default=1)
    s = sub.add_parser("score")
    s.add_argument("out")
    s.add_argument("--show", type=int, default=0)
    args = parser.parse_args()
    prepare(args) if args.command == "prepare" else score(args)


if __name__ == "__main__":
    sys.exit(main())
