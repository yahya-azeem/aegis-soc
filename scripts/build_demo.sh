#!/usr/bin/env bash
# Build the Aegis<->Vortex co-simulation once (the slow, one-time step).
#
#   scripts/build_demo.sh
#
# Verilator cannot build in a path containing spaces, so if this checkout lives
# in one we mirror it to $AEGIS_DEMO_DIR (default ~/aegis-demo) and build there.
# After this completes, use scripts/run_raytrace.sh to run the demo.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "$REPO" == *" "* ]]; then
    WORK="${AEGIS_DEMO_DIR:-$HOME/aegis-demo}"
    echo "[build] path contains spaces; mirroring to $WORK"
    mkdir -p "$WORK"
    rsync -a --exclude build --exclude target --exclude project/target --exclude .git \
        "$REPO/" "$WORK/"
    exec bash "$WORK/scripts/build_demo.sh" "$@"
fi

cd "$REPO"
echo "[build] 1/2  elaborate the SoC with the real Vortex RTL on the acc port"
make verilog-vortex >/dev/null
echo "[build] 2/2  compile + smoke the co-simulation (Verilator, ~230 modules)"
echo "            this is the slow step; it only needs to run once"
bash test/vortex/vortex_smoke.sh
echo "[build] done. Binaries:"
echo "        build/vortex-smoke/obj_dir-soc/VAegis   (SoC + Vortex)"
echo "        build/vortex-smoke/obj_dir/VVortexShell (standalone Vortex)"
echo "[build] run the raytrace with: scripts/run_raytrace.sh --open"
