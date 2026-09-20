#!/usr/bin/env python3
"""Live viewer for the Aegis raytracer.

Shows a hero reference image until the co-simulation starts streaming frames,
then switches to the image building up in real time as the kernel runs on the
real Vortex RTL.

  python3 scripts/live_view.py --dir build/live --hero docs/raytrace_chess.png
"""

import argparse
import os
import tkinter as tk
from tkinter import ttk

from PIL import Image, ImageTk

BG = "#0b1220"
FG = "#e2e8f0"
SUB = "#94a3b8"
ACCENT = "#22c55e"


def read_status(path):
    st = {"step": 0, "fb_writes": 0, "busy": 0, "done": 0}
    try:
        for line in open(path):
            if "=" in line:
                k, v = line.strip().split("=", 1)
                if k in st:
                    st[k] = int(float(v))
    except Exception:
        pass
    return st


def fit(img, box):
    scale = min(box / img.width, box / img.height)
    return img.resize((max(1, int(img.width * scale)), max(1, int(img.height * scale))), Image.LANCZOS)


class Viewer:
    def __init__(self, directory, scale, total, hero):
        self.dir = directory
        self.scale = scale
        self.total = max(1, total)
        self.last_mtime = None
        self.showing_live = False

        self.root = tk.Tk()
        self.root.title("Aegis SoC  -  real Vortex RTL  -  ray tracing")
        self.root.configure(bg=BG)
        self.root.protocol("WM_DELETE_WINDOW", self.root.destroy)

        tk.Label(self.root, text="Aegis SoC", bg=BG, fg=FG,
                 font=("DejaVu Sans", 20, "bold")).pack(anchor="w", padx=16, pady=(14, 0))
        self.subtitle = tk.Label(
            self.root,
            text="ray tracer executing on the real Vortex GPGPU RTL  -  writing through shared HBM3",
            bg=BG, fg=SUB, font=("DejaVu Sans", 10))
        self.subtitle.pack(anchor="w", padx=16)

        self.canvas = tk.Label(self.root, bg=BG, bd=0)
        self.canvas.pack(padx=16, pady=12)

        self.bar = ttk.Progressbar(self.root, length=640, maximum=100, mode="determinate")
        self.bar.pack(padx=16, pady=(0, 6), fill="x")

        self.hud = tk.Label(self.root, text="starting GPU...", bg=BG, fg=SUB,
                            font=("DejaVu Sans Mono", 11), justify="left")
        self.hud.pack(anchor="w", padx=16, pady=(0, 14))

        # hero splash, shown until the first live frame arrives
        self.hero_photo = None
        if hero and os.path.exists(hero):
            try:
                self.hero_photo = ImageTk.PhotoImage(fit(Image.open(hero).convert("RGB"), 640))
                self.canvas.configure(image=self.hero_photo)
            except Exception:
                self.hero_photo = None

        self.photo = None
        self.root.after(100, self.update)

    def update(self):
        frame_path = os.path.join(self.dir, "frame.ppm")
        try:
            mtime = os.path.getmtime(frame_path)
        except OSError:
            mtime = None
        if mtime and mtime != self.last_mtime:
            try:
                img = Image.open(frame_path).convert("RGB")
                if self.scale > 1:
                    img = img.resize((img.width * self.scale, img.height * self.scale), Image.NEAREST)
                self.photo = ImageTk.PhotoImage(img)
                self.canvas.configure(image=self.photo)
                self.last_mtime = mtime
                self.showing_live = True
                self.subtitle.configure(
                    text="ray tracer executing on the real Vortex GPGPU RTL  -  writing through shared HBM3")
            except Exception:
                pass

        if not self.showing_live and self.hero_photo is not None:
            self.hud.configure(text="reference render (host)  -  waiting for the GPU's first frame...", fg=SUB)
            self.root.after(100, self.update)
            return

        st = read_status(os.path.join(self.dir, "status.txt"))
        pct = min(100.0, 100.0 * st["step"] / self.total) if not st["done"] else 100.0
        self.bar["value"] = pct
        state = "RENDER COMPLETE (framebuffer verified vs golden)" if st["done"] else "RENDERING..."
        self.hud.configure(
            text=f"{state}\n"
                 f"sim cycles : {st['step']:>10,}\n"
                 f"pixels written to HBM3 : {st['fb_writes']:>5}\n"
                 f"vx_busy : {'yes' if st['busy'] else 'no ':>3}",
            fg=ACCENT if st["done"] else SUB,
        )
        self.root.after(100, self.update)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="build/live")
    ap.add_argument("--hero", default="docs/raytrace_chess.png")
    ap.add_argument("--scale", type=int, default=16)
    ap.add_argument("--total", type=int, default=7_500_000, help="expected retire step (progress bar)")
    a = ap.parse_args()
    Viewer(a.dir, a.scale, a.total, a.hero).root.mainloop()


if __name__ == "__main__":
    main()
