#!/usr/bin/env bash
# Benchmarks the app on whatever adb sees (an emulator, or a handheld), with the stand-in game.
#
#   tools/bench.sh [SECONDS_STILL] [LINES]
#
# Three phases, each ending with the app's own step timings (logcat tag LfgPerf) and its CPU use:
#   still    the same line sits on screen: what background reading costs while nothing changes
#   reading  the game is advanced LINES times: what a new line costs
#   lookup   a word is tapped on each of LINES lines: what a lookup costs
# CPU is the app's share of one core, from /proc/<pid>/stat, so 100 means one core fully used.
set -euo pipefail
STILL="${1:-40}"
LINES="${2:-8}"
APP=io.github.beesan.lfg
GAME=io.github.beesan.lfg.fakegame

pid() { adb shell pidof "$APP" | tr -d '\r'; }
ticks() { adb shell "cat /proc/$(pid)/stat" | awk '{print $14 + $15}'; }
phase() { # name seconds command...
  local name="$1" seconds="$2"; shift 2
  adb logcat -c
  local t0 c0 t1 c1
  c0=$(ticks); t0=$(date +%s.%N)
  "$@"
  local left; left=$(awk -v s="$seconds" -v a="$t0" -v b="$(date +%s.%N)" 'BEGIN{d=s-(b-a); print (d>0)?d:0}')
  sleep "$left"
  c1=$(ticks); t1=$(date +%s.%N)
  # The loop reports every 60 looks (21 s); ask for what it has by waiting for the next report.
  sleep 22
  echo "== $name: $(awk -v c="$((c1 - c0))" -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f%% of a core over %.0f s", c/(b-a), b-a}')"
  adb logcat -d -s LfgPerf | sed -n 's/.*LfgPerf *: //p' | tr '|' '\n' | sed 's/^ */   /'
}
advance() { for _ in $(seq "$LINES"); do adb shell input tap 1500 300; sleep 4; done; }
lookups() { for _ in $(seq "$LINES"); do adb shell input tap 1500 300; sleep 3; adb shell input tap 400 855; sleep 2; adb shell input tap 1500 300; sleep 1; done; }

adb shell am force-stop "$GAME"
adb shell am start -n "$GAME/.GameActivity" >/dev/null
sleep 12
phase still "$STILL" true
phase reading $((LINES * 4)) advance
phase lookup $((LINES * 6)) lookups
