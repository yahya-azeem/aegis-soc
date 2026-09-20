#!/usr/bin/env python3
"""Host-side reference render of the chess-board kernel (rt_chess.rs).

Same scene, same shading, double precision; writes the expected framebuffer
words to rt_chess_golden.bin for the RTL testbench to compare against.
"""

import os
import sys

W = int(os.environ.get("RT_W", 40))
H = int(os.environ.get("RT_H", 40))
MAX_DEPTH = 1

EYE = (0.0, 3.0, 4.6)
TANF = 0.41421356
FY, FZ = -0.5462678, -0.8376109
UY, UZ = 0.8376109, -0.5462678

SUN = (0.55, 0.80, 0.40)

LIGHT = (0.82, 0.72, 0.56)
DARK = (0.26, 0.16, 0.11)
TABLE = (0.13, 0.11, 0.09)
WHITE = (0.90, 0.88, 0.80)
BLACK = (0.12, 0.12, 0.16)
PIECE_REFL = 0.10
PLANE_REFL = 0.16

PX = [-1.5, -0.5, 0.5, 1.5, -1.5, -0.5, 0.5, 1.5]
PZ = [-1.5, -1.5, -1.5, -1.5, 1.5, 1.5, 1.5, 1.5]
PR = [0.30, 0.26, 0.26, 0.30, 0.30, 0.26, 0.26, 0.30]
PWHITE = [True, True, True, True, False, False, False, False]


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def norm(v):
    n = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) ** 0.5
    return (v[0] / n, v[1] / n, v[2] / n)


def plane_col(px, pz):
    if px < -4.0 or px > 4.0 or pz < -4.0 or pz > 4.0:
        return TABLE
    cx = int(px + 4.0)
    cz = int(pz + 4.0)
    return LIGHT if (cx + cz) & 1 == 0 else DARK


def sph_hit(o, d, c, r):
    l = (c[0] - o[0], c[1] - o[1], c[2] - o[2])
    b = dot(l, d)
    cc = dot(l, l) - r * r
    disc = b * b - cc
    if disc < 0.0:
        return None
    s = disc ** 0.5
    for t in (b - s, b + s):
        if t > 1e-3:
            return (t, ((l[0] - d[0] * t) / r, (l[1] - d[1] * t) / r, (l[2] - d[2] * t) / r))
    return None


def clamp8(v):
    v = 0.0 if v < 0.0 else (1.0 if v > 1.0 else v)
    return int(v * 255.0 + 0.5)


def main():
    sl = norm(SUN)
    fb = bytearray()
    for y in range(H):
        v = ((y + 0.5) / H) * 2.0 - 1.0
        for x in range(W):
            u = ((x + 0.5) / W) * 2.0 - 1.0
            d = norm((u * TANF, FY + UY * (v * TANF), FZ + UZ * (v * TANF)))
            o = EYE
            acc = [0.0, 0.0, 0.0]
            gain = 1.0
            depth = 0
            while depth <= MAX_DEPTH:
                bt = 1e9
                best = None
                # plane
                if d[1] > 1e-9 or d[1] < -1e-9:
                    t = -o[1] / d[1]
                    if t > 1e-3 and t < bt:
                        col = plane_col(o[0] + d[0] * t, o[2] + d[2] * t)
                        bt = t
                        best = (t, (0.0, 1.0, 0.0), col, PLANE_REFL)
                # pieces
                for i in range(8):
                    h = sph_hit(o, d, (PX[i], PR[i], PZ[i]), PR[i])
                    if h is not None and h[0] < bt:
                        bt = h[0]
                        best = (h[0], h[1], WHITE if PWHITE[i] else BLACK, PIECE_REFL)
                if best is None:
                    f = 0.5 + 0.5 * d[1]
                    acc[0] += gain * (0.32 + (0.90 - 0.32) * f)
                    acc[1] += gain * (0.50 + (0.92 - 0.50) * f)
                    acc[2] += gain * (0.88 + (0.98 - 0.88) * f)
                    break
                t, n, col, refl = best
                hp = (o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t)
                dl = max(dot(n, sl), 0.0)
                vv = norm((-d[0], -d[1], -d[2]))
                half = norm((vv[0] + sl[0], vv[1] + sl[1], vv[2] + sl[2]))
                spec = max(dot(n, half), 0.0) ** 16
                scl = 0.16 + 0.80 * dl
                acc[0] += gain * (col[0] * scl + 0.85 * spec)
                acc[1] += gain * (col[1] * scl + 0.82 * spec)
                acc[2] += gain * (col[2] * scl + 0.78 * spec)
                dd = 2.0 * dot(d, n)
                d = norm((d[0] - dd * n[0], d[1] - dd * n[1], d[2] - dd * n[2]))
                o = (hp[0] + n[0] * 2e-3, hp[1] + n[1] * 2e-3, hp[2] + n[2] * 2e-3)
                gain *= refl
                if gain < 0.05:
                    break
                depth += 1
            px = (clamp8(acc[0]) << 16) | (clamp8(acc[1]) << 8) | clamp8(acc[2])
            fb += px.to_bytes(4, "little")
    out = os.environ.get("RT_OUT", "rt_chess_golden.bin")
    with open(out, "wb") as f:
        f.write(fb)
    print(f"chess golden framebuffer: {len(fb)} bytes ({len(fb)//4} pixels) -> {out}")


if __name__ == "__main__":
    main()
