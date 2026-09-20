#!/usr/bin/env bash
# Run the PRE-BUILT Aegis<->Vortex raytracer on the real GPU. No compilation.
#
#   scripts/run_raytrace.sh [--open]
#
# This never invokes make / verilator / sbt -- it only executes the already
# built binary, so it is safe to run live in an interview.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$REPO/build/vortex-smoke/obj_dir-soc/VAegis"
OPEN=0
[ "${1:-}" = "--open" ] && OPEN=1

if [ ! -x "$BIN" ]; then
    echo "error: pre-built binary not found: $BIN"
    echo "build it once with:  make demo-build   (or scripts/build_demo.sh)"
    exit 1
fi

W="${RT_W:-40}"
H="${RT_H:-40}"

echo "[run] executing pre-built co-simulation binary (no build)"
AEGIS_VX_RAYTRACE=1 \
AEGIS_VX_RT_W="$W" \
AEGIS_VX_RT_H="$H" \
AEGIS_VX_RT_BIN="$REPO/test/vortex/rt_balls.bin" \
AEGIS_VX_RT_GOLDEN="$REPO/test/vortex/rt_balls_golden.bin" \
AEGIS_VX_RT_DUMP="$REPO/build/rtl_framebuffer.bin" \
AEGIS_VX_RT_TIMEOUT=100000000 \
"$BIN"

python3 "$REPO/test/vortex/fb_to_png.py" \
    "$REPO/build/rtl_framebuffer.bin" "$REPO/docs/raytrace_rtl_live.png" 15 "$W" "$H"

echo "[run] done -> $REPO/docs/raytrace_rtl_live.png"
if [ "$OPEN" = 1 ]; then
    xdg-open "$REPO/docs/raytrace_rtl_live.png" >/dev/null 2>&1 || true
fi
