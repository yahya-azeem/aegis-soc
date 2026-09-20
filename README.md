# Aegis SoC

A single-die **RISC-V system-on-chip** written in [Chisel](https://www.chisel-lang.org/), built
around one **unified, self-serving HBM3-class memory stack** shared by a CPU lane, a GPU lane and
a fixed-function accelerator lane. The whole design is sized to elaborate, simulate and be
co-simulated with the **real Vortex GPGPU RTL** on a laptop.

<p align="center">
  <img src="docs/aegis_block_diagram.png" alt="Aegis SoC block diagram" width="900"/>
</p>

<p align="center">
  <img src="docs/aegis_floorplan.png" alt="Aegis SoC conceptual floorplan" width="720"/><br/>
  <em>Conceptual floorplan (illustrative, derived from the RTL blocks — not a placed-and-routed die).</em>
</p>

---

## What it is (and what it deliberately is not)

Aegis federates three very different requesters onto **one banked DRAM model** and arbitrates
between them in hardware:

| Lane | Blocks | What it does |
|------|--------|--------------|
| **CPU** | `RiscVICore` → `CoreMemToHBM` | In-order **RV32I** boot core with a word-level port widened to 512-bit stack lines |
| **GPU** | `GPUL2Cache` → `AXIToMemReq`, `SimtCore` | Two-port GPU front-end driving real single-beat AXI4 transactions, plus a 32-lane SIMT kernel core |
| **Accelerator** | `GemmToMem` *or* `VortexAccelerator` | 8×8 GEMM engine reading operands from shared memory, or the **real Vortex GPGPU RTL** as a BlackBox |
| **Memory** | `SplitPrioritizer` → `HBM3Stack` | QoS arbitration into a banked DRAM array with an open-page controller and refresh |

**Honest scope.** This is a modelling and verification project, not a tape-out:
`RiscVICore` is a compact multi-cycle RV32I core (no pipeline, no MMU, no exceptions); `SimtCore`
is a sequential line-at-a-time element-wise adder rather than a warp-scheduled machine; and the
`HBM3Stack` is a 16 KB banked model, not a 128 GB memory system. Those limits are stated
explicitly so the interesting parts — the shared-memory arbitration, the CPU↔DRAM datapath, and
the real-GPU co-simulation — can be verified end to end. See
[Modelled vs. target parameters](#modelled-vs-target-parameters).

---

## Architecture

![block diagram](docs/aegis_block_diagram.png)

The block set is exactly what `Top` instantiates; it is not aspirational:

```
Top (Aegis)
├── RiscVICore core          RV32I in-order boot core
├── CoreMemToHBM cpuAdp      32-bit word → 512-bit line read-modify-write
├── GPUL2Cache l2            2-port round-robin + single-beat AXI4 FSM
├── SimtCore simt            32 lanes, Y = X + Z over shared memory
├── AXIToMemReq gpuAdp       AXI4 slave → MemReq (WSTRB read-modify-write)
├── GemmToMem gemm           8×8 systolic MAC from shared memory
└── SplitPrioritizer split   CPU/GPU/ACC QoS arbiter
    └── HBM3Stack hbm        4 banks × 8 rows × 8 cols × 512-bit, open-page + refresh
```

When `vortexRtl = true`, `VortexAccelerator` (a `BlackBox` of `VortexShell`, wrapping the
unmodified upstream `Vortex_axi`) replaces `GemmToMem` on the accelerator port. The SoC then
exposes `vx_dcr_*`, `vx_start` and `vx_busy` so a testbench can program and launch the real GPU.

### Unified shared memory

```
RV32I CPU  ── cpu_req/resp ──┐
GPU L2     ── gpu_req/resp ──┼──► SplitPrioritizer ──► HBM3Stack ──► mem_axi (mirror)
GEMM/Vortex── acc_req/resp ──┘          (mode[1:0])
```

- **`HBM3Stack`** owns a real register array (`4 banks × 8 rows × 8 cols` of 512-bit words =
  **16 KB**, 64-byte lines). It decodes address → `{bank,row,column}`, tracks per-bank open rows
  with `tACT`/`tPRE` timing, and runs a periodic refresh walker. Reads are served **from the
  array**, so written data physically persists across a test. `io.mem` is an AXI
  **observability mirror** of the current PHY transaction — completion never depends on it.
- Only `addr[13:6]` is decoded, so the 16 KB model aliases addresses modulo 16 KB; the
  testbench places its code, framebuffer and stack in disjoint low regions.
- **`SplitPrioritizer`** selects one requester per cycle and tags the response so data returns to
  the port that issued it. `mode[1:0]` selects the QoS policy:

  | `mode` | Policy |
  |--------|--------|
  | `0` unified | fair rotating-priority round-robin across CPU / GPU / ACC |
  | `1` gaming | CPU strict priority; GPU and ACC round-robin the rest |
  | `2` ai | CPU strict priority **and** HBM3 open-page (row-buffer) policy |

### CPU lane

`RiscVICore` implements the RV32I base integer subset (LUI/AUIPC/JAL/JALR, all branches,
LB/LH/LW/LBU/LHU, SB/SH/SW, all ALU immediate/register ops), with no M/A/F/D/C extensions, CSRs,
`ecall`/`ebreak`, fences or interrupts. It is a single-instruction multi-cycle machine that
fetches from a 256×32 boot `imem` programmed through `prog_we/prog_addr/prog_data`; the sentinel
instruction `0xFFFFFFFF` halts it. `CoreMemToHBM` widens each 32-bit access to a 512-bit line,
sign-extends loads by size, and performs a **read-modify-write** for every store (the core is
single-outstanding by construction).

### GPU lane

`GPUL2Cache` round-robin arbitrates its two cluster ports (the external `gpu` port and the
on-die `SimtCore`), then runs a real AXI4 FSM (`AW/W/B` for stores, `AR/R` for loads) and returns
memory data to the issuing cluster. `AXIToMemReq` is the AXI4-slave-to-`MemReq` bridge; it does
a **read-for-ownership merge** when `WSTRB` is not all-ones, so partial stores cannot clobber
untouched bytes on the 512-bit bus, and it echoes `BID = AWID` / `RID = ARID`. `SimtCore`
executes an element-wise `Y = X + Z` kernel over arrays in the shared HBM3, one line per step,
and raises `simt_done`.

### Accelerator lane

`GemmToMem` reads an `A` tile (`T×T`, 16-bit) at `base`, a `B` tile at `base + T*T*2` and writes
a `C` tile (`T×T`, 32-bit) at `base + T*T*4`, computing a real matrix product from shared
memory. With `config.vortexRtl = true`, the **real Vortex GPGPU RTL** takes this port instead.

---

## Real Vortex co-simulation and the raytracer proof

`vortex/` is a vendored copy of upstream **Vortex 3.0** (Apache-2.0) — the author's glue is
`src/main/scala/aegis/gpu/VortexBlackBox.scala`, `test/vortex/VortexShell.sv` (a flat-pin
wrapper) and the testbenches. The emitted SoC and the real RTL are co-simulated **out of tree**
with Verilator in three escalating scopes:

1. **standalone** — compile `VortexShell` + real RTL and drive the DCR/start/busy pins;
2. **co-elaboration** — lint the emitted `Aegis.sv` together with the real Vortex RTL;
3. **end-to-end** — build and run `Aegis` + real Vortex, program a kernel through the SoC's own
   `vx_dcr_*`/`vx_start` pins, and observe `vx_busy` plus HBM3 mirror traffic.

The headline workload is a **bare-metal RV32IMF raytracer kernel** (`test/vortex/rt_balls.rs`,
`#![no_std]`, custom Vortex entry/exit asm, fast inverse-sqrt) that renders three reflective
spheres with a sky gradient into the shared HBM3. `test/vortex/golden.py` is a double-precision
host render of the *same scene*; the RTL testbench compares every framebuffer word against it
with a per-channel tolerance of ±4.

<table>
<tr>
<td align="center"><img src="docs/raytrace.png" width="380"/><br/>
<em>Host reference render (600×600) of the scene the Vortex kernel executes.</em></td>
<td align="center"><img src="docs/raytrace_40x40.png" width="380"/><br/>
<em>Native kernel framebuffer (40×40, nearest-upscaled) — <code>rt_balls_golden.bin</code>.</em></td>
</tr>
</table>

```bash
make raytrace     # render docs/raytrace.png from the kernel's scene
```

**Result — the co-simulation was run and passes.** Verilator co-simulated the emitted SoC with
the real Vortex RTL: the kernel ran from shared HBM3, the RV32I CPU loop read the same memory
back, and the read-back framebuffer matched the golden within ±4 per channel —
**1600 / 1600 pixels within tolerance, 1595 exact, mean channel error 0.0013** (`vx_busy`,
AXI read/write, magic store and shared-memory store all observed). The raw evidence is in
[`docs/cosim_result.txt`](docs/cosim_result.txt). The image below is that **RTL-produced**
framebuffer, dumped by the testbench (`AEGIS_VX_RT_DUMP`):

<table>
<tr>
<td align="center"><img src="docs/raytrace_rtl.png" width="380"/><br/>
<em>Framebuffer written by the real Vortex RTL inside the SoC (40×40, upscaled).</em></td>
</tr>
</table>

---

## Verification

All tests are ChiselSim (Verilator-backed) ScalaTests, plus structural elaboration tests.
Current status: **13 suites, 27 tests, all passing** (`make test`).

| Suite | Covers |
|-------|--------|
| `SimulationSmokeTest` | simulator sanity |
| `RiscVICoreTest` | arithmetic/logic, internal load/store, branches, JAL/JALR |
| `RiscVICoreMemTest` | core → `CoreMemToHBM` → splitter → HBM3 round-trips |
| `HBM3StackTest` | write persistence, open-page keep/close policy |
| `SplitPrioritizerTest` | CPU/GPU routing, gaming CPU priority, AI open-page, shared data integrity |
| `AXIToMemReqTest` | full-line AXI store + load through HBM3 |
| `GPUL2CacheMemTest` / `GPUL2CacheTest` | cluster round-robin and shared-memory round-trip |
| `SimtCoreTest` | `Y = X + Z` kernel through the real L2/AXI path |
| `GemmSharedAccTest` | 8×8 GEMM on shared memory vs. a software reference |
| `SoCCpuMemTest` | CPU writes / GPU reads the same HBM3 line and vice versa |
| `TopSmokeTest` | full `Top`: CPU boot, GPU↔CPU sharing, GEMM and SIMT inside the SoC |
| `VortexBlackBoxEmitTest` | real-Vortex BlackBox present/absent in emitted SV |

A raw-Verilator smoke harness (`test/Makefile.test`, `test/test_bench.cpp`) additionally compiles
the emitted `Aegis.sv` and runs 1010 ticks.

---

## Gate-level views (Yosys)

`make verilog-yosys` emits a Yosys-friendly netlist (firtool `disallowLocalVariables` +
`disallowPackedArrays`) which is synthesized with **Yosys 0.66**.

<p align="center">
  <img src="docs/aegis_yosys_cells.png" alt="Yosys cell counts" width="820"/>
</p>

- `docs/aegis_yosys_cells.png` — **15,181 cells across 8 modules** (proc/memory level). `GemmToMem`
  dominates at 11,735 cells (its register tile), then `RiscVICore` 1,608 and `HBM3Stack` 1,414.
- `docs/aegis_yosys_stat.txt` — full Yosys `stat`, including per-cell-type breakdowns.
- `docs/schematic_AXIToMemReq.png`, `docs/schematic_CoreMemToHBM.png` — Yosys `show` netlist
  schematics.
- `docs/aegis_gate_CoreMemToHBM.v` — gate-level netlist after `synth` + `abc`.

```bash
make verilog-yosys
yosys -p "read_verilog -sv build/rtl-yosys/Aegis.sv; hierarchy -top Aegis; proc; opt; memory -nomap; opt_clean; stat -top Aegis"
yosys -p "read_verilog -sv build/rtl-yosys/Aegis.sv; hierarchy -top CoreMemToHBM; proc; opt; show -format png -prefix sch_core CoreMemToHBM"
```

> Yosys's generic flow stops at gate primitives. **Transistor-level (CMOS) schematics** need a
> standard-cell/PDK library and a place-and-route flow (e.g. OpenLane/OpenROAD), which is out of
> scope here; the gate netlist is the deepest view available without a PDK.

### Transistor-level (CMOS) schematic

To go below gates we map the synthesized netlist to real `nmos`/`pmos` transistor networks with
`techlib/cmos.v` (combinational cells are exact CMOS networks; registers are the 14-transistor
transmission-gate master-slave DFF), then render with `netlistsvg` and the MOSFET skin
`scripts/cmos_skin.svg`:

<p align="center">
  <img src="docs/transistors_cells.png" alt="CMOS standard-cell library at transistor level" width="900"/>
</p>

`make transistors` regenerates the cell-library figure above, the per-block transistor counts
(`docs/aegis_transistor_counts.txt`) and a transistor-level SPICE netlist
(`docs/aegis_arbiter_transistors.spice`). Structural transistor counts:

| Block | Transistors |
|-------|------------:|
| SplitPrioritizer (arbiter) | 12,870 |
| CoreMemToHBM | 43,192 |
| AXIToMemReq | 18,518 |
| GPUL2Cache | 32,126 |
| SimtCore | 67,948 |
| GemmToMem | 204,702 |
| RiscVICore | 365,008 |
| HBM3Stack | 4,723,348 |
| **Total** | **5,467,712** |

`HBM3Stack` dominates because it is the 131,072-bit register file plus its read/decode logic. A
**full-module** transistor graph is far too dense to render legibly, so the transistor deliverable
is the cell library figure, the per-block counts and the SPICE netlist rather than one giant
schematic; a physical transistor *layout* would additionally require a PDK and an OpenLane/OpenROAD
flow.

---

## Build and run

**Prerequisites:** JDK 17+, `sbt`, `verilator`, Python 3. `make` targets:

```bash
make compile         # compile Scala/Chisel
make verilog         # elaborate Top -> build/rtl/Aegis.sv (+ verification tree)
make verilog-vortex  # same SoC with the real Vortex RTL on the acc port
make verilog-yosys   # Yosys-friendly emission for gate-level analysis
make test            # ScalaTest suite
make verilator       # raw-Verilator smoke harness
make raytrace        # render the raytracer reference scene to docs/raytrace.png
make clean
```

Elaborate and simulate with the helper script too:

```bash
python3 scripts/simulate.py --verilog      # build/rtl/
python3 scripts/simulate.py --simulate     # raw-Verilator harness
```

> **Path caveat.** Verilator cannot build in a directory whose path contains spaces. `make test`
> detects this and transparently mirrors the checkout to a space-free temp dir; for the raw
> Verilator and Vortex co-sim flows, clone into a space-free path.

The out-of-tree Vortex co-sim needs a RISC-V toolchain and the vendored `vortex/` RTL:

```bash
make verilog-vortex
test/vortex/vortex_smoke.sh          # standalone + co-elab + end-to-end
test/vortex/rt_cosim.sh              # raytracer kernel vs. golden
```

---

## Modelled vs. target parameters

The project deliberately models small, *verifiable* numbers rather than hyperscale ones. This
table is the single source of truth so documentation and RTL cannot drift apart:

| Subsystem | Modelled here (verified) | Design target (illustrative) |
|-----------|--------------------------|------------------------------|
| Memory | 16 KB banked HBM3 model, 512-bit, 4 banks × 8 rows × 8 cols | 256 MB unified LPDDR5-class @ 3.2 Gbps |
| CPU | 1× RV32I in-order multi-cycle boot core, 512-bit shared path | 4 cores @ 1–3 GHz, L1/L2/L3 hierarchy |
| GPU | 2-port L2 front-end + 32-lane SIMT core; optional real Vortex 3.0 RTL | 8 clusters / 64 SIMT cores @ 1.5 GHz |
| Accelerator | 8×8 GEMM from shared memory | larger GEMM + ray-tracing / AI-upscale blocks |
| AXI | 64-bit addr, 512-bit data, 8-bit IDs | — |

`AegisConfig` contains only the parameters that are actually instantiated
(`socName`, `axiAddrWidth`, `axiDataWidth`, `vortexRtl`); every block's geometry lives in its own
module.

---

## Repository layout

```
src/main/scala/aegis/
├── Top.scala                 SoC top: CPU + GPU + acc sharing SplitPrioritizer
├── package.scala             AegisConfig
├── types.scala               AXI4 / MemReq / MemInterface bundles
├── cpu/                      RiscVCore, CoreMemToHBM
├── gpu/                      GPUL2Cache, SimtCore, VortexBlackBox (VortexAccelerator)
├── fixedfunc/                GemmToMem
├── memory/                   HBM3Stack, SplitPrioritizer
└── elaborate/                CIRCT emit helpers (TopElaborate, TopVortexElaborate)

techlib/                      cmos.v (gate -> nmos/pmos transistor mapping)
src/test/scala/aegis/         ChiselSim + emit suites
test/                         raw-Verilator harness
test/vortex/                  real-Vortex co-sim, raytracer kernel + golden renderer
vortex/                       vendored upstream Vortex 3.0 RTL (Apache-2.0)
docs/                         block diagram, floorplan, raytracer renders, Yosys views,
                              transistor-level cell library + counts
scripts/                      simulate.py, render_floorplan.py, render_yosys_summary.py,
                              render_transistors.py, transistor_flow.sh, cmos_skin.svg
```

---

## Engineering highlights

- **A self-serving DRAM model in synthesizable Chisel** — real banked array, address decode,
  open-page activate/precharge timing, refresh, and register-file persistence (not a testbench
  echo).
- **Unified-memory arbitration with QoS modes** — one stack, three requesters, tagged responses,
  fair or CPU-prioritised scheduling, and an open-page mode for throughput.
- **A correct 32→512-bit memory path** — byte/half/word load sign-extension and store
  read-modify-write, proven end to end against the DRAM array from a booted RV32I program.
- **The AXI `WSTRB` read-modify-write fix** and response-ID echo — the subtle correctness
  details that unblocked real GPU cache-line fills.
- **Black-boxing unmodified upstream Vortex** with a thin flat-pin wrapper instead of forking it,
  and co-simulating it out of tree in three escalating scopes.
- **A bare-metal Rust raytracer as a hardware test vector** — `no_std`, custom entry/exit asm,
  fast inverse-sqrt, verified pixel-by-pixel against a double-precision Python golden model.

## References & licenses

| Component | License | URL |
|-----------|---------|-----|
| Chisel / FIRRTL / CIRCT | Apache-2.0 | https://github.com/chipsalliance/chisel |
| Vortex (vendored in `vortex/`) | Apache-2.0 | https://github.com/vortexgpgpu/vortex |
| Verilator | LGPL-3.0 / Artistic-2.0 | https://github.com/verilator/verilator |

The `vortex/` tree is a vendored copy of upstream Vortex 3.0 retained for the out-of-tree
co-simulation; the author's original work is the Chisel SoC plus the glue/testbench files listed
above.
