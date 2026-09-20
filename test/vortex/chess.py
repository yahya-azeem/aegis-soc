#!/usr/bin/env python3
"""High-resolution host-side raytracer: a chess board with pieces.

This is the "hero" render and the scene reference for the GPU kernel. It is a
small Whitted-style raytracer (plane/checker board, spheres, capped vertical
cylinders, boxes) with shadows, specular highlights and reflection bounces.

  CHESS_W=800 CHESS_H=600 CHESS_SS=2 python3 test/vortex/chess.py [out.png]
"""

import math
import os
import sys
from multiprocessing import Pool

from PIL import Image

W = int(os.environ.get("CHESS_W", 800))
H = int(os.environ.get("CHESS_H", 600))
SS = int(os.environ.get("CHESS_SS", 2))
MAXD = int(os.environ.get("CHESS_DEPTH", 2))
OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/raytrace_chess.png"

EYE = (3.2, 5.8, 9.8)
TARGET = (0.0, 0.45, -0.1)
FOV = 42.0
SUN = (0.55, 0.92, 0.30)
FILL = (-0.55, 0.40, 0.72)
SKY_TOP = (0.35, 0.52, 0.90)
SKY_BOT = (0.85, 0.90, 0.98)
AMBIENT = 0.20
EPS = 1e-4

WHITE = (0.90, 0.88, 0.82)
BLACK = (0.10, 0.10, 0.13)
BOARD_L = (0.82, 0.72, 0.56)
BOARD_D = (0.30, 0.19, 0.13)
TABLE = (0.16, 0.13, 0.11)
WOOD = (0.45, 0.29, 0.18)


def vadd(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def vsub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def vmul(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def vdot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def vnorm(a):
    n = math.sqrt(vdot(a, a))
    return (a[0] / n, a[1] / n, a[2] / n)


def build_scene():
    # primitives: ("sph", cx,cy,cz, r, col, refl)
    #             ("cyl", cx,cz, y0,y1, r, col, refl)
    #             ("box", x0,y0,z0, x1,y1,z1, col, refl)
    P = []

    def sph(c, r, col, refl=0.05):
        P.append(("sph", c[0], c[1], c[2], r, col, refl))

    def cyl(x, z, y0, y1, r, col, refl=0.05):
        P.append(("cyl", x, z, y0, y1, r, col, refl))

    def box(a, b, col, refl=0.04):
        P.append(("box", a[0], a[1], a[2], b[0], b[1], b[2], col, refl))

    def pawn(x, z, col):
        cyl(x, z, 0.0, 0.07, 0.29, col)
        cyl(x, z, 0.07, 0.34, 0.16, col)
        sph((x, 0.44, z), 0.17, col, 0.10)

    def rook(x, z, col):
        cyl(x, z, 0.0, 0.07, 0.30, col)
        cyl(x, z, 0.07, 0.52, 0.20, col)
        cyl(x, z, 0.52, 0.64, 0.25, col)

    def bishop(x, z, col):
        cyl(x, z, 0.0, 0.07, 0.29, col)
        cyl(x, z, 0.07, 0.48, 0.15, col)
        sph((x, 0.60, z), 0.16, col, 0.12)
        sph((x, 0.78, z), 0.07, col, 0.12)

    def knight(x, z, col):
        cyl(x, z, 0.0, 0.07, 0.29, col)
        cyl(x, z, 0.07, 0.42, 0.17, col)
        sph((x, 0.55, z), 0.17, col, 0.10)
        box((x - 0.10, 0.58, z - 0.26), (x + 0.10, 0.80, z - 0.05), col)

    def queen(x, z, col):
        cyl(x, z, 0.0, 0.07, 0.31, col)
        cyl(x, z, 0.07, 0.62, 0.15, col)
        sph((x, 0.74, z), 0.18, col, 0.14)
        sph((x, 0.94, z), 0.07, col, 0.14)

    def king(x, z, col):
        cyl(x, z, 0.0, 0.07, 0.32, col)
        cyl(x, z, 0.07, 0.66, 0.16, col)
        sph((x, 0.78, z), 0.18, col, 0.14)
        box((x - 0.05, 0.92, z - 0.05), (x + 0.05, 1.06, z + 0.05), col)
        box((x - 0.12, 0.97, z - 0.04), (x + 0.12, 1.03, z + 0.04), col)

    kind = {"p": pawn, "r": rook, "b": bishop, "n": knight, "q": queen, "k": king}
    # file, rank (0..7); white on ranks 0-1, black on 6-7
    layout = [
        ("r", 0, 0, WHITE), ("n", 1, 0, WHITE), ("b", 2, 0, WHITE), ("q", 3, 0, WHITE),
        ("k", 4, 0, WHITE), ("b", 5, 0, WHITE), ("n", 6, 0, WHITE), ("r", 7, 0, WHITE),
        ("p", 0, 1, WHITE), ("p", 1, 1, WHITE), ("p", 2, 1, WHITE), ("p", 3, 1, WHITE),
        ("p", 5, 1, WHITE), ("p", 6, 1, WHITE),
        ("r", 0, 7, BLACK), ("n", 1, 7, BLACK), ("b", 2, 7, BLACK), ("q", 3, 7, BLACK),
        ("k", 4, 7, BLACK), ("b", 5, 7, BLACK), ("n", 6, 7, BLACK), ("r", 7, 7, BLACK),
        ("p", 0, 6, BLACK), ("p", 1, 6, BLACK), ("p", 3, 6, BLACK), ("p", 4, 6, BLACK),
        ("p", 5, 6, BLACK), ("p", 6, 6, BLACK), ("p", 7, 6, BLACK),
    ]
    for k, f, r, col in layout:
        x = (f - 3.5) * 1.0
        z = (r - 3.5) * 1.0
        kind[k](x, z, col)

    # border frame around the board
    for i in range(-4, 4):
        pass
    return P


PRIMS = build_scene()
SUN_N = vnorm(SUN)
FILL_N = vnorm(FILL)


def sky(d):
    f = max(0.0, min(1.0, 0.5 + 0.6 * d[1]))
    return (SKY_TOP[0] + (SKY_BOT[0] - SKY_TOP[0]) * f,
            SKY_TOP[1] + (SKY_BOT[1] - SKY_TOP[1]) * f,
            SKY_TOP[2] + (SKY_BOT[2] - SKY_TOP[2]) * f)


def board_color(x, z):
    if abs(x) > 4.0 or abs(z) > 4.0:
        return None
    fx = math.floor(x + 4.0)
    fz = math.floor(z + 4.0)
    return BOARD_L if (fx + fz) % 2 == 0 else BOARD_D


def hit_prim(o, d, prim, tmax):
    kind = prim[0]
    if kind == "sph":
        _, cx, cy, cz, r, col, refl = prim
        oc = (o[0] - cx, o[1] - cy, o[2] - cz)
        b = vdot(oc, d)
        c = vdot(oc, oc) - r * r
        disc = b * b - c
        if disc < 0:
            return None
        s = math.sqrt(disc)
        t = -b - s
        if t < EPS:
            t = -b + s
            if t < EPS:
                return None
        if t >= tmax:
            return None
        p = (o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t)
        n = vnorm(vsub(p, (cx, cy, cz)))
        return (t, n, col, refl, p)
    if kind == "cyl":
        _, cx, cz, y0, y1, r, col, refl = prim
        ox, oz = o[0] - cx, o[2] - cz
        a = d[0] * d[0] + d[2] * d[2]
        if a < 1e-9:
            return None
        b = 2 * (ox * d[0] + oz * d[2])
        c = ox * ox + oz * oz - r * r
        disc = b * b - 4 * a * c
        best = None
        if disc >= 0:
            sq = math.sqrt(disc)
            for t in ((-b - sq) / (2 * a), (-b + sq) / (2 * a)):
                if t < EPS or t >= tmax:
                    continue
                y = o[1] + d[1] * t
                if y0 <= y <= y1:
                    p = (o[0] + d[0] * t, y, o[2] + d[2] * t)
                    n = vnorm((p[0] - cx, 0.0, p[2] - cz))
                    best = (t, n, col, refl, p)
                    break
        # top cap
        if abs(d[1]) > 1e-9:
            t = (y1 - o[1]) / d[1]
            if t > EPS and t < tmax and (best is None or t < best[0]):
                px, pz = o[0] + d[0] * t, o[2] + d[2] * t
                if (px - cx) ** 2 + (pz - cz) ** 2 <= r * r:
                    best = (t, (0.0, 1.0, 0.0), col, refl, (px, y1, pz))
        return best
    if kind == "box":
        _, x0, y0, z0, x1, y1, z1, col, refl = prim
        tmin, tmax2 = -1e30, 1e30
        nrm = None
        for axis in range(3):
            if abs(d[axis]) < 1e-12:
                if o[axis] < (x0, y0, z0)[axis] or o[axis] > (x1, y1, z1)[axis]:
                    return None
                continue
            inv = 1.0 / d[axis]
            t1 = ((x0, y0, z0)[axis] - o[axis]) * inv
            t2 = ((x1, y1, z1)[axis] - o[axis]) * inv
            sign = -1.0
            if t1 > t2:
                t1, t2 = t2, t1
                sign = 1.0
            if t1 > tmin:
                tmin = t1
                nn = [0.0, 0.0, 0.0]
                nn[axis] = sign
                nrm = tuple(nn)
            if t2 < tmax2:
                tmax2 = t2
        if tmin > tmax2 or tmin < EPS or tmin >= tmax:
            return None
        p = (o[0] + d[0] * tmin, o[1] + d[1] * tmin, o[2] + d[2] * tmin)
        return (tmin, nrm, col, refl, p)
    return None


def intersect(o, d, tmax=1e30):
    best = None
    if abs(d[1]) > 1e-9:
        t = -o[1] / d[1]
        if EPS < t < tmax:
            p = (o[0] + d[0] * t, 0.0, o[2] + d[2] * t)
            col = board_color(p[0], p[2])
            if col is not None:
                best = (t, (0.0, 1.0, 0.0), col, 0.12, p)
            else:
                best = (t, (0.0, 1.0, 0.0), TABLE, 0.05, p)
    for prim in PRIMS:
        h = hit_prim(o, d, prim, tmax if best is None else best[0])
        if h is not None:
            best = h
    return best


def occluded(o, d):
    for prim in PRIMS:
        if hit_prim(o, d, prim, 1e30) is not None:
            return True
    # board shadow
    if abs(d[1]) > 1e-9:
        t = -o[1] / d[1]
        if t > 1e-3:
            p = (o[0] + d[0] * t, 0.0, o[2] + d[2] * t)
            if board_color(p[0], p[2]) is not None:
                return True
    return False


def trace(o, d, depth):
    h = intersect(o, d)
    if h is None:
        return sky(d)
    t, n, col, refl, p = h
    # shadow
    so = vadd(p, vmul(n, 2e-3))
    if occluded(so, SUN_N):
        lit = 0.0
        spec = 0.0
    else:
        lit = 0.95 * max(vdot(n, SUN_N), 0.0)
        hv = vnorm(vsub(SUN_N, d))
        spec = max(vdot(n, hv), 0.0) ** 80
    lit += 0.40 * max(vdot(n, FILL_N), 0.0)   # fill light (no shadow) so black pieces read
    out = (col[0] * (AMBIENT + lit) + 0.85 * spec,
           col[1] * (AMBIENT + lit * 0.97) + 0.82 * spec,
           col[2] * (AMBIENT + lit * 0.93) + 0.78 * spec)
    if refl > 0.0 and depth < MAXD:
        rd = vnorm(vsub(d, vmul(n, 2.0 * vdot(d, n))))
        r = trace(so, rd, depth + 1)
        out = (out[0] + refl * r[0], out[1] + refl * r[1], out[2] + refl * r[2])
        out = vmul(out, 1.0 / (1.0 + refl))
    return out


def clamp8(v):
    v = 0.0 if v < 0.0 else (1.0 if v > 1.0 else v)
    return int(v * 255.0 + 0.5)


def render_rows(args):
    y0, y1 = args
    fwd = vnorm(vsub(TARGET, EYE))
    right = vnorm((fwd[2], 0.0, -fwd[0]))
    up = vnorm((right[1] * fwd[2] - right[2] * fwd[1],
                right[2] * fwd[0] - right[0] * fwd[2],
                right[0] * fwd[1] - right[1] * fwd[0]))
    aspect = W / H
    tanf = math.tan(math.radians(FOV) * 0.5)
    buf = bytearray()
    inv = 1.0 / (SS * SS)
    for py in range(y0, y1):
        for px in range(W):
            acc = [0.0, 0.0, 0.0]
            for sy in range(SS):
                for sx in range(SS):
                    u = ((px + (sx + 0.5) / SS) / W) * 2.0 - 1.0
                    v = 1.0 - ((py + (sy + 0.5) / SS) / H) * 2.0
                    d = vnorm(vadd(vadd(vmul(right, u * aspect * tanf),
                                          vmul(up, v * tanf)), fwd))
                    c = trace(EYE, d, 0)
                    acc[0] += c[0]
                    acc[1] += c[1]
                    acc[2] += c[2]
            buf.append(clamp8(acc[0] * inv))
            buf.append(clamp8(acc[1] * inv))
            buf.append(clamp8(acc[2] * inv))
    return (y0, y1, bytes(buf))


def main():
    bands = [(y, min(H, y + 16)) for y in range(0, H, 16)]
    img = Image.new("RGB", (W, H))
    pix = img.load()
    with Pool() as pool:
        for y0, y1, buf in pool.imap_unordered(render_rows, bands):
            i = 0
            for yy in range(y0, y1):
                for xx in range(W):
                    pix[xx, yy] = (buf[i], buf[i + 1], buf[i + 2])
                    i += 3
    os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
    img.save(OUT)
    print(f"chess render {W}x{H} (ss={SS}, depth={MAXD}) -> {OUT}")


if __name__ == "__main__":
    main()
