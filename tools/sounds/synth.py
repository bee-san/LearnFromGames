"""Hand-made candidates: soft pitched ticks and pops in the manner of a handheld console's menu."""
import numpy as np, soundfile as sf
from scipy.signal import butter, sosfilt, fftconvolve
SR = 48000
rng = np.random.default_rng(3)

def env(n, attack, tau):
    t = np.arange(n) / SR
    a = np.minimum(1.0, t / max(attack, 1e-5))
    return a * np.exp(-t / tau)

def tone(f0, dur, tau, attack=0.0015, glide=1.0, glide_t=0.006, harm=()):
    n = int(dur * SR); t = np.arange(n) / SR
    f = f0 * (1 + (glide - 1) * np.exp(-t / glide_t))
    ph = 2 * np.pi * np.cumsum(f) / SR
    x = np.sin(ph)
    for k, a, tk in harm:
        x += a * np.sin(k * ph) * np.exp(-t / tk)
    return x * env(n, attack, tau)

def click(dur, lo, hi, tau):
    n = int(dur * SR)
    x = rng.standard_normal(n) * env(n, 0.0003, tau)
    return sosfilt(butter(2, [lo, hi], btype="band", fs=SR, output="sos"), x)

def thump(f0, dur, tau, drop=1.8):
    return tone(f0, dur, tau, attack=0.001, glide=drop, glide_t=0.008)

def room(x, wet=0.12, tail=0.09, bright=5000):
    """A small room under the dry sound: sparse early reflections and a short noise tail."""
    n = int(tail * SR)
    ir = rng.standard_normal(n) * np.exp(-np.arange(n) / (tail * SR / 5))
    ir = sosfilt(butter(2, [300, bright], btype="band", fs=SR, output="sos"), ir)
    ir /= np.sqrt((ir ** 2).sum())
    pre = int(0.006 * SR)
    w = fftconvolve(x, ir)[: len(x) + n]
    out = np.zeros(len(x) + n + pre); out[: len(x)] += x; out[pre: pre + len(w)] += wet * w
    return out

def mix(*parts):
    n = max(len(p) for p in parts); out = np.zeros(n)
    for p in parts: out[: len(p)] += p
    return out

def at(x, t):
    return np.concatenate([np.zeros(int(t * SR)), x])

def lp(x, f):
    return sosfilt(butter(2, f, btype="low", fs=SR, output="sos"), x)

def fin(x, peak=0.7):
    n = int(0.004 * SR); x = x.copy(); x[-n:] *= np.linspace(1, 0, n)
    return x / np.abs(x).max() * peak

A5, C6, E6, A6, E5, D5, A4 = 880.0, 1046.5, 1318.5, 1760.0, 659.3, 587.3, 440.0
out = {}
# Moving: a short hollow pitched tick. Three weights of it.
out["move_tick_a"] = room(mix(tone(A5 * 1.5, 0.07, 0.011, glide=1.25, harm=[(2.0, 0.25, 0.004)]), 0.35 * click(0.01, 1500, 6000, 0.0015)), wet=0.10)
out["move_tick_b"] = room(mix(tone(A5, 0.08, 0.014, glide=1.3, harm=[(2.76, 0.18, 0.004)]), 0.25 * click(0.01, 1200, 5000, 0.0015), 0.5 * thump(160, 0.05, 0.010)), wet=0.10)
out["move_tick_c"] = room(mix(tone(C6, 0.06, 0.008, glide=1.15), 0.5 * click(0.008, 2000, 7000, 0.001)), wet=0.14, tail=0.12)
# A water-drop pop, pitch rising: the Vita's bubbles.
out["move_bubble"] = room(tone(620, 0.09, 0.022, attack=0.003, glide=0.62, glide_t=0.018), wet=0.08)
out["move_bubble_hi"] = room(tone(900, 0.07, 0.016, attack=0.002, glide=0.7, glide_t=0.012), wet=0.08)
# Picking: a low thock with a note on top of it, the note a fifth above the tick's.
thock = mix(thump(95, 0.12, 0.022, drop=2.4), 0.6 * lp(click(0.03, 200, 2400, 0.004), 3000), 0.25 * click(0.006, 2000, 6000, 0.001))
out["pick_thock"] = room(thock, wet=0.08)
out["pick_thock_note"] = room(mix(thock, 0.45 * tone(A5, 0.22, 0.05, harm=[(2.0, 0.2, 0.02)]), at(0.40 * tone(E6, 0.22, 0.06, harm=[(2.0, 0.15, 0.02)]), 0.045)), wet=0.14, tail=0.16)
out["pick_thock_soft_note"] = room(mix(thock, 0.30 * tone(E6, 0.20, 0.045)), wet=0.12, tail=0.14)
out["pick_rise"] = room(mix(0.8 * thock, 0.5 * tone(A5, 0.25, 0.05, attack=0.004, glide=0.8, glide_t=0.03)), wet=0.14, tail=0.16)
# Into a wheel inside the wheel, and back out: two quick notes up a fifth, and down.
out["into"] = room(mix(0.5 * thock, 0.5 * tone(A5, 0.12, 0.03), at(0.5 * tone(E6, 0.2, 0.05), 0.06)), wet=0.14, tail=0.16)
out["back"] = room(mix(0.5 * tone(E6 / 2 * 1.0, 0.12, 0.03), at(0.55 * tone(A4, 0.2, 0.05), 0.06), at(0.4 * thump(110, 0.08, 0.015), 0.06)), wet=0.12, tail=0.14)
# The wheel opening: a soft breath in with a low pop at its end; and going away.
def breath(dur, f1, f2, rise=True):
    n = int(dur * SR); t = np.arange(n) / n
    x = rng.standard_normal(n)
    e = (t ** 2 if rise else (1 - t) ** 2) * np.sin(np.pi * t) ** 0.5
    # sweep a band-pass by cross-fading two filtered copies
    a = sosfilt(butter(2, [f1 * 0.7, f1 * 1.4], btype="band", fs=SR, output="sos"), x)
    b = sosfilt(butter(2, [f2 * 0.7, f2 * 1.4], btype="band", fs=SR, output="sos"), x)
    return (a * (1 - t) + b * t) * e
out["open"] = room(mix(0.5 * breath(0.14, 500, 1800), at(tone(440, 0.12, 0.03, attack=0.004, glide=0.7, glide_t=0.02), 0.09), at(0.5 * thump(120, 0.08, 0.016), 0.09)), wet=0.12, tail=0.14)
out["away"] = room(mix(0.4 * breath(0.14, 1500, 400, rise=False), tone(520, 0.12, 0.03, attack=0.003, glide=1.5, glide_t=0.03)), wet=0.10, tail=0.12)
# A card made: two soft bell-like notes, a fifth then the octave.
def bell(f, dur, tau): return tone(f, dur, tau, attack=0.002, harm=[(2.0, 0.3, tau * 0.5), (3.0, 0.12, tau * 0.3), (4.2, 0.06, tau * 0.2)])
out["card"] = room(mix(0.4 * thock, 0.5 * bell(A5, 0.5, 0.12), at(0.5 * bell(E6, 0.6, 0.16), 0.09), at(0.3 * bell(A6, 0.6, 0.18), 0.18)), wet=0.18, tail=0.25)
# No: two low dull knocks.
knock = mix(thump(140, 0.1, 0.02, drop=1.5), 0.3 * lp(click(0.02, 150, 1200, 0.004), 1500))
out["no"] = room(mix(knock, at(0.85 * mix(thump(118, 0.1, 0.022, drop=1.5), 0.3 * lp(click(0.02, 150, 1200, 0.004), 1500)), 0.11)), wet=0.08)
for k, v in out.items():
    sf.write(f"own/{k}.wav", fin(v), SR)
print(len(out), "written")
