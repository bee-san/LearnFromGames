#!/usr/bin/env python3
"""Scores what the readers read against the corpus's true text.

    tools/score_ocr.py CORPUS_DIR [--show]

Reads report.json (written by the OcrCorpusReport unit test) and each case's
NAME.json. For each reader it reports, per case: how much of the true text was
read (character accuracy over the best pairing of read text to true text),
whether each block came out as one piece, and anything read that is not text
of the game (furigana, noise).
"""
import json
import sys
from difflib import SequenceMatcher
from pathlib import Path

corpus = Path(sys.argv[1])
show = "--show" in sys.argv
report = json.loads((corpus / "report.json").read_text())


def similarity(a, b):
    return SequenceMatcher(None, a, b, autojunk=False).ratio() if a or b else 1.0


def squash(text):
    return "".join(text.split())


totals = {}
for name in sorted(report):
    truth = json.loads((corpus / f"{name}.json").read_text())
    blocks = [squash("".join(rows)) for rows in truth["blocks"]]
    ignore = [squash(t) for t in truth["ignore"]]
    for engine, result in report[name].items():
        for level in ("lines", "blocks"):
            read = [squash(item["text"]) for item in result[level]]
            # Pair each true block with the read piece most like it.
            used, scores, whole = set(), [], 0
            for block in blocks:
                best = max(range(len(read)), key=lambda i: similarity(block, read[i]) if i not in used else -1, default=None)
                score = similarity(block, read[best]) if best is not None and best not in used else 0.0
                if best is not None and score > 0.3:
                    used.add(best)
                scores.append(score)
                whole += score >= 0.999
            extra = [read[i] for i in range(len(read)) if i not in used]
            junk = [e for e in extra if e]
            furigana = [e for e in junk if any(similarity(e, g) > 0.6 for g in ignore)]
            key = (engine, level)
            t = totals.setdefault(key, {"cases": 0, "blocks": 0, "whole": 0, "score": 0.0, "extra": 0, "furigana": 0})
            t["cases"] += 1
            t["blocks"] += len(blocks)
            t["whole"] += whole
            t["score"] += sum(scores)
            t["extra"] += len(junk)
            t["furigana"] += len(furigana)
            if show and level == "blocks":
                mark = "ok " if whole == len(blocks) and not junk else "!! "
                print(f"{mark}{engine:9}{name:11} {whole}/{len(blocks)} blocks exact, mean {sum(scores)/len(scores):.3f}, extra {junk}")
                if whole != len(blocks):
                    for item in result[level]:
                        print(f"      read: {item['text']!r}")

print(f"{'reader':10}{'grouping':9}{'blocks exact':>14}{'mean accuracy':>15}{'extra pieces':>14}{'(furigana)':>12}")
for (engine, level), t in sorted(totals.items()):
    print(f"{engine:10}{level:9}{t['whole']:>8}/{t['blocks']:<5}{t['score']/t['blocks']:>15.3f}{t['extra']:>14}{t['furigana']:>12}")
