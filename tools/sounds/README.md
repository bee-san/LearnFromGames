# The wheel's and lookup's sounds

`app/src/main/res/raw/sound_*.ogg` are made here, not recorded. What each is:

| file | heard when | made of |
|---|---|---|
| `sound_move` | the wheel points at another slice; lookup steps to another word | the Hi75 key coming up, played a third faster, with a 9 ms ping at A5 and a small room |
| `sound_pick` | a tile is picked; a card is asked for | the Hi75 key going down, a low thump under it, a faint marimba E5 |
| `sound_into`, `sound_back` | into a wheel inside the wheel, and back out | the key coming up, then two marimba notes: E5 to A5, and A5 to E5 |
| `sound_open`, `sound_away` | the wheel opens, and goes with nothing picked | a bamboo swish, and the same a fourth lower |
| `sound_no` | a tile with nothing to act on; a card that failed | the same low marimba note twice |

The Hi75 recordings are the ones the app's buttons play (`click_down_1`, `click_up_1`; Josh Comeau's
button-sounds, MIT). The marimba notes and the swish are rendered in code by
[Earshot](https://github.com/Leonxlnx/earshot) 0.3 (MIT; no recording is shipped with it, and what it
renders is free to use).

`alt/` holds the ones not chosen, to swap in without making anything again: copy one over the
`sound_*.ogg` it replaces. They can be tried on a wheel at <https://claude.ai/artifact/NpHAYETVRwVh3kc9aQEkMS>
(the owner's page).

## Making them again

```sh
uv venv --python 3.12 venv && uv pip install --python venv/bin/python "earshot @ git+https://github.com/Leonxlnx/earshot"
mkdir -p ref r2 own
for n in down up; do ffmpeg -i ../../app/src/main/res/raw/click_${n}_1.ogg ref/hi75_${n}_1.wav; done
E=venv/bin/earshot
$E make --sound menu/move --theme soft --pitch A5 --seed 7 --mono --ring -0.6 -o r2/fig_move.wav
$E make --sound menu/move --theme soft --pitch E6 --seed 7 --mono --ring -0.6 -o r2/fig_move_e6.wav   # comes out at E5
$E make --sound menu/move --theme soft --pitch A4 --seed 7 --mono --ring -0.6 -o r2/fig_move_a4.wav
$E make --sound success/fifth --theme soft --pitch A5 --seed 7 --mono --ring -0.2 -o r2/fig_card_fifth.wav
$E make --sound success/done  --theme soft --pitch A5 --seed 7 --mono --ring -0.2 -o r2/fig_card_done.wav
$E make --sound success/rise  --theme soft --pitch A5 --seed 7 --mono --ring -0.3 -o r2/fig_card_rise.wav
$E make --sound error/double  --theme soft --pitch A3 --seed 7 --mono --ring -0.6 -o r2/fig_no_double.wav
$E make --sound error/fall    --theme soft --pitch A3 --seed 7 --mono --ring -0.5 -o r2/fig_no_fall.wav
$E sfx render fit/whoosh.short/bamboo_swish --sr 48000 --dur 0.30 -o r2/bank_swish_open.wav
$E sfx render fit/whoosh.short/bamboo_swish --sr 48000 --dur 0.30 --pitch F3 -o r2/bank_swish_away.wav
$E sfx render fit/hit/bamboo_tok --sr 48000 -o r2/bank_fit_hit_bamboo_tok.wav
venv/bin/python synth.py     # own/*.wav: the hand-made ticks and pops final.py borrows from
venv/bin/python final.py     # final/sound_*.wav, and alt/*.wav: the ones not chosen
for f in final/*.wav; do ffmpeg -i $f -c:a libvorbis -q:a 6 -ac 1 ../../app/src/main/res/raw/$(basename ${f%.wav}).ogg; done
```

## How they were chosen (2026-11-26)

The owner likes the thock of the Hi75 keys and the tick a PSP's menu makes going from game to game.
Sixty candidates (Earshot's interface sounds, its sound bank, hand-made ticks and pops, and layers of
the Hi75 keys with notes) were scored with Earshot's listening model for how close each sits to the
Hi75 recordings and how "cheap" or "harsh" it reads. Pure synthesised pops read as cheap beeps; the
key with a short pitched ping under it stayed closest to the keys while keeping a note, as a console
menu's tick has. The tick is one sound, the same every time: it is heard many times a minute.
