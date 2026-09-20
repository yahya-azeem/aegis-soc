#!/usr/bin/env python3
"""Render a conceptual die floorplan for the Aegis SoC.

This is an *illustrative* layout derived from the RTL block structure in
docs/aegis_block_diagram.dot -- it is not a physical synthesis/place-and-route
result. It exists to communicate the chip's organisation at a glance.

Usage:
  python3 scripts/render_floorplan.py [OUT.png]
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/aegis_floorplan.png"

W, H = 1600, 1000
BG = (15, 23, 42)
DIE = (30, 41, 59)
IO = (51, 65, 85)
TXT = (226, 232, 240)
SUB = (148, 163, 184)

CPU = (99, 102, 241)
GPU = (34, 197, 94)
ACC = (245, 158, 11)
MEM = (56, 189, 248)
NOC = (168, 85, 247)


def font(size, bold=False):
    for p in (
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/TTF/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/dejavu/DejaVuSans.ttf",
    ):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def block(d, box, fill, title, sub, f_title, f_sub, radius=14):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=(255, 255, 255, 40), width=2)
    x0, y0, x1, y1 = box
    lines = sub.split("\n")
    t_h, line_h = 28, 18
    total = t_h + line_h * len(lines)
    y = (y0 + y1) / 2 - total / 2
    tw = d.textlength(title, font=f_title)
    d.text(((x0 + x1 - tw) / 2, y), title, font=f_title, fill=(15, 23, 42))
    y += t_h
    for ln in lines:
        lw = d.textlength(ln, font=f_sub)
        d.text(((x0 + x1 - lw) / 2, y), ln, font=f_sub, fill=(15, 23, 42))
        y += line_h


def main():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img, "RGBA")

    f_h1 = font(34, bold=True)
    f_h2 = font(20, bold=True)
    f_lbl = font(18, bold=True)
    f_sub = font(13)
    f_leg = font(14)

    d.text((40, 30), "Aegis SoC", font=f_h1, fill=TXT)
    d.text((40, 74), "conceptual die floorplan  ·  single-die RISC-V + GPGPU with unified shared HBM3",
           font=f_sub, fill=SUB)

    # die + I/O ring
    die = (40, 120, W - 40, H - 40)
    d.rounded_rectangle(die, radius=26, fill=DIE, outline=IO, width=6)
    ring = (die[0] + 16, die[1] + 16, die[2] - 16, die[3] - 16)
    d.rounded_rectangle(ring, radius=18, outline=IO, width=2)
    d.text((die[0] + 30, die[1] + 26), "I/O ring", font=f_sub, fill=SUB)

    # central NoC spine
    noc = (die[0] + 250, 430, die[2] - 250, 500)
    cpu_r = (die[0] + 40, 190, die[0] + 430, 400)
    gpu_r = (die[2] - 620, 190, die[2] - 40, 400)
    acc_r = (die[0] + 40, 530, die[0] + 430, 740)
    mem_r = (die[2] - 620, 530, die[2] - 40, 740)

    # fabric connectors (drawn before the blocks so they pass underneath)
    nl = (noc[0], (noc[1] + noc[3]) // 2)
    nr = (noc[2], (noc[1] + noc[3]) // 2)
    for src, dst in [
        (nl, (cpu_r[0] + 40, cpu_r[3])),
        (nl, (acc_r[0] + 40, acc_r[1])),
        (nr, (gpu_r[2] - 40, gpu_r[3])),
        (nr, (mem_r[0] + 60, mem_r[1])),
    ]:
        d.line([src, dst], fill=SUB, width=3)

    block(d, noc, NOC, "SoC fabric / NoC", "SplitPrioritizer  ·  AXI4 / MemReq  ·  QoS arbitration",
          f_lbl, f_sub)

    # CPU cluster
    block(d, cpu_r, CPU, "CPU complex",
          "RiscVICore (RV32I in-order)\nCoreMemToHBM  ·  32b→512b RMW", f_h2, f_sub)

    # GPU cluster
    block(d, gpu_r, GPU, "GPU complex",
          "GPUL2Cache (2-port RR + AXI4 FSM)\nSimtCore (32 lanes)  ·  AXIToMemReq", f_h2, f_sub)

    # Accelerator
    block(d, acc_r, ACC, "Accelerator",
          "GemmToMem (8×8 systolic)\nVortexAccelerator BlackBox (optional)", f_h2, f_sub)

    # Memory stack
    block(d, mem_r, MEM, "HBM3 stack",
          "4 banks × 8 rows × 8 cols × 512-bit\nopen-page controller + refresh walker", f_h2, f_sub)

    # legend
    lx, ly = 40, H - 34
    for i, (c, t) in enumerate([(CPU, "CPU"), (GPU, "GPU"), (ACC, "accelerator"), (MEM, "memory"), (NOC, "fabric")]):
        x = lx + i * 150
        d.rounded_rectangle((x, ly, x + 18, ly + 14), radius=4, fill=c)
        d.text((x + 26, ly - 2), t, font=f_leg, fill=SUB)

    d.text((W - 640, H - 34),
           "illustrative layout derived from RTL blocks — not a placed-and-routed die",
           font=f_sub, fill=SUB)

    os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
    img.save(OUT)
    print(f"floorplan -> {OUT} ({W}x{H})")


if __name__ == "__main__":
    main()
