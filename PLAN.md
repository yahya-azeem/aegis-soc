# Aegis SoC — RISC-V SoC in Chisel — Implementation Plan

A single-die SoC written in Chisel that federates a RISC-V CPU core, a Vortex-style
GPGPU, and fixed-function blocks behind one **unified shared memory** model —
sized and shaped to be elaborable, simulatable, and verifiable on a laptop.

The project deliberately targets a **small, realistic footprint** (256MB unified
LPDDR5-class memory, RV32I grade CPU) rather than hyperscale numbers, so the whole
design can be co-simulated end-to-end by Verilator on this machine.

---

## 1. Design goals (what actually holds today)

- **Real, self-contained memory.** `HBM3Stack` owns a banked DRAM array with an
  open-page controller (activate/precharge/refresh). Reads and writes are served
  *from the array itself*, not echoed from a testbench. The AXI `mem` port is an
  observability mirror of the current PHY transaction.
- **Shared memory, one stack.** CPU lane, GPU lane, and an accelerator lane all
  attach to the same stack through `SplitPrioritizer`, which arbitrates CPU/GPU/ACC
  with a QoS `mode` (unified vs. low-latency CPU bias).
- **Bootable top.** `Top` elaborates to `Aegis` and emits SystemVerilog whose
  verification-layer files are genuinely resolvable by Verilator.
- **Real external RTL.** The actual Vortex GPGPU RTL is BlackBox'd onto the SoC's
  accelerator lane and co-simulated out-of-tree with Verilator (see §5).

### Honest scope guardrails

- The CPU is an **in-order RV32I boot core** (`RiscVICore`). Multi-core complex is a
  structurally-simple `NutShellWrapper` file used by unit tests. There is no
  out-of-order QEMU-class CPU, and none is planned.
- GPU is a small SIMT core (`SimtCore`) plus, optionally, the **real Vortex RTL** as
  a black box. Not an entire software driver stack.
- Memory is **256MB unified LPDDR5-class at 3.2 Gbps**, not a 128GB HBM3 pool. The
  numbers in `MemoryConfig` describe the model, which is simulatable here.

---

## 2. Repository layout

```
src/main/scala/aegis/
├── Top.scala                 # SoC top: CPU+GPU+fixed-func sharing SplitPrioritizer
├── package.scala             # AegisConfig + per-domain config case classes
├── types.scala               # shared bundles (AXI, MemReq, MemPort, ...)
├── cpu/
│   ├── RiscVCore.scala       # RV32I in-order boot core + CoreMemToHBM adapter
│   └── NutShellWrapper.scala # multi-core complex scaffold (NutShellCore/L2/L3/crossbar)
├── gpu/
│   ├── SimtCore.scala        # on-die SIMT vector kernel core
│   ├── VortexWrapper.scala   # GPU L2 cache + AXI adapter into the stack
│   └── VortexBlackBox.scala  # Chisel BlackBox of real Vortex_axi RTL
├── memory/
│   ├── HBM3Stack.scala       # self-serving banked DRAM + open-page controller
│   └── SplitPrioritizer.scala# CPU/GPU/ACC arbitration into the stack
├── bridge/                   # AXIToMemReq, AXITileLinkBridge
├── fixedfunc/                # GemmToMem, OpenGeMM/RayFlex wrappers
└── interconnect/SoCFabric.scala

src/test/scala/aegis/         # 25 ChiselSim + emit test suites
test/vortex/                  # out-of-tree Verilator co-sim of the real Vortex RTL
build/                        # generated: rtl/, vortex-smoke/
```

---

## 3. CPU complex: RV32I boot core (NutShell in-order shaping)

### 3.1 What exists

| Component | File | Role |
|-----------|------|------|
| RV32I core | `cpu/RiscVCore.scala` | In-order RISC-V core with a word-level external memory port; boots from a small program image |
| CPU→HBM | `cpu/CoreMemToHBM.scala` | 32-bit word requests widened to 512-bit stack lines |
| Multi-core scaffold | `cpu/NutShellWrapper.scala` | `NutShellCore` → `L2CacheBank` → `CoreCrossbar` → `L3VCache` chain into AXI (unit-test subject; in-order by design) |

### 3.2 Parameters (`CPUConfig`)

- 4 cores in-order, **1.0 GHz** target
- L1 I/D **32KB** each, L2 **256KB**, L3 **4MB**
- RVV config hooks (`vlen`/`dlen`) reserved, not yet exercised

Naming note: the design language and scaffold here follow the **NutShell** (NSCSCC
winner, OSCPU) in-order philosophy — deliberately simple, bootable, verifiable —
rather than a multi-issue out-of-order machine.

---

## 4. GPU complex: SIMT core + real Vortex RTL

### 4.1 In-Chisel SIMT core

- `SimtCore` launches a bounded element-wise vector kernel against the shared
  stack through the GPU lane and signals completion (`simt_done`).
- `VortexWrapper` provides the GPU L2 cache + `AXIToMemReq` adapter; the external
  cluster port and the on-die SIMT core both hang off it.

### 4.2 Real Vortex RTL as a Chisel BlackBox

- `VortexBlackBox.scala` declares `VortexShell` (a flat-pin wrapper around
  `Vortex_axi`) as a `BlackBox` with `desiredName = "VortexShell"`.
- When `config.gpu.vortexRtl = true`, `Top` replaces the Chisel GEMM on the
  accelerator lane with `VortexAccelerator` (BlackBox + AXI→mem adapter) and exposes
  `vx_dcr_*`, `vx_start`, `vx_busy` control pins.
- The real Verilog sources live in the mirrored `vortex/` checkout; they are **not**
  compiled by sbt — the emitted `Aegis.sv` is co-simulated with them out-of-tree.

### 4.3 Parameters (`GPUConfig`)

- 8 clusters / 64 SIMT cores nominal (1 cluster used by the real-RTL smoke)
- warp size 32, 64KB shared memory, **1.0 GHz**

---

## 5. Memory subsystem: unified shared stack (256MB)

```
RV32I CPU  ──── MemPort.cpu ──────┐
Simt/GPU   ──── MemPort.gpu ──────┼─→ SplitPrioritizer ─→ HBM3Stack
GEMM/Vortex ── MemPort.acc ───────┘        │
                                          mode (unified / CPU-priority)
```

- `HBM3Stack`: `2² banks × 2³ rows × 2³ cols` of 512-bit words = **16KB physical
  model**; address→bank/row/column decode with open-page activate/precharge timing
  and a refresh walker. `io.mem` is an AXI observability mirror — completion never
  depends on it.
- `SplitPrioritizer`: CPU always prioritized; GPU/ACC round-robin the remaining
  bandwidth. `mode = ai` enables full open-page policy on the stack.
- Config (`MemoryConfig`): **256MB unified**, 2 channels, 512-bit bus, 3.2 Gbps.

---

## 6. Fixed-function co-die

- `GemmToMem`: real GEMM engine on the shared stack, third splitter port
  (`gemm_start` / `gemm_base` / `gemm_busy`).
- `OpenGeMMWrapper` / `RayFlexWrapper`: parameterized wrappers scaffolded for future
  AI-upscaling and ray-tracing blocks; not yet silicon-real.

---

## 7. Build & verification workflow

### 7.1 Elaboration (sbt / Makefile)

```bash
make compile            # sbt compile
make verilog            # emit Aegis SystemVerilog to build/rtl/ (split file tree)
make verilog-vortex     # same SoC with real Vortex RTL BlackBox → build/vortex-smoke/emit/
make test               # full sbt suite (50 ChiselSim + emit tests)
```

`EmitSupport` re-materializes CIRCT's concatenated multi-file dump into a real file
tree (`Aegis.sv` + `verification/…`), so the verification-layer `include`s resolve
under Verilator.

### 7.2 Raw-Verilator harness (default path)

```bash
cd test && make -f Makefile.test test
```

Compiles `build/rtl/Aegis.sv` + its verification layers with the plain `test_bench.cpp`
into `build/obj_dir` and runs 1010 ticks.

### 7.3 Real Vortex RTL co-simulation (out-of-tree)

```bash
make verilog-vortex && test/vortex/vortex_smoke.sh
```

The smoke script runs three scopes against the mirrored `vortex/` sources:
1. **Standalone** — compile `VortexShell.sv` + the real `Vortex_axi` RTL alone; drive
   the flat pins from C++ (DCR-programmed grid launch → busy + AXI activity).
2. **Co-elaboration** — lint the emitted `Aegis.sv` (with `VortexShell` BlackBox)
   together with the real Vortex RTL.
3. **End-to-end** — build AND run the emitted SoC with real Vortex RTL; drive kernels
   through the SoC's own `vx_dcr_*`/`vx_start` ports, watch `vx_busy` and HBM3 mirror
   traffic.

Supported config knobs (from real Vortex `VX_config.toml`): XLEN=32, 1 cluster,
minimal define set (full cflags break Verilator const-folding).

---

## 8. Verification strategy

| Level | Tool | Scope |
|-------|------|-------|
| Unit | ChiselSim | pipeline, caches, bridges, split-prioritizer, HBM3 stack |
| Elaboration | CIRCT emit | BlackBox presence/absence, port shapes |
| Integration | ChiselSim | CPU+GPU+fixed-func sharing one stack E2E |
| Co-sim | Verilator | real Vortex RTL inside the emitted SoC top |

---

## 9. Roadmap (current + next)

### Done
- [x] Banked, self-serving HBM3 stack with open-page controller
- [x] Split-prioritizer with CPU/GPU/ACC QoS arbitration
- [x] RV32I boot core with shared-memory path (CoreMemToHBM)
- [x] SIMT on-die kernel core through GPU lane
- [x] GEMM on a third shared-memory port
- [x] Bootable `Top` + split multi-file SystemVerilog emission
- [x] Real Vortex RTL as BlackBox; standalone + co-elab + end-to-end Verilator smoke
- [x] Default raw-Verilator harness (Makefile.test) green
- [x] AXI response-ID echo fix (`AXIToMemReq` returns `RID = ARID`), unblocking
      dcache fills so the co-sim'd Vortex kernel completes and writes back
- [x] Real Vortex SIMT kernel writing results, verified at the HBM3 mirror address
      (hand-assembled `lui/addi/sw/wsync/tmc` kernel stored at VMA 0x100, `0xa5a5`
      store read back through the gpu lane from 0x10000)
- [x] VCD waveform capture of the end-to-end co-sim for bring-up analysis
- [x] CPU shared-memory round-trip through the CPU port: hand-assembled RV32I program
      (`lui/lw/lui/addi/sw/add/halt`) loaded via the boot port, `lw` reads the HBM3
      cell seeded by the GPU lane (0x10000, `0x12345678`), `sw` writes 0x10040, and
      both registers and the mirror confirm read/write — core's lui→lw→sw→copy
      path verified end-to-end
- [x] CPU loop/branch program on the shared stack: a hand-assembled RV32I loop
      (`lui/addi/xor/lw/add/bne/sw/halt`) sums a 4-word vector seeded in shared
      HBM3 (10+20+30+40), branching back on `bne` until the count hits 0, and
      stores `sum=100` at 0x10040 — verified in registers and at the mirror
- [x] Real Vortex GPGPU runs a bare-metal Rust raytracer kernel (sphere tracing,
      fresnel-mirror shading, solid-floor plane) loaded as flat firmware into
      shared HBM3, writes the framebuffer, and the CPU loop reads it back over
      the CPU memory port for direct comparison against a double-precision golden
      — 1600-pixel 40x40 frame verified under Verilator co-simulation
- [x] AXI WSTRB read-modify-write fix in `AXIToMemReq` (partial stores on the
      512-bit bus would clobber unwritten lanes; 10-state FSM performs a
      read-for-ownership + merge before commit)
- [x] Rust raytracer kernel + host-side golden generator + Python high-res renderer
      share the same scene description (three coloured glass spheres, ground plane,
      sky gradient); PNG output at 600x600 with supersampled antialiasing

---

## 10. Open source references

| Component | License | URL |
|-----------|---------|-----|
| Chisel / FIRRTL | Apache 2.0 | https://github.com/chipsalliance/chisel |
| NutShell | Mulan PSL v2 | https://github.com/OSCPU/NutShell |
| Vortex | Apache 2.0 | https://github.com/vortexgpgpu/vortex |
| Verilator | LGPL 3.0 / Artistic | https://github.com/verilator/verilator |

---

## 11. Resource notes

The whole project is tuned for a **14GB / 12-core laptop**:

- Full `--cc` build of `Aegis` + real Vortex RTL: 230 modules, ~45 C++ files;
  peak ~530MB Verilator, built with `-j2` to stay gentle on RAM.
- sbt full suite: 25 suites / 50 tests, ~5 minutes.
- 512-bit AXI signals are `VlWide<16>` in Verilator testbenches.