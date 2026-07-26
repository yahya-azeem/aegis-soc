# RISC-V SoC Chisel Implementation Plan

Based on:
- **XiangShan** (kunminghu-v3): https://github.com/OpenXiangShan/XiangShan/tree/kunminghu-v3
- **Vortex GPGPU**: https://github.com/vortexgpgpu/vortex

---

## 1. Repository Structure Overview

### 1.1 XiangShan (kunminghu-v3) Tree

```
XiangShan/
├── src/main/scala/
│   ├── device/                # Virtual devices for simulation
│   ├── system/                # SoC wrapper (top-level connectivity)
│   ├── top/                   # Top module (XSTop, SoC integration)
│   ├── utils/                 # Utility code
│   └── xiangshan/             # Main CPU design code
│       ├── backend/           # Execute, writeback stages
│       ├── frontend/          # Fetch, decode, BTB, branch predictor
│       ├── cache/             # L1/L2 cache hierarchy
│       ├── csr/               # Control/status registers
│       ├── lsu/               # Load/store unit
│       ├── memblock/          # Memory block
│       ├── pmu/               # Performance monitoring
│       └── transforms/        # FIRRTL transforms
├── XSCache/                   # Cache subsystem (submodule)
├── difftest/                  # Difference testing framework
├── yunsuan/                   # Vector/SIMD unit (submodule)
├── macros/                    # Scala macros for CSR
├── project/                   # Build properties
├── scripts/                   # Agile development scripts
├── debug/                     # Debug utilities
├── docs/                      # Documentation
├── ready-to-run/              # Pre-built simulation images
├── rocket-chip/               # Rocket Chip library (submodule)
├── utility/                   # Utility library (submodule)
├── build.mill                 # Mill build definition
└── Makefile                   # Top-level build targets
```

### 1.2 Vortex GPGPU Tree

```
vortex/
├── hw/rtl/                    # Hardware RTL (SystemVerilog)
│   ├── VX_define.vh           # Global defines/parameters
│   ├── VX_gpu_pkg.sv          # GPU package (types, config)
│   ├── VX_cluster.sv          # Core cluster wrapper
│   ├── VX_socket.sv           # Socket interconnect
│   ├── VX_graphics.sv         # Graphics pipeline
│   ├── VX_kmu.sv              # Kernel management unit
│   ├── Vortex.sv              # Top-level GPU wrapper
│   ├── Vortex_axi.sv          # AXI interface wrapper
│   └── afu/                   # FPGA AFU shell
├── hw/dpi/                    # DPI for simulation
├── software/                  # Software stack
│   ├── runtime/               # Runtime API (OpenCL, Vulkan)
│   ├── driver/                # Kernel driver
│   └── libs/                  # Libraries
├── ci/                        # CI scripts
├── docs/                      # Design documentation
├── VX_config.toml             # Configuration parameters
├── VX_types.toml              # Type definitions
├── configure                  # Configure script
└── Makefile.in                # Build system
```

---

## 2. CPU Complex: XiangShan Kunminghu V3 Integration

### 2.1 Pipeline Architecture

| Stage | File(s) | Description |
|-------|---------|-------------|
| Fetch | `src/main/scala/xiangshan/frontend/` | PC gen, ICache, ITLB, branch prediction |
| Decode | `src/main/scala/xiangshan/frontend/` | Instruction decode, rename |
| Issue | `src/main/scala/xiangshan/backend/` | Reservation stations, wakeup/select logic |
| Execute | `src/main/scala/xiangshan/backend/` | ALU, FPU, branch units |
| Load/Store | `src/main/scala/xiangshan/lsu/` | Load/store queue, dcache, DTLB |
| Commit | `src/main/scala/xiangshan/backend/` | ROB, writeback, commit logic |

### 2.2 Key Implementation Steps

1. **Core integration**: Modify `src/main/scala/top/XSTop.scala` to wrap Kunminghu V3 core with TileLink/AXI interfaces
2. **Multi-core config**: Parameterize via `src/main/scala/system/` for 2-8 core clusters
3. **RVA23 compliance**: Verify CSR maps in `src/main/scala/xiangshan/csr/` for AIA, hypervisor extensions
4. **Cache hierarchy**: Configure L1 (64KB I-cache + 64KB D-cache) and L2 (1MB/core) via cache generators in `XSCache/`
5. **L3 V-Cache**: Add 3D-stacked SRAM controller over TileLink with 96-128MB capacity
6. **Vector unit**: Integrate `yunsuan/` for RVV 1.0 with VLEN=128, DLEN=1024

### 2.3 Configuration Parameters

Key parameters in `XSCache/`:
- `L2SetAssociative` (65+ tunable parameters)
- `L3SetAssociative`
- `CacheLineSize` (64B default)
- `MSHRDepth` for miss handling

---

## 3. GPU Complex: Vortex GPGPU Scaling

### 3.1 Architecture Overview

```
Vortex Top (Vortex.sv)
├── Cluster 0..N (VX_cluster.sv)
│   ├── Core 0..M (SIMT cores)
│   │   ├── Fetch/Decode
│   │   ├── Scalar pipeline (RISC-V IMA-zicsr-zfinx)
│   │   └── Vector pipeline
│   ├── Local Memory / Shared memory
│   └── Tensor Core Unit (WGMMA, mixed-precision)
├── L1/L2 Cache Subsystem
├── Memory Fabric (VX_socket.sv)
├── Command Processor
├── Graphics Pipeline (VX_graphics.sv)
│   ├── Rasterizer
│   ├── TMU (Texture Mapping)
│   └── ROP (Raster Output)
└── AXI Interface (Vortex_axi.sv)
```

### 3.2 Key Implementation Steps

1. **Configuration**: Set `VX_config.toml` for 64-128 cores, 1.5GHz target
2. **SIMT core scaling**: Modify `VX_cluster.sv` for wider warp sizes, larger register files
3. **Tensor core integration**: Add WGMMA engines per cluster in `hw/rtl/`
4. **Graphics pipeline**: Adapt `VX_graphics.sv` rasterizer, TMU, ROP for gaming workloads
5. **Memory fabric**: Configure `VX_socket.sv` for 2048-bit bus to HBM3
6. **Driver**: Use Mesa Lavapipe (`vortexpipe`) with VOLT LLVM-based SIMT compiler

### 3.3 Vortex 3.0 Features to Enable

| Feature | Config File | Status |
|---------|-------------|--------|
| SIMT execution | `VX_config.toml` | Baseline |
| Extended registers | Muon core | in v3 |
| WGMMA tensor | `VX_types.toml` | in v3 |
| FP8/INT8 support | `VX_config.toml` | in v3 |
| Vulkan support | `ci/testcases/vulkan.yaml` | in v3 |

---

## 4. Fixed-Function Acceleration Co-Die

### 4.1 Components

| Unit | Source | Function |
|------|--------|----------|
| Rasterizer | Vortex `VX_graphics.sv` | Primitive assembly, triangle setup |
| TMU | Vortex `hw/rtl/` | Texture filtering, mipmap |
| ROP | Vortex `hw/rtl/` | Z-buffer, alpha blend, AA |
| RayFlex | `https://arxiv.org/pdf/2409.06000` | BVH traversal, ray-triangle intersection |
| Systolic Array | OpenGeMM (`https://arxiv.org/html/2411.09543v2`) | Matrix multiply for AI upscaling |

### 4.2 Implementation Order

1. Implement RayFlex elastic pipeline with skid buffer
2. Integrate RayFlex BVH engines as TileLink slaves
3. Add TinyBVH software (single-header BVH lib) for driver support
4. Generate OpenGeMM matrix units parameterized for FSR/DLSS-style upscaling
5. Wire all fixed-function blocks via AXI4 interconnect

---

## 5. Memory Subsystem: 128GB Dual-Rank Split-Prioritizer

### 5.1 Architecture

```
XiangShan CPU  ──┬── TileLink ──┐
Vortex GPU    ──┬── AXI4 ──────┤
Fixed-Func    ──┬── AXI4 ──────┼── Split-Prioritizer ── HBM3/LPDDR5X PHY
RayFlex       ──┬── AXI4 ──────┘
```

### 5.2 Implementation Steps

1. Implement split-prioritizer with QoS-aware arbitration
2. **Gaming mode**: 16GB low-latency (CPU) + 112GB high-bandwidth (GPU)
3. **AI mode**: full 128GB unified with open-page policy
4. Integrate HBM3 controller IP (9.6 Gbps/pin, 1024-bit bus)
5. Add AXI4-to-TileLink bridge from `rocket-chip/`

---

## 6. Software Stack

### 6.1 Driver Layer

| Layer | Component | Integration |
|-------|-----------|-------------|
| CPU binary translation | FEX-Emu / Box64 | x86 → RISC-V JIT |
| Graphics API | DXVK → Vulkan | DirectX 11/12 translation |
| SPIR-V compiler | VOLT (LLVM) | JIT shader compilation |
| GPU driver | Mesa + vortexpipe | Vulkan / OpenCL support |
| AI framework | OpenCL / HIP | via pocl-vortex / chipStar |

### 6.2 Shader Translation Pipeline

```
Game Binary (x86/DirectX)
    → FEX-Emu (x86 → RISC-V)
    → DXVK (DirectX → Vulkan SPIR-V)
    → VOLT (SPIR-V → Vortex machine code)
    → Vortex SIMT execution
```

---

## 7. Build & Simulation Workflow

### 7.1 XiangShan Build

```bash
# From XiangShan repo root
git checkout kunminghu-v3
make init           # Initialize submodules
make verilog        # Generate SystemVerilog (build/rtl/XSTop.sv)
make emu CONFIG=MinimalConfig EMU_THREADS=4 -j$(nproc)
```

### 7.2 Vortex Build

```bash
# From vortex repo root
./configure
make
make simx          # Software simulator
```

### 7.3 Co-simulation

```
XiangShan (Verilator) ←→ difftest/NEMU ←→ Vortex (simx)
        ↓                        ↓
    Memory fabric          Shared memory model
```

---

## 8. Phased Implementation Roadmap

### Phase 1: Foundation (Months 1-3)
- [ ] Clone XiangShan (`kunminghu-v3` branch) and Vortex (`master`)
- [ ] Set up Mill/SBT build environment
- [ ] Run XiangShan `make verilog` to generate baseline
- [ ] Set up Vortex `configure && make` for software simulation
- [ ] Implement AXI4 ↔ TileLink bridge
- [ ] Integrate difftest for co-simulation

### Phase 2: CPU Subsystem (Months 2-5)
- [ ] Configure multi-core XiangShan (4 cores)
- [ ] Tune L1/L2 cache parameters via XSCache generators
- [ ] Implement L3 V-Cache controller
- [ ] Verify RVA23 compliance (AIA, hypervisor, IOMMU)
- [ ] Integrate yunsuan vector unit
- [ ] Run SPEC CPU2006 benchmarks

### Phase 3: GPU Subsystem (Months 4-8)
- [ ] Scale Vortex to 64+ cores via `VX_config.toml`
- [ ] Enable extended register support in Muon core
- [ ] Implement WGMMA tensor cores
- [ ] Integrate graphics pipeline (rasterizer, TMU, ROP)
- [ ] Write Mesa driver with vortexpipe
- [ ] Run OpenCL/Vulkan conformance tests

### Phase 4: Fixed-Function + Memory (Months 6-10)
- [ ] Implement RayFlex Chisel generator
- [ ] Implement OpenGeMM systolic array generator
- [ ] Design split-prioritizer with AXI4 QoS
- [ ] Integrate HBM3 controller interface
- [ ] Run FOSS neural upscaling (FSR-compatible)

### Phase 5: Software + System (Months 8-12)
- [ ] Integrate FEX-Emu for x86 binary translation
- [ ] Integrate DXVK for DirectX → Vulkan
- [ ] Integrate VOLT LLVM SPIR-V compiler
- [ ] Port Linux kernel + ML4W (Wayland/Hyprland)
- [ ] End-to-end demo: AAA game via FEX → DXVK → Vortex

---

## 9. Verification Strategy

| Level | Tool | Scope |
|-------|------|-------|
| Unit | ChiselTest, Verilator | Pipeline stages, cache controllers |
| Integration | difftest | CPU-GPU co-simulation |
| ISA | riscv-dv, RISCOF | RVA23 compliance |
| GPU | Vortex CI test suites | OpenCL, Vulkan, Tensor |
| Performance | Trace-driven RTL | SPEC, gaming FPS |
| Power | FIRRTL transform analysis | Clock gating, DVFS |

---

## 10. Open Source Dependencies

| Component | License | URL |
|-----------|---------|-----|
| Chisel / FIRRTL | Apache 2.0 | https://github.com/chipsalliance/chisel |
| XiangShan | Mulan PSL v2 | https://github.com/OpenXiangShan/XiangShan |
| Vortex | Apache 2.0 | https://github.com/vortexgpgpu/vortex |
| Rocket Chip | BSD | https://github.com/chipsalliance/rocket-chip |
| OpenGeMM | GPL v3 | https://arxiv.org/html/2411.09543v2 |
| RayFlex | GPL v3 | https://arxiv.org/pdf/2409.06000 |
| TinyBVH | MIT | https://github.com/jbikker/tinybvh |
| FEX-Emu | MIT | https://github.com/FEX-Emu/FEX |
| DXVK | zlib | https://github.com/doitsujin/dxvk |

---

## 11. Resource Estimation

| Component | Area (est.) | Power (est.) | Process |
|-----------|-------------|--------------|---------|
| XiangShan 4-core | 8-12 mm² | 15-25W | 3nm |
| Vortex 64-core | 40-60 mm² | 40-60W | 5nm |
| Fixed-function co-die | 15-25 mm² | 10-15W | 5nm |
| HBM3 PHY + controller | 10-15 mm² | 5-10W | 5nm |
| **Total SoC** | **~100 mm²** | **~115W TDP** | 3DHI |
