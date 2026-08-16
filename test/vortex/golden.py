#!/usr/bin/env python3
"""Host-side reference render of the vortex raytracer.

Replicates rt_balls.rs (same scene constants, same reflection/shading model,
double precision) and writes the expected RGBA words to rt_balls_golden.bin.
The RTL testbench compares the kernel's framebuffer words against this with a
per-channel tolerance (mirrored-ball scenes are stable in float, so byte-exact
matching is not required).
"""

import os

W = int(os.environ.get("RT_W", 40))
H = int(os.environ.get("RT_H", 40))
MAX_DEPTH = 2

EYE = (0.0, 0.0, 4.0)
FWD = (0.0, 0.0, -1.0)
SUN = (0.65, 0.80, 0.55)

SKY_TOP = (0.30, 0.45, 0.85)
SKY_BOT = (0.92, 0.92, 0.96)

BALLS = [
    # (x, y, z, r, colour, refl)
    (-1.7, 0.00, 0.70, 0.70, (0.15, 0.70, 0.15), 0.55),
    (1.1, 0.30, -0.10, 0.80, (0.20, 0.20, 0.90), 0.40),
    (0.10, -1.40, 0.40, 0.65, (0.95, 0.75, 0.10), 0.25),
]


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def normalize(v):
    n = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) ** 0.5
    return (v[0] / n, v[1] / n, v[2] / n)


def sph_hit(o, d, c, r):
    l = (c[0] - o[0], c[1] - o[1], c[2] - o[2])
    b = dot(l, d)
    cc = dot(l, l) - r * r
    disc = b * b - cc
    if disc < 0.0:
        return None
    s = disc ** 0.5
    t0 = b - s
    if t0 > 1e-3:
        return (t0, ((l[0] - d[0] * t0) / r, (l[1] - d[1] * t0) / r, (l[2] - d[2] * t0) / r))
    t1 = b + s
    if t1 > 1e-3:
        return (t1, ((l[0] - d[0] * t1) / r, (l[1] - d[1] * t1) / r, (l[2] - d[2] * t1) / r))
    return None


def clamp8(v):
    v = min(max(v, 0.0), 1.0)
    return int(v * 255.0 + 0.5)


def main():
    sl = normalize(SUN)
    fb = bytearray()

    for y in range(H):
        v = ((y + 0.5) / H) * 2.0 - 1.0
        for x in range(W):
            u = ((x + 0.5) / W) * 2.0 - 1.0
            d = normalize((u * 0.7, v * 0.7, FWD[2]))
            o = EYE
            acc = [0.0, 0.0, 0.0]
            gain = 1.0

            depth = 0
            while depth <= MAX_DEPTH:
                best = None
                for b in BALLS:
                    h = sph_hit(o, d, b[:3], b[3])
                    if h is not None and (best is None or h[0] < best[0]):
                        best = (h[0], h[1], b[4], b[5])

                if best is None:
                    f = 0.5 + 0.5 * d[1]  # ^ sky, v ground
                    acc[0] += gain * (SKY_TOP[0] + (SKY_BOT[0] - SKY_TOP[0]) * f)
                    acc[1] += gain * (SKY_TOP[1] + (SKY_BOT[1] - SKY_TOP[1]) * f)
                    acc[2] += gain * (SKY_TOP[2] + (SKY_BOT[2] - SKY_TOP[2]) * f)
                    break

                t, n, col, refl = best
                hp = (o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t)

                dl = max(dot(n, sl), 0.0)

                vv = normalize((-d[0], -d[1], -d[2]))
                half = normalize((vv[0] + sl[0], vv[1] + sl[1], vv[2] + sl[2]))
                spec = max(dot(n, half), 0.0) ** 16

                scl = 0.12 + 0.85 * dl
                acc[0] += gain * (col[0] * scl + 0.90 * spec)
                acc[1] += gain * (col[1] * scl + 0.85 * spec)
                acc[2] += gain * (col[2] * scl + 0.80 * spec)

                dd = 2.0 * dot(d, n)
                d = normalize((d[0] - dd * n[0], d[1] - dd * n[1], d[2] - dd * n[2]))
                o = (hp[0] + n[0] * 2e-3, hp[1] + n[1] * 2e-3, hp[2] + n[2] * 2e-3)

                gain *= refl
                if gain < 0.05:
                    break
                depth += 1

            px = (clamp8(acc[0]) << 16) | (clamp8(acc[1]) << 8) | clamp8(acc[2])
            fb += px.to_bytes(4, "little")

    with open("rt_balls_golden.bin", "wb") as f:
        f.write(fb)
    print(f"golden framebuffer: {len(fb)} bytes ({len(fb) // 4} pixels)")


if __name__ == "__main__":
    main()