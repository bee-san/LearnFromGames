#!/usr/bin/env python3
"""Writes the weights of Silero VAD's 16 kHz branch for the app's own port.

The app runs Silero VAD v6.2.3 in plain Kotlin (capture/SileroVad.kt) rather
than through ONNX Runtime, so it needs only the numbers. They are read out of
the 16 kHz side of the model's top If node and written as one file of
little-endian float32, in this order, each array row-major:

    stft basis          [258, 256]       rows 0-128 real, 129-257 imaginary
    encoder.0 weight    [128, 129, 3]    then bias [128]
    encoder.1 weight    [64, 128, 3]     then bias [64]
    encoder.2 weight    [64, 64, 3]      then bias [64]
    encoder.3 weight    [128, 64, 3]     then bias [128]
    lstm weight_ih      [512, 128]       gates in PyTorch's order: i, f, g, o
    lstm weight_hh      [512, 128]
    lstm bias_ih        [512]
    lstm bias_hh        [512]
    decoder weight      [128]            then bias [1]

There is no header; the reader knows the sizes and checks the length.

    python3 -m venv venv && venv/bin/pip install onnx numpy
    venv/bin/python tools/export_silero.py
"""
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "app/src/test/resources/vad/silero_vad.onnx"
OUT = ROOT / "app/src/main/assets/vad/silero_vad_16k.bin"

# Name in the graph, and the shape the Kotlin side expects.
LAYOUT = [
    ("stft.forward_basis_buffer", (258, 1, 256)),
    ("encoder.0.reparam_conv.weight", (128, 129, 3)),
    ("encoder.0.reparam_conv.bias", (128,)),
    ("encoder.1.reparam_conv.weight", (64, 128, 3)),
    ("encoder.1.reparam_conv.bias", (64,)),
    ("encoder.2.reparam_conv.weight", (64, 64, 3)),
    ("encoder.2.reparam_conv.bias", (64,)),
    ("encoder.3.reparam_conv.weight", (128, 64, 3)),
    ("encoder.3.reparam_conv.bias", (128,)),
    ("decoder.rnn.weight_ih", (512, 128)),
    ("decoder.rnn.weight_hh", (512, 128)),
    ("decoder.rnn.bias_ih", (512,)),
    ("decoder.rnn.bias_hh", (512,)),
    ("decoder.decoder.2.weight", (1, 128, 1)),
    ("decoder.decoder.2.bias", (1,)),
]


def sixteen_k_branch(model):
    """The branch the top If takes when sr == 16000."""
    top = [n for n in model.graph.node if n.op_type == "If"]
    assert len(top) == 1, "expected one If at the top of the graph"
    (then,) = [a.g for a in top[0].attribute if a.name == "then_branch"]
    return then


def constants(graph):
    """The branch's constants by their PyTorch names."""
    prefix = "If_0_then_branch__Inline_0__"
    found = {}
    for node in graph.node:
        if node.op_type != "Constant":
            continue
        name = node.output[0].removeprefix(prefix)
        value = next(a for a in node.attribute if a.name == "value")
        found[name] = numpy_helper.to_array(value.t)
    return found


def main():
    found = constants(sixteen_k_branch(onnx.load(MODEL)))
    # The 16 kHz branch is the one with the 256-sample window and 128-sample hop.
    assert int(found["self.stft.hop_length"]) == 128, "not the 16 kHz branch"
    parts = []
    for name, shape in LAYOUT:
        array = found[name]
        assert array.shape == shape, f"{name}: {array.shape} != {shape}"
        assert array.dtype == np.float32, f"{name}: {array.dtype}"
        parts.append(array.ravel())
    data = np.concatenate(parts).astype("<f4")
    OUT.write_bytes(data.tobytes())
    print(f"{OUT.relative_to(ROOT)}: {data.size} floats, {OUT.stat().st_size} bytes")


if __name__ == "__main__":
    sys.exit(main())
