#!/usr/bin/env bash
# Full demo: build once (if needed) then run the raytracer on the real GPU.
#
#   scripts/demo_raytrace.sh [--open]
#
# For the interview prefer the run-only path:
#   scripts/build_demo.sh   # do this once, beforehand
#   scripts/run_raytrace.sh --open
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$REPO/build/vortex-smoke/obj_dir-soc/VAegis"

if [ ! -x "$BIN" ]; then
    bash "$REPO/scripts/build_demo.sh"
fi
exec bash "$REPO/scripts/run_raytrace.sh" "$@"
