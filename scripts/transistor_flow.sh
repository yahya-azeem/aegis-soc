#!/usr/bin/env bash
# Transistor-level view of the Aegis SoC.
#
#   1. render the CMOS standard-cell library (docs/transistors_cells.png)
#   2. map each block to nmos/pmos and record transistor counts
#   3. write a transistor-level SPICE netlist for the arbiter block
#
# Requires: yosys, `npx netlistsvg`, rsvg-convert, python3 + Pillow.
# Run from a space-free path (Yosys command parsing).
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

echo "== emit Yosys-friendly RTL =="
make verilog-yosys >/dev/null

echo "== render CMOS standard-cell library =="
python3 scripts/render_transistors.py

echo "== per-block transistor counts =="
: > docs/aegis_transistor_counts.txt
{
  echo "Aegis SoC - structural CMOS transistor counts (Yosys 0.66 + techlib/cmos.v)"
  echo
  printf "%-20s %12s %12s %12s\n" "block" "nmos" "pmos" "total"
} >> docs/aegis_transistor_counts.txt

total=0
for spec in \
  "SplitPrioritizer|blackbox HBM3Stack" \
  "CoreMemToHBM|" "AXIToMemReq|" "GPUL2Cache|" "SimtCore|" \
  "GemmToMem|" "RiscVICore|" "HBM3Stack|"
do
  mod="${spec%%|*}"; pre="${spec##*|}"
  out=$(yosys -p "read_verilog -sv build/rtl-yosys/Aegis.sv; hierarchy -top $mod; $pre; \
      synth -top $mod; abc -g AND,OR,NAND,NOR,XOR,XNOR,MUX,ANDNOT,ORNOT; \
      techmap -map techlib/cmos.v; opt_clean; stat -top $mod" 2>/dev/null)
  nm=$(echo "$out" | grep -oE '[0-9]+ +nmos' | tail -1 | awk '{print $1}')
  pm=$(echo "$out" | grep -oE '[0-9]+ +pmos' | tail -1 | awk '{print $1}')
  [ -z "$nm" ] && nm=0; [ -z "$pm" ] && pm=0
  total=$(( total + nm + pm ))
  printf "%-20s %12s %12s %12s\n" "$mod" "$nm" "$pm" "$((nm+pm))" >> docs/aegis_transistor_counts.txt
done
printf "%-20s %12s %12s %12s\n" "TOTAL" "-" "-" "$total" >> docs/aegis_transistor_counts.txt

echo "== SPICE netlist for the arbiter (HBM3Stack black-boxed) =="
yosys -p "read_verilog -sv build/rtl-yosys/Aegis.sv; hierarchy -top SplitPrioritizer; \
  blackbox HBM3Stack; synth -top SplitPrioritizer; \
  abc -g AND,OR,NAND,NOR,XOR,XNOR,MUX,ANDNOT,ORNOT; techmap -map techlib/cmos.v; \
  opt_clean; write_spice docs/aegis_arbiter_transistors.spice" >/dev/null

echo "done:"
ls -la docs/transistors_cells.png docs/aegis_transistor_counts.txt docs/aegis_arbiter_transistors.spice
