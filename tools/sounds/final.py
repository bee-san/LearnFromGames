import numpy as np, soundfile as sf, sys, io, contextlib, importlib.util, os
from scipy.signal import resample_poly
spec = importlib.util.spec_from_file_location("synth", "synth.py"); syn = importlib.util.module_from_spec(spec)
with contextlib.redirect_stdout(io.StringIO()): spec.loader.exec_module(syn)
SR = 48000
def rd(p, speed=1.0, thr=0.02):
    x, sr = sf.read(p, always_2d=True); x = x.mean(1)
    if sr != SR: x = resample_poly(x, SR, sr)
    if speed != 1.0: x = resample_poly(x, 1000, int(1000 * speed))
    i = np.argmax(np.abs(x) > np.abs(x).max() * thr); x = x[max(0, i - 24):]
    return x / np.abs(x).max()
def cut(x, ms, fade=12):
    n = int(ms / 1000 * SR); x = x[:n].copy(); f = min(int(fade / 1000 * SR), len(x)); x[-f:] *= np.linspace(1, 0, f) ** 2; return x
mix, at, room, tone, thump = syn.mix, syn.at, syn.room, syn.tone, syn.thump
down, up = rd("ref/hi75_down_1.wav"), rd("ref/hi75_up_1.wav")
A5, E5, A4 = rd("r2/fig_move.wav"), rd("r2/fig_move_e6.wav"), rd("r2/fig_move_a4.wav")   # 880, 659 and 440 Hz, as measured
out = {}
# The chosen set
out["sound_move"] = room(mix(0.7 * rd("ref/hi75_up_1.wav", 1.35), 0.45 * tone(880, 0.07, 0.009)), wet=0.10)
out["sound_pick"] = room(mix(1.0 * down, 0.5 * thump(95, 0.1, 0.02, drop=2.2), 0.16 * cut(E5, 150, 50)), wet=0.07)
out["sound_into"] = mix(0.55 * up, 0.5 * cut(E5, 110, 30), at(0.58 * cut(A5, 200, 70), 0.07))
out["sound_back"] = mix(0.55 * up, 0.5 * cut(A5, 110, 30), at(0.58 * cut(E5, 200, 70), 0.07))
out["sound_open"] = cut(rd("r2/bank_swish_open.wav", thr=0.05), 230, 40)
out["sound_away"] = cut(rd("r2/bank_swish_away.wav", thr=0.05), 230, 40)
out["sound_card"] = cut(rd("r2/fig_card_fifth.wav"), 900, 300)
out["sound_no"] = cut(rd("r2/fig_no_double.wav"), 420, 80)
# Others worth hearing beside them
alt = {}
alt["move_key_only"] = room(0.9 * rd("ref/hi75_up_1.wav", 1.4), wet=0.08)
alt["move_wood_tick"] = rd("own/move_tick_c.wav")
alt["move_bamboo_tok"] = cut(rd("r2/bank_fit_hit_bamboo_tok.wav"), 120, 30)
alt["move_marimba"] = cut(A5, 99, 20)
alt["move_bubble"] = rd("own/move_bubble.wav")
alt["pick_note_e5_louder"] = mix(1.0 * down, 0.36 * cut(E5, 160, 40))
alt["pick_deep_thock"] = room(mix(1.0 * down, 0.5 * thump(95, 0.1, 0.02, drop=2.2)), wet=0.08)
alt["pick_two_notes"] = mix(1.0 * down, 0.30 * cut(A5, 120, 30), at(0.32 * cut(E5, 170, 50), 0.05))
alt["pick_note_a5"] = mix(1.0 * down, 0.40 * cut(A5, 160, 40))
alt["card_octave"] = cut(rd("r2/fig_card_done.wav"), 700, 250)
alt["card_three_rising"] = cut(rd("r2/fig_card_rise.wav"), 1000, 300)
alt["no_falling"] = cut(rd("r2/fig_no_fall.wav"), 390, 80)
os.makedirs("final", exist_ok=True); os.makedirs("alt", exist_ok=True)
def fin(x, peak=0.7):
    n = int(0.004 * SR); x = x.copy(); x[-n:] *= np.linspace(1, 0, n)
    # Half a millisecond up from nothing, so a recording that starts mid-wave does not click.
    a = 24; x[:a] *= np.sin(np.linspace(0, np.pi / 2, a)) ** 2
    return np.concatenate([np.zeros(8), x / np.abs(x).max() * peak, np.zeros(8)])
for k, v in out.items(): sf.write(f"final/{k}.wav", fin(v), SR, subtype="PCM_16")
for k, v in alt.items(): sf.write(f"alt/{k}.wav", fin(v), SR, subtype="PCM_16")
