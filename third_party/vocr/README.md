# VOCR runtime

The recogniser and full-frame text spotter from
[bee-san/vita-vn-ocr](https://github.com/bee-san/vita-vn-ocr), copied unchanged
from `runtime/` at commit `4674e54b79ac87d9dbcc3e7ba2bfc670c9e3b52c`. Plain
C99, no allocation. GPL-3.0-or-later (`LICENSE`).

LearnFromGames runs it on every picture of the game as the cheap first pass
that says what text is on screen (`app/src/main/cpp/lfg_vocr.c`).

The weights are in `app/src/main/assets/vocr/` and are for non-commercial use
only; see the attribution files beside them.
