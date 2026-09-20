//! Bare-metal RISC-V chess-board raytracer kernel (RV32IMF, no libc/runtime).
//!
//! Same structure and shading as rt_balls.rs, but the scene is a checkerboard
//! plane with pieces (spheres) in white and black, viewed from a raised angle.
//! Single-threaded; built with:
//!   RT_SCENE=chess RT_W=40 RT_H=40 bash test/vortex/build_rt.sh

#![no_std]
#![no_main]

use core::arch::global_asm;
use core::panic::PanicInfo;

const fn parse_usize(s: &str) -> usize {
    let b = s.as_bytes();
    let mut i = 0usize;
    let mut n = 0usize;
    while i < b.len() {
        let d = b[i];
        if d >= b'0' && d <= b'9' {
            n = n * 10 + (d - b'0') as usize;
        }
        i += 1;
    }
    n
}

const W: usize = match option_env!("RT_W") {
    Some(s) => {
        let n = parse_usize(s);
        if n == 0 { 40 } else { n }
    }
    None => 40,
};
const H: usize = match option_env!("RT_H") {
    Some(s) => {
        let n = parse_usize(s);
        if n == 0 { 40 } else { n }
    }
    None => 40,
};
const MAX_DEPTH: i32 = 1;

const FRAME_BASE: *mut u32 = 0x2000 as *mut u32;

// camera: eye (0,3.0,4.6) looking at the board centre
const EYEX: f32 = 0.0;
const EYEY: f32 = 3.0;
const EYEZ: f32 = 4.6;
const TANF: f32 = 0.41421356;
const FY: f32 = -0.5462678;
const FZ: f32 = -0.8376109;
const UY: f32 = 0.8376109;
const UZ: f32 = -0.5462678;

const SUN_X: f32 = 0.55;
const SUN_Y: f32 = 0.80;
const SUN_Z: f32 = 0.40;

const LIGHT_R: f32 = 0.82;
const LIGHT_G: f32 = 0.72;
const LIGHT_B: f32 = 0.56;
const DARK_R: f32 = 0.26;
const DARK_G: f32 = 0.16;
const DARK_B: f32 = 0.11;
const TABLE_R: f32 = 0.13;
const TABLE_G: f32 = 0.11;
const TABLE_B: f32 = 0.09;

// pieces: x, z, radius, colour (white / black)
const NW: usize = 4;
const PWX: [f32; 8] = [-1.5, -0.5, 0.5, 1.5, -1.5, -0.5, 0.5, 1.5];
const PWZ: [f32; 8] = [-1.5, -1.5, -1.5, -1.5, 1.5, 1.5, 1.5, 1.5];
const PWR: [f32; 8] = [0.30, 0.26, 0.26, 0.30, 0.30, 0.26, 0.26, 0.30];
const PW_IS_WHITE: [bool; 8] = [true, true, true, true, false, false, false, false];

global_asm!(
    r#"
    .section .text.start,"ax",@progbits
    .global _start
_start:
    li    sp, 0x3C00
    addi  ra, zero, 0
    jal   aegis_rt
    .word 0x0000700b
    .word 0x0000000b
1:
    j 1b
    "#,
    options(),
);

#[panic_handler]
fn panic(_info: &PanicInfo) -> ! {
    loop {}
}

#[inline(always)]
fn dot3(ax: f32, ay: f32, az: f32, bx: f32, by: f32, bz: f32) -> f32 {
    ax * bx + ay * by + az * bz
}

#[inline(always)]
fn isqrtf(x: f32) -> f32 {
    let i = 0x5F37_59DFu32.wrapping_sub(x.to_bits() >> 1);
    let mut y = f32::from_bits(i);
    y = y * (1.5 - 0.5 * x * y * y);
    y = y * (1.5 - 0.5 * x * y * y);
    y
}

#[inline(always)]
fn sqrtt(disc: f32) -> f32 {
    if disc <= 0.0 { 0.0 } else { disc * isqrtf(disc) }
}

#[inline(always)]
fn p16(x: f32) -> f32 {
    let mut y = x;
    y = y * y;
    y = y * y;
    y = y * y;
    y = y * y;
    y
}

#[inline(always)]
fn pack(r: f32, g: f32, b: f32) -> u32 {
    let ci = |v: f32| -> u32 {
        let v = if v < 0.0 { 0.0 } else { v };
        let v = if v > 1.0 { 1.0 } else { v };
        (v * 255.0 + 0.5) as u32
    };
    (ci(r) << 16) | (ci(g) << 8) | ci(b)
}

// board / table colour at a plane hit
#[inline(always)]
fn plane_col(px: f32, pz: f32) -> (f32, f32, f32) {
    if px < -4.0 || px > 4.0 || pz < -4.0 || pz > 4.0 {
        return (TABLE_R, TABLE_G, TABLE_B);
    }
    let cx = (px + 4.0) as i32;
    let cz = (pz + 4.0) as i32;
    if (cx + cz) & 1 == 0 {
        (LIGHT_R, LIGHT_G, LIGHT_B)
    } else {
        (DARK_R, DARK_G, DARK_B)
    }
}

#[inline(always)]
fn sph_hit(ox: f32, oy: f32, oz: f32, dx: f32, dy: f32, dz: f32,
           cx: f32, cy: f32, cz: f32, cr: f32) -> Option<(f32, f32, f32, f32)> {
    let lx = cx - ox; let ly = cy - oy; let lz = cz - oz;
    let b = dot3(lx, ly, lz, dx, dy, dz);
    let c = dot3(lx, ly, lz, lx, ly, lz) - cr * cr;
    let disc = b * b - c;
    if disc < 0.0 { return None; }
    let denom = 1.0 / cr;
    let s = sqrtt(disc);
    let t0 = b - s;
    if t0 > 1e-3 { return Some((t0, (lx - dx * t0) * denom, (ly - dy * t0) * denom, (lz - dz * t0) * denom)); }
    let t1 = b + s;
    if t1 > 1e-3 { return Some((t1, (lx - dx * t1) * denom, (ly - dy * t1) * denom, (lz - dz * t1) * denom)); }
    None
}

// nearest hit -> (t, nx, ny, nz, cr, cg, cb, refl)
#[inline(always)]
fn scene_hit(ox: f32, oy: f32, oz: f32, dx: f32, dy: f32, dz: f32)
    -> Option<(f32, f32, f32, f32, f32, f32, f32, f32)> {
    let mut bt = 1e9f32;
    let mut hit = None;
    // ground plane y = 0
    if dy > 1e-9 || dy < -1e-9 {
        let t = -oy / dy;
        if t > 1e-3 && t < bt {
            let (cr, cg, cb) = plane_col(ox + dx * t, oz + dz * t);
            bt = t;
            hit = Some((t, 0.0, 1.0, 0.0, cr, cg, cb, 0.16));
        }
    }
    // pieces
    let mut i = 0usize;
    while i < 8 {
        let r = PWR[i];
        let (cr, cg, cb, refl) = if PW_IS_WHITE[i] {
            (0.90f32, 0.88f32, 0.80f32, 0.10f32)
        } else {
            (0.12f32, 0.12f32, 0.16f32, 0.10f32)
        };
        if let Some((t, nx, ny, nz)) = sph_hit(ox, oy, oz, dx, dy, dz, PWX[i], r, PWZ[i], r) {
            if t < bt {
                bt = t;
                hit = Some((t, nx, ny, nz, cr, cg, cb, refl));
            }
        }
        i += 1;
    }
    hit
}

#[no_mangle]
pub extern "C" fn aegis_rt() {
    let inv_sun = isqrtf(dot3(SUN_X, SUN_Y, SUN_Z, SUN_X, SUN_Y, SUN_Z));
    let (slx, sly, slz) = (SUN_X * inv_sun, SUN_Y * inv_sun, SUN_Z * inv_sun);

    let mut y = 0usize;
    while y < H {
        let v = ((y as f32 + 0.5) / H as f32) * 2.0 - 1.0;
        let mut x = 0usize;
        while x < W {
            let u = ((x as f32 + 0.5) / W as f32) * 2.0 - 1.0;

            // primary ray from the hardcoded camera basis
            let mut dx = u * TANF;
            let mut dy = FY + UY * (v * TANF);
            let mut dz = FZ + UZ * (v * TANF);
            let dn = isqrtf(dot3(dx, dy, dz, dx, dy, dz));
            dx *= dn; dy *= dn; dz *= dn;

            let (mut ox, mut oy, mut oz) = (EYEX, EYEY, EYEZ);
            let mut acc_r = 0.0f32;
            let mut acc_g = 0.0f32;
            let mut acc_b = 0.0f32;
            let mut gain = 1.0f32;

            let mut depth = 0i32;
            while depth <= MAX_DEPTH {
                match scene_hit(ox, oy, oz, dx, dy, dz) {
                    Some((t, nx, ny, nz, cr, cg, cb, refl)) => {
                        let (hx, hy, hz) = (ox + dx * t, oy + dy * t, oz + dz * t);

                        let mut dl = dot3(nx, ny, nz, slx, sly, slz);
                        if dl < 0.0 { dl = 0.0; }

                        let (vx, vy, vz) = (-dx, -dy, -dz);
                        let vn = isqrtf(dot3(vx, vy, vz, vx, vy, vz));
                        let (vx, vy, vz) = (vx * vn, vy * vn, vz * vn);
                        let (hx2, hy2, hz2) = (vx + slx, vy + sly, vz + slz);
                        let hn = isqrtf(dot3(hx2, hy2, hz2, hx2, hy2, hz2));
                        let (hx2, hy2, hz2) = (hx2 * hn, hy2 * hn, hz2 * hn);
                        let mut spec = dot3(nx, ny, nz, hx2, hy2, hz2);
                        if spec < 0.0 { spec = 0.0; }
                        spec = p16(spec);

                        let scl = 0.16 + 0.80 * dl;
                        acc_r += gain * (cr * scl + 0.85 * spec);
                        acc_g += gain * (cg * scl + 0.82 * spec);
                        acc_b += gain * (cb * scl + 0.78 * spec);

                        let nd = dot3(dx, dy, dz, nx, ny, nz);
                        let mut rx = dx - 2.0 * nd * nx;
                        let mut ry = dy - 2.0 * nd * ny;
                        let mut rz = dz - 2.0 * nd * nz;
                        let rn = isqrtf(dot3(rx, ry, rz, rx, ry, rz));
                        rx *= rn; ry *= rn; rz *= rn;
                        dx = rx; dy = ry; dz = rz;
                        ox = hx + nx * 2e-3;
                        oy = hy + ny * 2e-3;
                        oz = hz + nz * 2e-3;
                        gain *= refl;
                        if gain < 0.05 { break; }
                        depth += 1;
                    }
                    None => {
                        // sky gradient
                        let f = 0.5 + 0.5 * dy;
                        acc_r += gain * (0.32 + (0.90 - 0.32) * f);
                        acc_g += gain * (0.50 + (0.92 - 0.50) * f);
                        acc_b += gain * (0.88 + (0.98 - 0.88) * f);
                        break;
                    }
                }
            }

            unsafe {
                core::ptr::write_volatile(FRAME_BASE.add(y * W + x), pack(acc_r, acc_g, acc_b));
            }
            x += 1;
        }
        y += 1;
    }
}
