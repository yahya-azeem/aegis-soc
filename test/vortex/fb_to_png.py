#!/usr/bin/env python3
"""Convert a raw 0x00RRGGBB framebuffer (.bin) to a viewable PNG.

Usage:
  python3 test/vortex/fb_to_png.py IN.bin OUT.png [scale] [W] [H]

The framebuffer word layout matches golden.py / rt_balls.rs: little-endian
32-bit words of the form 0x00RRGGBB.
"""

import os
import sys

from PIL import Image


def load(path, w, h):
    raw = open(path, "rb").read()
    assert len(raw) >= w * h * 4, f"{path}: {len(raw)} bytes < {w*h*4}"
    px = []
    for i in range(w * h):
        word = int.from_bytes(raw[i * 4:i * 4 + 4], "little")
        px.append(((word >> 16) & 0xFF, (word >> 8) & 0xFF, word & 0xFF))
    img = Image.new("RGB", (w, h))
    img.putdata(px)
    return img


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, dst = sys.argv[1], sys.argv[2]
    scale = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    w = int(sys.argv[4]) if len(sys.argv) > 4 else 40
    h = int(sys.argv[5]) if len(sys.argv) > 5 else w
    img = load(src, w, h)
    if scale > 1:
        img = img.resize((w * scale, h * scale), Image.NEAREST)
    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    img.save(dst)
    print(f"{src} ({w}x{h}) -> {dst} ({img.width}x{img.height})")


if __name__ == "__main__":
    main()
