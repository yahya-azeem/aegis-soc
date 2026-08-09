#!/usr/bin/env bash
# VortexShell co-sim smoke test against the real Vortex RTL sources.
#
# Flow:
#   1. generate config headers (unresolved VX_config.vh + resolved VX_types.vh)
#   2. compile the real Vortex RTL + VortexShell.sv with Verilator
#   3. run the C++ testbench, which asserts reset/busy/start behavior
#
# All transient artifacts go under ../build/vortex-smoke/.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VX="${REPO_ROOT}/vortex"
OUT="${REPO_ROOT}/build/vortex-smoke"
CONFIG="${VX}/VX_config.toml"
GEN="${VX}/ci/gen_config.py"
XTYPES="${VX}/VX_types.toml"

mkdir -p "${OUT}"

echo "== generating config headers =="
export XLEN=32
python3 "${GEN}" --config "${CONFIG}" --format verilog --output "${OUT}/VX_config.vh"
python3 "${GEN}" --config "${VX}/VX_types.toml" --format verilog --resolved --output "${OUT}/VX_types.vh"

echo "== resolving cflags =="
CFLAGS=$(export XLEN=32; python3 "${GEN}" --config "${CONFIG}" --cflags="-DVX_CFG_XLEN=32 -DVX_CFG_XLEN_32")
echo "${CFLAGS}" > "${OUT}/cflags.txt"
DEVFLAGS="-DVX_CFG_XLEN=32 -DVX_CFG_XLEN_32"
echo "using minimal defines only (full cflags break const-folding)"

echo "== gathering RTL sources =="
PKGS="${VX}/hw/rtl/VX_gpu_pkg.sv ${VX}/hw/rtl/fpu/VX_fpu_pkg.sv ${VX}/hw/rtl/VX_trace_pkg.sv"
REST=$(find "${VX}/hw/rtl/interfaces" "${VX}/hw/rtl/libs" "${VX}/hw/rtl/core" "${VX}/hw/rtl/mem" "${VX}/hw/rtl/cache" "${VX}/hw/rtl/fpu" "${VX}/hw/rtl" -maxdepth 1 -type f \( -name '*.v' -o -name '*.sv' \) -print | grep -vE "VX_gpu_pkg.sv|VX_fpu_pkg.sv|VX_trace_pkg.sv" | sort -u)
echo "${PKGS}" > "${OUT}/srcs.txt"
echo "${REST}" >> "${OUT}/srcs.txt"

echo "== verilator build =="
verilator --cc --top-module VortexShell \
    --language 1800-2012 --assert -Wno-fatal -Wno-DECLFILENAME -Wno-REDEFMACRO \
    --x-initial unique --x-assign unique \
    --Mdir "${OUT}/obj_dir" \
    ${DEVFLAGS} \
    -I"${OUT}" \
    -I"${VX}/hw/rtl" -I"${VX}/hw/rtl/libs" -I"${VX}/hw/rtl/interfaces" -I"${VX}/hw/rtl/core" -I"${VX}/hw/rtl/mem" -I"${VX}/hw/rtl/cache" -I"${VX}/hw/rtl/fpu" \
    $(cat "${OUT}/srcs.txt") \
    "${REPO_ROOT}/test/vortex/VortexShell.sv" \
    --exe "${REPO_ROOT}/test/vortex/vortex_testbench.cpp" 2>&1 | tail -8

echo "== make =="
make -C "${OUT}/obj_dir" -f "VVortexShell.mk" 2>&1 | tail -8

echo "== run: standalone VortexShell smoke =="
"${OUT}/obj_dir/VVortexShell"

# Optional: verify the Chisel-emitted SoC (vortexRtl=true) co-elaborates with
# the real Vortex RTL. Run `make verilog-vortex` first to populate emit/.
if [ -f "${OUT}/emit/Aegis.sv" ]; then
  echo "== scope: Aegis(top) + real Vortex RTL co-elaboration =="
  EMIT="${OUT}/emit"
  verilator --lint-only --cc --top-module Aegis \
      --language 1800-2012 --assert -Wno-fatal -Wno-DECLFILENAME -Wno-REDEFMACRO \
      --x-initial unique --x-assign fast \
      -DVX_CFG_XLEN=32 -DVX_CFG_XLEN_32 -DVX_CFG_FLEN=32 \
      -I"${OUT}" -I"${EMIT}" -I"${EMIT}/verification" \
      -I"${EMIT}/verification/assert" -I"${EMIT}/verification/assume" -I"${EMIT}/verification/cover" \
      -I"${VX}/hw/rtl" -I"${VX}/hw/rtl/libs" -I"${VX}/hw/rtl/interfaces" -I"${VX}/hw/rtl/core" \
      -I"${VX}/hw/rtl/mem" -I"${VX}/hw/rtl/cache" -I"${VX}/hw/rtl/fpu" \
      $(cat "${OUT}/srcs.txt") \
      "${REPO_ROOT}/test/vortex/VortexShell.sv" \
      "${EMIT}/Aegis.sv" \
      "${EMIT}/verification/layers-Aegis-Verification.sv" \
      "${EMIT}/verification/assert/layers-Aegis-Verification-Assert.sv" \
      "${EMIT}/verification/assume/layers-Aegis-Verification-Assume.sv" \
      "${EMIT}/verification/cover/layers-Aegis-Verification-Cover.sv" 2>&1 | tail -3
  echo "Aegis + Vortex co-elaboration done"
fi