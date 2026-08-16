//! Bare-metal RISC-V raytracer kernel (RV32IMF, no libc, no runtime).
//!
//! Compiled with the local `rustc` for `riscv32imafc-unknown-none-elf`
//! with `-C target-feature=-c,-a` so the instruction stream stays inside
//! the Vortex RTL's supported set (no compressed, no atomics). All
//! constants fold into registers; the kernel keeps itself text-only so the
//! flat binary is the code image for the shared HBM3 stack.

#![no_std]
#![no_main]
#![allow(unused_assignments)] // final `bt = t;` in the nearest-hit scan is a benign dead store

use core::arch::global_asm;
use core::panic::PanicInfo;

// ---------------------------------------------------------------------------
// Parameters
// ---------------------------------------------------------------------------

// Render resolution. Overridable at build time: `RT_W=20 RT_H=20 ...`.
// Default 40x40 (slow for Verilator co-sim; use 20x20 there).
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
        if n == 0 {
            40
        } else {
            n
        }
    }
    None => 40,
};
const H: usize = match option_env!("RT_H") {
    Some(s) => {
        let n = parse_usize(s);
        if n == 0 {
            40
        } else {
            n
        }
    }
    None => 40,
};
const MAX_DEPTH: i32 = 2; // reflection bounces (0 reports pixels as-viewed)

// Address map (shared memory): kernel image is loaded at VMA 0x100 and the
// starup PC is 0x100. The reading stack sits at 0x8000 (clear of the image)
// and the framebuffer is at 0x10000 -- the verified GPU->shared-HBM3 target.
// Shared-memory layout (16KB HBM3 model folds addresses via addr[13:6], so the
// regions MUST be rIdx-disjoint from the code image at 0x100..0xA78):
//   - framebuffer base: 0x2000  (up to 100 lines for 40x40)
//   - reading stack:    top at 0x3C00
// Address map (shared memory): kernel image is loaded at VMA 0x100 and the
// startup PC is 0x100. The framebuffer and reading stack live below 0x4000 as
// disjoint regions of the 16KB HBM3 model (see FRAME_BASE / entry asm).
const FRAME_BASE: *mut u32 = 0x2000 as *mut u32;

const EYE_X: f32 = 0.0;
const EYE_Y: f32 = 0.0;
const EYE_Z: f32 = 4.0;
const FWD_Z: f32 = -1.0;

const SUN_X: f32 = 0.65;
const SUN_Y: f32 = 0.80;
const SUN_Z: f32 = 0.55;

const SKY_TOP_R: f32 = 0.30;
const SKY_TOP_G: f32 = 0.45;
const SKY_TOP_B: f32 = 0.85;
const SKY_BOT_R: f32 = 0.92;
const SKY_BOT_G: f32 = 0.92;
const SKY_BOT_B: f32 = 0.96;

const S0_X: f32 = -1.7;
const S0_Y: f32 = 0.00;
const S0_Z: f32 = 0.70;
const S0_R: f32 = 0.70;
const S0_CR: f32 = 0.15;
const S0_CG: f32 = 0.70;
const S0_CB: f32 = 0.15;
const S0_RF: f32 = 0.55;

const S1_X: f32 = 1.1;
const S1_Y: f32 = 0.30;
const S1_Z: f32 = -0.10;
const S1_R: f32 = 0.80;
const S1_CR: f32 = 0.20;
const S1_CG: f32 = 0.20;
const S1_CB: f32 = 0.90;
const S1_RF: f32 = 0.40;

const S2_X: f32 = 0.10;
const S2_Y: f32 = -1.40;
const S2_Z: f32 = 0.40;
const S2_R: f32 = 0.65;
const S2_CR: f32 = 0.95;
const S2_CG: f32 = 0.75;
const S2_CB: f32 = 0.10;
const S2_RF: f32 = 0.25;

// ---------------------------------------------------------------------------
// Entry: set up a scratch stack at 0x3C00 (above the ~3KB image) then run.
//   tmc x0   : deactivate all lanes
//   wsync    : wait for memory ops in flight before retiring
// ---------------------------------------------------------------------------
// The testbench co-sim showed `wsync` stalling forever under heavy store
// traffic; RT_NOWSYNC=1 emits a wsync-free tail (retire on `tmc` alone) and
// the host flushes the cache via the cache-flush DCR read instead.
// The testbench co-sim showed `wsync` stalling forever under heavy store
// traffic; building with `--cfg RT_NOWSYNC` emits a wsync-free tail (retire
// on `tmc` alone) and the host flushes the cache via the cache-flush DCR read.

#[cfg(not(RT_NOWSYNC))]
core::arch::global_asm!(
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

#[cfg(RT_NOWSYNC)]
core::arch::global_asm!(
    r#"
    .section .text.start,"ax",@progbits
    .global _start
_start:
    li    sp, 0x3C00
    addi  ra, zero, 0
    jal   aegis_rt
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

// ---------------------------------------------------------------------------
// Math
// ---------------------------------------------------------------------------

#[inline(always)]
fn dot3(ax: f32, ay: f32, az: f32, bx: f32, by: f32, bz: f32) -> f32 {
    ax * bx + ay * by + az * bz
}

#[inline(always)]
fn isqrtf(x: f32) -> f32 {
    // fast inverse sqrt: magic-number seed + two Newton steps
    let i = 0x5F37_59DFu32.wrapping_sub(x.to_bits() >> 1);
    let mut y = f32::from_bits(i);
    y = y * (1.5 - 0.5 * x * y * y);
    y = y * (1.5 - 0.5 * x * y * y);
    y
}

#[inline(always)]
fn sqrtt(disc: f32) -> f32 {
    if disc <= 0.0 {
        0.0
    } else {
        disc * isqrtf(disc)
    }
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

// ray-sphere: nearest hit -> Some((t, nx, ny, nz)), scene hit else None
#[inline(always)]
fn sph_hit(
    ox: f32,
    oy: f32,
    oz: f32,
    dx: f32,
    dy: f32,
    dz: f32,
    cx: f32,
    cy: f32,
    cz: f32,
    cr: f32,
) -> Option<(f32, f32, f32, f32)> {
    let lx = cx - ox;
    let ly = cy - oy;
    let lz = cz - oz;
    let b = dot3(lx, ly, lz, dx, dy, dz);
    let c = dot3(lx, ly, lz, lx, ly, lz) - cr * cr;
    let disc = b * b - c;
    if disc < 0.0 {
        return None;
    }
    let denom = 1.0 / cr;
    let s = sqrtt(disc);
    let t0 = b - s;
    if t0 > 1e-3 {
        return Some((t0, (lx - dx * t0) * denom, (ly - dy * t0) * denom, (lz - dz * t0) * denom));
    }
    let t1 = b + s;
    if t1 > 1e-3 {
        return Some((t1, (lx - dx * t1) * denom, (ly - dy * t1) * denom, (lz - dz * t1) * denom));
    }
    None
}

// same signature as the SIMT kernel: returns Some((t, mat, nx, ny, nz))
// (t == u0x7FFFFFFF for "miss"? no: we keep it sequential / single-warp)
// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

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

            // primary ray
            let mut dx = u * 0.7;
            let mut dy = v * 0.7;
            let mut dz = FWD_Z;
            let dn = isqrtf(dx * dx + dy * dy + dz * dz);
            dx *= dn;
            dy *= dn;
            dz *= dn;

            let (mut ox, mut oy, mut oz) = (EYE_X, EYE_Y, EYE_Z);
            let mut acc_r = 0.0f32;
            let mut acc_g = 0.0f32;
            let mut acc_b = 0.0f32;
            let mut gain = 1.0f32;

            let mut depth = 0i32;
            while depth <= MAX_DEPTH {
                // nearest hit across the three balls
                let mut bt = 1e9f32;
                let mut nhit: Option<(f32, f32, f32, f32, u8)> = None;
                if let Some((t, nx, ny, nz)) =
                    sph_hit(ox, oy, oz, dx, dy, dz, S0_X, S0_Y, S0_Z, S0_R)
                {
                    if t < bt {
                        bt = t;
                        nhit = Some((t, nx, ny, nz, 0u8));
                    }
                }
                if let Some((t, nx, ny, nz)) =
                    sph_hit(ox, oy, oz, dx, dy, dz, S1_X, S1_Y, S1_Z, S1_R)
                {
                    if t < bt {
                        bt = t;
                        nhit = Some((t, nx, ny, nz, 1u8));
                    }
                }
                if let Some((t, nx, ny, nz)) =
                    sph_hit(ox, oy, oz, dx, dy, dz, S2_X, S2_Y, S2_Z, S2_R)
                {
                    if t < bt {
                        bt = t;
                        nhit = Some((t, nx, ny, nz, 2u8));
                    }
                }

                let (t, nx, ny, nz, mat);
                match nhit {
                    Some(h) => {
                        t = h.0;
                        nx = h.1;
                        ny = h.2;
                        nz = h.3;
                        mat = h.4;
                    }
                    None => {
                        // miss -> gradient sky, warmer toward the bottom
                        let f = 0.5 + 0.5 * dy;
                        acc_r += gain * (SKY_TOP_R + (SKY_BOT_R - SKY_TOP_R) * f);
                        acc_g += gain * (SKY_TOP_G + (SKY_BOT_G - SKY_TOP_G) * f);
                        acc_b += gain * (SKY_TOP_B + (SKY_BOT_B - SKY_TOP_B) * f);
                        break;
                    }
                }

                let (cr, cg, cb, refl);
                if mat == 0 {
                    cr = S0_CR;
                    cg = S0_CG;
                    cb = S0_CB;
                    refl = S0_RF;
                } else if mat == 1 {
                    cr = S1_CR;
                    cg = S1_CG;
                    cb = S1_CB;
                    refl = S1_RF;
                } else {
                    cr = S2_CR;
                    cg = S2_CG;
                    cb = S2_CB;
                    refl = S2_RF;
                }

                // hit point
                let (hx, hy, hz) = (ox + dx * t, oy + dy * t, oz + dz * t);

                // diffuse
                let mut dl = dot3(nx, ny, nz, slx, sly, slz);
                if dl < 0.0 {
                    dl = 0.0;
                }

                // Blinn-Phong half vector
                let (vx, vy, vz) = (-dx, -dy, -dz);
                let vn = isqrtf(dot3(vx, vy, vz, vx, vy, vz));
                let (vx, vy, vz) = (vx * vn, vy * vn, vz * vn);
                let (hx2, hy2, hz2) = (vx + slx, vy + sly, vz + slz);
                let hn = isqrtf(dot3(hx2, hy2, hz2, hx2, hy2, hz2));
                let (hx2, hy2, hz2) = (hx2 * hn, hy2 * hn, hz2 * hn);
                let mut spec = dot3(nx, ny, nz, hx2, hy2, hz2);
                if spec < 0.0 {
                    spec = 0.0;
                }
                spec = p16(spec);

                // ambient + diffuse + specular
                let scl = 0.12 + 0.85 * dl;
                acc_r += gain * (cr * scl + 0.90 * spec);
                acc_g += gain * (cg * scl + 0.85 * spec);
                acc_b += gain * (cb * scl + 0.80 * spec);

                // reflect: r = d - 2 (d.n) n
                let nd = dot3(dx, dy, dz, nx, ny, nz);
                let mut rx = dx - 2.0 * nd * nx;
                let mut ry = dy - 2.0 * nd * ny;
                let mut rz = dz - 2.0 * nd * nz;
                let rn = isqrtf(dot3(rx, ry, rz, rx, ry, rz));
                rx *= rn;
                ry *= rn;
                rz *= rn;
                dx = rx;
                dy = ry;
                dz = rz;

                ox = hx + nx * 2e-3;
                oy = hy + ny * 2e-3;
                oz = hz + nz * 2e-3;
                gain *= refl;
                if gain < 0.05 {
                    break;
                }
                depth += 1;
            }

            // write RGBA word
            unsafe {
                core::ptr::write_volatile(FRAME_BASE.add(y * W + x), pack(acc_r, acc_g, acc_b));
            }
            x += 1;
        }
        y += 1;
    }
}