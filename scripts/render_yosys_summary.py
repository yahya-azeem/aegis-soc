#!/usr/bin/env python3
"""Render a cell-count summary chart from a Yosys `stat` transcript.

Usage:
  python3 scripts/render_yosys_summary.py docs/aegis_yosys_stat.txt docs/aegis_yosys_cells.png
"""

import re
import sys

from PIL import Image, ImageDraw, ImageFont

SRC = sys.argv[1] if len(sys.argv) > 1 else "docs/aegis_yosys_stat.txt"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/aegis_yosys_cells.png"

BG = (15, 23, 42)
BAR = (99, 102, 241)
BAR2 = (34, 197, 94)
TXT = (226, 232, 240)
SUB = (148, 163, 184)


def font(size, bold=False):
    for p in (
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/TTF/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/dejavu/DejaVuSans.ttf",
    ):
        if __import__("os").path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def parse(path):
    modules, cur, total = {}, None, 0
    for line in open(path):
        m = re.match(r"^=== (.+) ===\s*$", line)
        if m:
            name = m.group(1)
            if name == "design hierarchy":
                cur = None
                continue
            cur = name
            modules.setdefault(cur, {"cells": 0, "submodules": 0, "types": {}})
            continue
        if cur is None:
            continue
        m = re.match(r"^\s+(\d+) cells\s*$", line)
        if m:
            modules[cur]["cells"] = int(m.group(1))
            if cur is None:
                pass
            continue
        m = re.match(r"^\s+(\d+) submodules\s*$", line)
        if m:
            modules[cur]["submodules"] = int(m.group(1))
            continue
        m = re.match(r"^\s+(\d+)\s+\$(\w+)\s*$", line)
        if m and "cells" in modules[cur]:
            modules[cur]["types"][m.group(2)] = int(m.group(1))
    total = sum(v["cells"] for v in modules.values())
    return modules, total


def main():
    mods, total_in = parse(SRC)
    rows = [(k, v) for k, v in mods.items() if v["cells"] > 0]
    rows.sort(key=lambda kv: kv[1]["cells"])
    total = sum(v["cells"] for _, v in rows)

    W = 1200
    row_h = 46
    H = 150 + row_h * len(rows)
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    f_h1 = font(30, True)
    f_h2 = font(17, True)
    f_lbl = font(16, True)
    f_num = font(15)
    f_sub = font(13)

    d.text((40, 28), "Aegis SoC — Yosys synthesis cell counts", font=f_h1, fill=TXT)
    d.text((40, 68), f"{total:,} cells across {len(rows)} modules (Yosys 0.66, proc/memory level)",
           font=f_sub, fill=SUB)

    x0, x1 = 320, W - 160
    maxc = max(v["cells"] for _, v in rows) or 1
    y = 120
    for i, (name, v) in enumerate(reversed(rows)):
        c = v["cells"]
        # log-ish scale so small FSMs stay visible next to GemmToMem
        frac = (c ** 0.5) / (maxc ** 0.5)
        bw = max(4, int(frac * (x1 - x0)))
        col = BAR if c < maxc else BAR2
        d.text((40, y + 6), name, font=f_lbl, fill=TXT)
        d.text((40, y + 24), f"{v['submodules']} submodule(s)", font=f_sub, fill=SUB)
        d.rounded_rectangle((x0, y + 8, x0 + bw, y + 32), radius=6, fill=col)
        d.text((x0 + bw + 10, y + 9), f"{c:,}", font=f_num, fill=TXT)
        y += row_h

    d.text((40, H - 34), "bar length ∝ sqrt(cell count)", font=f_sub, fill=SUB)
    img.save(OUT)
    print(f"{OUT} ({W}x{H}), {len(rows)} modules, {total} cells")


if __name__ == "__main__":
    main()
