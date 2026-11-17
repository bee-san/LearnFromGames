#!/usr/bin/env python3
"""Plays steps on a virtual gamepad on the connected emulator or device.

Keys injected with `adb shell input` never reach an accessibility service, so
the lookup shortcut cannot be tested that way; a uinput device behaves as a
real pad does, including a D-pad that arrives as the HAT axes.

    tools/virtual_pad.py chord:L3+R3 wait:1500 hat:y:1 hat:y:0 hat:x:1 wait:900 hat:x:0 tap:A tap:B

Steps: chord:A+B  tap:A  down:A  up:A  hat:x:-1|0|1  hat:y:..  abs:lx|ly|rz:VALUE  wait:MS
Names: A B X Y L1 R1 L3 R3 SELECT START."""
import json, subprocess, sys
KEYS = dict(A=304, B=305, X=307, Y=308, L1=310, R1=311, SELECT=314, START=315, L3=317, R3=318)
ABS = dict(x=16, y=17, lx=0, ly=1, rz=5)
def ax(code, lo, hi): return {"code": code, "info": {"value": 0, "minimum": lo, "maximum": hi, "fuzz": 0, "flat": 0, "resolution": 0}}
cmds = [{"id": 1, "command": "register", "name": "LFG Test Pad", "vid": 0x1234, "pid": 0x5678, "bus": "usb",
         "configuration": [{"type": 100, "data": [1, 3]}, {"type": 101, "data": sorted(KEYS.values())}, {"type": 103, "data": [0, 1, 5, 16, 17]}],
         "abs_info": [ax(0, -100, 100), ax(1, -100, 100), ax(5, -100, 100), ax(16, -1, 1), ax(17, -1, 1)]},
        {"id": 1, "command": "delay", "duration": 1200}]
def inject(*ev): cmds.append({"id": 1, "command": "inject", "events": list(ev) + [0, 0, 0]})
def delay(ms): cmds.append({"id": 1, "command": "delay", "duration": ms})
for step in sys.argv[1:]:
    kind, _, rest = step.partition(":")
    if kind == "wait": delay(int(rest))
    elif kind == "chord":
        a, b = (KEYS[k] for k in rest.split("+"))
        inject(1, a, 1, 0, 0, 0, 1, b, 1); delay(120); inject(1, a, 0, 0, 0, 0, 1, b, 0); delay(300)
    elif kind == "tap": inject(1, KEYS[rest], 1); delay(80); inject(1, KEYS[rest], 0); delay(300)
    elif kind == "down": inject(1, KEYS[rest], 1); delay(50)
    elif kind == "up": inject(1, KEYS[rest], 0); delay(50)
    elif kind in ("hat", "abs"):
        axis, value = rest.split(":"); inject(3, ABS[axis], int(value)); delay(60)
delay(300)
script = "\n".join(json.dumps(c) for c in cmds)
subprocess.run(["adb", "shell", "cat > /data/local/tmp/pad.json; uinput /data/local/tmp/pad.json"], input=script.encode(), check=False)
