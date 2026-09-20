#!/usr/bin/env bash
# One-command live demo: run the Aegis SoC end-to-end with the real Vortex GPGPU
# RTL and render the raytracer kernel, then turn the RTL framebuffer into a PNG.
#
#   scripts/demo_raytrace.sh [--open] [W H]
#
# Verilator cannot build in a path containing spaces, so if this checkout lives
# in one the script mirrors itself to $AEGIS_DEMO_DIR (default ~/aegis-demo) and
# runs there. The Verilator build is cached, so the first run is the slow one.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- mirror to a space-free work dir when needed (keeps the build cache) ---
if [[ "$REPO" == *" "* ]]; then
    WORK="${AEGIS_DEMO_DIR:-$HOME/aegis-demo}"
    echo "[demo] checkout path contains spaces; mirroring to $WORK"
    mkdir -p "$WORK"
    rsync -a --exclude build --exclude target --exclude project/target --exclude .git \
        "$REPO/" "$WORK/"
    exec bash "$WORK/scripts/demo_raytrace.sh" "$@"
fi

OPEN=0
ARGS=()
for a in "$@"; do
    case "$a" in
        --open) OPEN=1 ;;
        *) ARGS+=("$a") ;;
    esac
done
W="${ARGS[0]:-${RT_W:-40}}"
H="${ARGS[1]:-${RT_H:-40}}"

cd "$REPO"
mkdir -p docs

echo "[demo] 1/4  elaborate the SoC with the real Vortex RTL on the acc port"
make verilog-vortex >/dev/null

echo "[demo] 2/4  build + smoke the Aegis<->Vortex co-simulation"
echo "            (first run compiles ~230 RTL modules with Verilator; later runs reuse the cache)"
bash test/vortex/vortex_smoke.sh

echo "[demo] 3/4  run the raytracer kernel on the real GPU through shared HBM3"
AEGIS_VX_RAYTRACE=1 \
AEGIS_VX_RT_W="$W" AEGIS_VX_RT_H="$H" \
AEGIS_VX_RT_BIN="$REPO/test/vortex/rt_balls.bin" \
AEGIS_VX_RT_GOLDEN="$REPO/test/vortex/rt_balls_golden.bin" \
AEGIS_VX_RT_DUMP="$REPO/build/rtl_framebuffer.bin" \
AEGIS_VX_RT_TIMEOUT=100000000 \
build/vortex-smoke/obj_dir-soc/VAegis

echo "[demo] 4/4  convert the RTL framebuffer to a PNG"
python3 test/vortex/fb_to_png.py build/rtl_framebuffer.bin docs/raytrace_rtl_live.png 15 "$W" "$H"

PNG="$REPO/docs/raytrace_rtl_live.png"
echo "[demo] done -> $PNG"
if [ "$OPEN" = "1" ]; then
    xdg-open "$PNG" >/dev/null 2>&1 || true
fi
