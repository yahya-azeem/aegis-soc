# Aegis SoC — design notes & roadmap

A single-die SoC written in Chisel that federates a RISC-V CPU core, a GPGPU (Chisel SIMT plus
the **real Vortex RTL** as a BlackBox) and a fixed-function accelerator behind one **unified
shared-memory** model, sized to be elaborated and co-simulated on a laptop.

For the architecture, build commands, proofs and the modelled-vs-target parameter table, see
**[README.md](README.md)** — it is the authoritative document. These notes record the design
rationale and the current roadmap.

---

## Design goals

- **One real memory system.** `HBM3Stack` owns a banked DRAM array with an open-page controller
  (activate/precharge/refresh). Reads are served from the array, not echoed from a testbench;
  the AXI `mem` port is an observability mirror.
- **Shared memory, one stack.** CPU, GPU and accelerator lanes all attach through
  `SplitPrioritizer`, which arbitrates with a QoS `mode` and tags responses by requester.
- **Bootable top.** `Top` elaborates to `Aegis` and emits SystemVerilog whose verification-layer
  files resolve under Verilator.
- **Real external RTL.** The upstream Vortex GPGPU is BlackBoxed onto the accelerator lane and
  co-simulated out of tree, sourcing the same shared HBM3.

### Honest scope guardrails

- The CPU is a compact **in-order RV32I multi-cycle boot core** (`RiscVICore`). There is no
  out-of-order core and none is planned.
- The GPU is a small SIMT core (`SimtCore`) plus the optional **real Vortex RTL** as a BlackBox.
  It is not a full software driver stack.
- The modelled memory is a **16 KB banked HBM3 model** (4 banks × 8 rows × 8 cols × 512-bit), the
  largest that co-simulates comfortably here; the design target is a 256 MB unified
  LPDDR5-class system.

---

## What exists today

| Component | File | Status |
|-----------|------|--------|
| RV32I core | `cpu/RiscVCore.scala` | Real; RV32I subset, boot interface, halt sentinel |
| Word→line adapter | `cpu/CoreMemToHBM.scala` | Real; sized load extraction + store RMW |
| Banked DRAM | `memory/HBM3Stack.scala` | Real; array, decode, open-page, refresh, mirror |
| QoS arbiter | `memory/SplitPrioritizer.scala` | Real; unified/gaming/ai policies |
| GPU L2 front-end | `gpu/GPUL2Cache.scala` | Real; 2-port RR + AXI4 FSM, real data return |
| AXI→MemReq bridge | `bridge/AXIToMemReq.scala` | Real; WSTRB RMW merge, ID echo |
| SIMT kernel core | `gpu/SimtCore.scala` | Real; 32-lane `Y = X + Z` over shared memory |
| GEMM engine | `fixedfunc/GemmToMem.scala` | Real; 8×8 product from shared memory |
| Real Vortex on acc port | `gpu/VortexBlackBox.scala` | Real BlackBox + AXI adapter |
| SoC top | `Top.scala` | Real; instantiates exactly the blocks above |

Verification: **13 ChiselSim/emit suites, 27 tests, all passing** (`make test`), plus a
raw-Verilator smoke harness and the out-of-tree Vortex co-simulation.

---

## Roadmap

### Done

- [x] Banked, self-serving HBM3 stack with open-page controller and refresh
- [x] Split-prioritizer with CPU/GPU/ACC QoS arbitration (unified / gaming / ai)
- [x] RV32I boot core with shared-memory path (`CoreMemToHBM`) and boot-program tests
- [x] SIMT on-die kernel core through the GPU lane
- [x] GEMM engine on a third shared-memory port
- [x] Bootable `Top` plus split multi-file SystemVerilog emission (`EmitSupport`)
- [x] Real Vortex RTL as a BlackBox; standalone + co-elaboration + end-to-end Verilator smoke
- [x] AXI `WSTRB` read-modify-write fix and response-ID echo (`AXIToMemReq`)
- [x] Real Vortex kernel writing results into shared HBM3, read back through the SoC
- [x] CPU shared-memory round-trip and loop/branch program verified against the stack
- [x] Bare-metal Rust raytracer kernel + double-precision Python golden + PNG render

### Next

- [ ] Add a pipeline and a small I/D cache to `RiscVICore`
- [ ] Warp-schedule `SimtCore` (multiple warps per cluster)
- [ ] Expose performance counters for the QoS arbiter
- [ ] Wider Vortex configuration (more clusters) once host RAM allows

---

## References

| Component | License | URL |
|-----------|---------|-----|
| Chisel / FIRRTL / CIRCT | Apache-2.0 | https://github.com/chipsalliance/chisel |
| Vortex (vendored in `vortex/`) | Apache-2.0 | https://github.com/vortexgpgpu/vortex |
| Verilator | LGPL-3.0 / Artistic-2.0 | https://github.com/verilator/verilator |
