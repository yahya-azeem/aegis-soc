#!/usr/bin/env bash
# Run the PRE-BUILT Aegis<->Vortex raytracer on the real GPU. No compilation.
#
#   scripts/run_raytrace.sh [--scene chess|balls] [--open] [--live] [--video]
#
#   (default)   run, then write docs/raytrace_<scene>_rtl_live.png
#   --live      also stream frames to build/live and open the live viewer window
#   --video     assemble the captured frames into docs/raytrace_<scene>_rtl.mp4
#   --open      open the final PNG when done
#
# This never invokes make / verilator / sbt -- it only executes the already
# built binary, so it is safe to run live in a demo.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$REPO/build/vortex-smoke/obj_dir-soc/VAegis"
LIVE_DIR="$REPO/build/live"

SCENE=chess
OPEN=0; LIVE=0; VIDEO=0; SCALE=16; TOTAL=7500000
ARGS=("$@")
for ((i=0; i<${#ARGS[@]}; i++)); do
    case "${ARGS[$i]}" in
        --scene) SCENE="${ARGS[$((i+1))]}"; i=$((i+1)) ;;
        --open)  OPEN=1 ;;
        --live)  LIVE=1 ;;
        --video) VIDEO=1; LIVE=1 ;;
        --scale) SCALE="${ARGS[$((i+1))]}"; i=$((i+1)) ;;
        --total) TOTAL="${ARGS[$((i+1))]}"; i=$((i+1)) ;;
    esac
done

case "$SCENE" in
    chess) SCENE_BIN="$REPO/test/vortex/rt_chess.bin";        SCENE_GOLD="$REPO/test/vortex/rt_chess_golden.bin"; W=40; H=40; TOTAL="${TOTAL:-12000000}" ;;
    balls) SCENE_BIN="$REPO/test/vortex/rt_balls.bin";        SCENE_GOLD="$REPO/test/vortex/rt_balls_golden.bin"; W=40; H=40 ;;
    *) echo "unknown scene: $SCENE (use chess|balls)"; exit 1 ;;
esac
OUT_PNG="$REPO/docs/raytrace_${SCENE}_rtl_live.png"
OUT_MP4="$REPO/docs/raytrace_${SCENE}_rtl.mp4"
HERO="$REPO/docs/raytrace_chess.png"

if [ ! -x "$BIN" ]; then
    echo "error: pre-built binary not found: $BIN"
    echo "build it once with:  make demo-build   (or scripts/build_demo.sh)"
    exit 1
fi
if [ ! -f "$SCENE_BIN" ] || [ ! -f "$SCENE_GOLD" ]; then
    echo "error: scene binaries not found ($SCENE_BIN / $SCENE_GOLD)"
    exit 1
fi

mkdir -p "$REPO/docs"

VIEWER_PID=""
if [ "$LIVE" = 1 ]; then
    rm -rf "$LIVE_DIR"; mkdir -p "$LIVE_DIR/frames"
    echo "[run] live viewer: build/live  (scene=$SCENE, scale ${SCALE}x)"
    python3 "$REPO/scripts/live_view.py" --dir "$LIVE_DIR" --scale "$SCALE" --total "$TOTAL" \
        --hero "$HERO" >/dev/null 2>&1 &
    VIEWER_PID=$!
fi

echo "[run] executing pre-built co-simulation binary (scene=$SCENE, no build)"
run_emu() {
    AEGIS_VX_RAYTRACE=1 AEGIS_VX_RT_W="$W" AEGIS_VX_RT_H="$H" \
    AEGIS_VX_RT_BIN="$SCENE_BIN" \
    AEGIS_VX_RT_GOLDEN="$SCENE_GOLD" \
    AEGIS_VX_RT_DUMP="$REPO/build/rtl_framebuffer.bin" \
    AEGIS_VX_RT_TIMEOUT=100000000 \
    "$BIN"
}
set +e
if [ "$LIVE" = 1 ]; then
    AEGIS_VX_LIVE_DIR="$LIVE_DIR" AEGIS_VX_LIVE_EVERY="${AEGIS_VX_LIVE_EVERY:-100000}" run_emu
else
    run_emu
fi
RC=$?
set -e

python3 "$REPO/test/vortex/fb_to_png.py" \
    "$REPO/build/rtl_framebuffer.bin" "$OUT_PNG" 15 "$W" "$H"

if [ "$VIDEO" = 1 ] && command -v ffmpeg >/dev/null; then
    echo "[run] assembling $OUT_MP4 from the captured frames"
    ffmpeg -y -loglevel error -framerate 12 -start_number 0 \
        -i "$LIVE_DIR/frames/frame_%05d.ppm" \
        -vf "scale=640:640:flags=neighbor" -pix_fmt yuv420p \
        "$OUT_MP4" || echo "[run] ffmpeg failed (frames may be missing)"
fi

echo "[run] done -> $OUT_PNG"
[ -n "$VIEWER_PID" ] && echo "[run] live viewer still open (close it when done)"
if [ "$OPEN" = 1 ]; then
    xdg-open "$OUT_PNG" >/dev/null 2>&1 || true
fi
exit $RC
