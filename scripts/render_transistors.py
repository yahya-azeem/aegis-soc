#!/usr/bin/env python3
"""Render a transistor-level CMOS standard-cell library figure.

For each gate we build a one-cell design, expand it to nmos/pmos with
techlib/cmos.v, render it with netlistsvg + scripts/cmos_skin.svg, and compose
the results into docs/transistors_cells.png.

Requires: yosys, `npx netlistsvg`, `rsvg-convert` (or ImageMagick), PIL.
"""

import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO, "scripts"))
import cmos_json  # noqa: E402  (local helper)

CMOS = os.path.join(REPO, "techlib", "cmos.v")
SKIN = os.path.join(REPO, "scripts", "cmos_skin.svg")
OUT = os.path.join(REPO, "docs", "transistors_cells.png")

CELLS = [
    ("CMOS inverter (INV)", "\\$_NOT_", "input a, output y", ".A(a), .Y(y)"),
    ("CMOS NAND2", "\\$_NAND_", "input a, input b, output y", ".A(a), .B(b), .Y(y)"),
    ("CMOS NOR2", "\\$_NOR_", "input a, input b, output y", ".A(a), .B(b), .Y(y)"),
    ("CMOS XOR2 (gate-level)", "\\$_XOR_", "input a, input b, output y", ".A(a), .B(b), .Y(y)"),
    ("CMOS 2:1 mux (transmission gate)", "\\$_MUX_", "input a, input b, input s, output y",
     ".A(a), .B(b), .S(s), .Y(y)"),
    ("CMOS DFF (TG master-slave, 14T)", "\\$_DFF_P_", "input c, input d, output q",
     ".C(c), .D(d), .Q(q)"),
]


def run(cmd, **kw):
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **kw)


def render_cell(name, celltype, ports, conns, tmp):
    v = "cell.v"
    j = "cell.json"
    svg = os.path.join(tmp, "cell.svg")
    png = os.path.join(tmp, "cell.png")
    with open(os.path.join(tmp, v), "w") as f:
        f.write(f"module cell ({ports});\n  {celltype} u ({conns});\nendmodule\n")
    # copy the transistor library in too, so yosys runs entirely space-free
    subprocess.run(["cp", CMOS, os.path.join(tmp, "cmos.v")], check=True)
    run(["yosys", "-p",
         f"read_verilog {v}; read_verilog cmos.v; hierarchy -top cell; flatten; "
         f"delete t:$scopeinfo; opt_clean; write_json {j}"], cwd=tmp)
    # annotate transistor port directions then render
    cmos_json.annotate(os.path.join(tmp, j))
    run(["npx", "--yes", "netlistsvg", j, "-o", svg, "--skin", SKIN], cwd=tmp)
    run(["rsvg-convert", "-o", png, "-w", "900", svg])
    im = Image.open(png).convert("RGBA")
    bg = Image.new("RGB", im.size, (255, 255, 255))
    bg.paste(im, mask=im.split()[3])
    return bg


def font(size, bold=False):
    for p in (
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/TTF/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/dejavu/DejaVuSans.ttf",
    ):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def main():
    with tempfile.TemporaryDirectory() as tmp:
        imgs = []
        for spec in CELLS:
            imgs.append((spec[0], render_cell(spec[0], spec[1], spec[2], spec[3], tmp)))

    cols = 2
    box_w, box_h = 760, 360
    pad, title_h, head_h = 24, 26, 70
    rows = (len(imgs) + cols - 1) // cols
    W = pad + cols * (box_w + pad)
    H = head_h + rows * (box_h + title_h + pad) + pad
    canvas = Image.new("RGB", (W, H), (255, 255, 255))
    d = ImageDraw.Draw(canvas)
    d.text((pad, 16), "Aegis SoC — CMOS standard-cell library at transistor level",
           font=font(26, True), fill=(15, 23, 42))
    d.text((pad, 48), "expanded from the synthesized gate netlist via techlib/cmos.v "
                      "(n = NMOS, p = PMOS)", font=font(15), fill=(100, 116, 139))

    for i, (title, img) in enumerate(imgs):
        r, c = divmod(i, cols)
        x = pad + c * (box_w + pad)
        y = head_h + r * (box_h + title_h + pad)
        d.text((x + 4, y), title, font=font(18, True), fill=(15, 23, 42))
        inner_w = box_w - 8
        scale = min(inner_w / img.width, box_h / img.height, 1.4)
        im2 = img.resize((max(1, int(img.width * scale)), max(1, int(img.height * scale))),
                         Image.LANCZOS)
        canvas.paste(im2, (x + 4, y + title_h))
        d.rectangle((x, y, x + box_w, y + title_h + box_h), outline=(203, 213, 225))

    canvas.save(OUT)
    print(f"{OUT} ({canvas.width}x{canvas.height})")


if __name__ == "__main__":
    main()
