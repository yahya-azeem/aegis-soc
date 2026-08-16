#!/usr/bin/env bash
# Build a reduced raytracer kernel, seed the host-side golden, recompile the
# end-to-end Aegis+Vortex Verilator model (testbench changed) and run the
# raytracer phase against the real Vortex RTL.
#
# The kernel and golden support any RT_W/RT_H; defaults match the committed
# 40x40 binary.

set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"

RT_W="${RT_W:-40}"
RT_H="${RT_H:-40}"

echo "== kernel + golden @ ${RT_W}x${RT_H} =="
RT_W="$RT_W" RT_H="$RT_H" bash test/vortex/build_rt.sh
RT_W="$RT_W" RT_H="$RT_H" python3 test/vortex/golden.py

echo "== rebuild Aegis+Vortex co-sim exe =="
make -C build/vortex-smoke/obj_dir-soc -j2 -f VAegis.mk 2>&1 | tail -4

echo "== run end-to-end with raytracer phase =="
AEGIS_VX_RAYTRACE=1 \
AEGIS_VX_RT_W="$RT_W" AEGIS_VX_RT_H="$RT_H" \
AEGIS_VX_RT_BIN="/home/yahya/Projects/aegis-soc/test/vortex/rt_balls.bin" \
AEGIS_VX_RT_GOLDEN="/home/yahya/Projects/aegis-soc/test/vortex/rt_balls_golden.bin" \
AEGIS_VX_RT_TIMEOUT=100000000 \
build/vortex-smoke/obj_dir-soc/VAegis