package aegis

import chisel3._
import aegis.cpu.{CoreMemToHBM, RiscVICore}
import aegis.gpu.{GPUL2Cache, SimtCore, VortexAccelerator}
import aegis.bridge.AXIToMemReq
import aegis.fixedfunc.GemmToMem
import aegis.memory.SplitPrioritizer

/**
 * The top-level SoC. A real RV32I CPU (through CoreMemToHBM), a GPU L2 cache
 * (through the AXI adapter) and a fixed-function GEMM engine all attach to the
 * same 512-bit HBM3 stack via the split-prioritizer -- the shared-memory
 * fabric of the chip.
 *
 * mem_axi is the stack's HBM3 AXI port (a mirror of the current transaction,
 * for co-simulation / observability).
 */
class Top(memMode: Int = 0)(implicit config: AegisConfig) extends Module {
  override def desiredName = config.socName

  val mem_axi = IO(new AXIBundle(config.axiAddrWidth, config.axiDataWidth))
  val mem_mode = IO(Output(UInt(2.W)))
  val debug_uart = IO(new UARTIO)

  // CPU debug / boot interface
  val prog_we = IO(Input(Bool()))
  val prog_addr = IO(Input(UInt(8.W)))
  val prog_data = IO(Input(UInt(32.W)))
  val start = IO(Input(Bool()))
  val halt = IO(Output(Bool()))
  val regs = IO(Output(Vec(32, UInt(32.W))))

  // GPU cluster port (driven from outside, e.g. a testbench or a future
  // real shared-memory pool)
  val gpu = IO(Flipped(new MemInterface))

  // On-die SIMT GPU core: launches an element-wise kernel over arrays in the
  // shared HBM3 and signals completion. Also drives the card's IRQ line.
  val simt_start  = IO(Input(Bool()))
  val simt_baseX  = IO(Input(UInt(64.W)))
  val simt_baseZ  = IO(Input(UInt(64.W)))
  val simt_baseY  = IO(Input(UInt(64.W)))
  val simt_nLines = IO(Input(UInt(16.W)))
  val simt_done   = IO(Output(Bool()))

  // GEMM accelerator control
  val gemm_start = IO(Input(Bool()))
  val gemm_base = IO(Input(UInt(64.W)))
  val gemm_busy = IO(Output(Bool()))

  // Real Vortex GPGPU control (driven only when config.gpu.vortexRtl)
  val vx_dcr_valid = IO(Input(Bool()))
  val vx_dcr_rw = IO(Input(Bool()))
  val vx_dcr_addr = IO(Input(UInt(12.W)))
  val vx_dcr_data = IO(Input(UInt(32.W)))
  val vx_dcr_rsp_valid = IO(Output(Bool()))
  val vx_dcr_rsp_data = IO(Output(UInt(32.W)))
  val vx_start = IO(Input(Bool()))
  val vx_busy = IO(Output(Bool()))

  val split = Module(new SplitPrioritizer)

  // ---- CPU: real RV32I core into the shared stack ----
  val core = Module(new RiscVICore(useExtMem = true))
  val cpuAdp = Module(new CoreMemToHBM)
  core.io.mem <> cpuAdp.io.word
  core.io.prog_we := prog_we
  core.io.prog_addr := prog_addr
  core.io.prog_data := prog_data
  core.io.start := start
  halt := core.io.halt
  regs := core.io.regs

  cpuAdp.io.hbm.req <> split.io.soc.cpu_req
  split.io.soc.cpu_resp <> cpuAdp.io.hbm.resp

  // ---- GPU: one external cluster + an on-die SIMT core through the real
  //          L2/Adapter into the stack ----
  val l2 = Module(new GPUL2Cache(2))
  val gpuAdp = Module(new AXIToMemReq)
  val simt = Module(new SimtCore(32))
  gpu <> l2.io.cluster(0)
  simt.io.mem <> l2.io.cluster(1)
  simt.io.start := simt_start
  simt.io.baseX := simt_baseX
  simt.io.baseZ := simt_baseZ
  simt.io.baseY := simt_baseY
  simt.io.nLines := simt_nLines
  simt_done := simt.io.done

  gpuAdp.io.axi.AWID := l2.io.mem.AWID
  gpuAdp.io.axi.AWADDR := l2.io.mem.AWADDR
  gpuAdp.io.axi.AWLEN := l2.io.mem.AWLEN
  gpuAdp.io.axi.AWSIZE := l2.io.mem.AWSIZE
  gpuAdp.io.axi.AWBURST := l2.io.mem.AWBURST
  gpuAdp.io.axi.AWVALID := l2.io.mem.AWVALID
  gpuAdp.io.axi.WDATA := l2.io.mem.WDATA
  gpuAdp.io.axi.WSTRB := l2.io.mem.WSTRB
  gpuAdp.io.axi.WLAST := l2.io.mem.WLAST
  gpuAdp.io.axi.WVALID := l2.io.mem.WVALID
  gpuAdp.io.axi.BREADY := l2.io.mem.BREADY
  gpuAdp.io.axi.ARID := l2.io.mem.ARID
  gpuAdp.io.axi.ARADDR := l2.io.mem.ARADDR
  gpuAdp.io.axi.ARLEN := l2.io.mem.ARLEN
  gpuAdp.io.axi.ARSIZE := l2.io.mem.ARSIZE
  gpuAdp.io.axi.ARBURST := l2.io.mem.ARBURST
  gpuAdp.io.axi.ARVALID := l2.io.mem.ARVALID
  gpuAdp.io.axi.RREADY := l2.io.mem.RREADY
  l2.io.mem.AWREADY := gpuAdp.io.axi.AWREADY
  l2.io.mem.WREADY := gpuAdp.io.axi.WREADY
  l2.io.mem.BVALID := gpuAdp.io.axi.BVALID
  l2.io.mem.BRESP := gpuAdp.io.axi.BRESP
  l2.io.mem.BID := gpuAdp.io.axi.BID
  l2.io.mem.ARREADY := gpuAdp.io.axi.ARREADY
  l2.io.mem.RVALID := gpuAdp.io.axi.RVALID
  l2.io.mem.RDATA := gpuAdp.io.axi.RDATA
  l2.io.mem.RRESP := gpuAdp.io.axi.RRESP
  l2.io.mem.RLAST := gpuAdp.io.axi.RLAST
  l2.io.mem.RID := gpuAdp.io.axi.RID

  gpuAdp.io.mem.req <> split.io.soc.gpu_req
  split.io.soc.gpu_resp <> gpuAdp.io.mem.resp

  // ---- Fixed function / accelerator into the shared stack ----
  // When config.gpu.vortexRtl is set, the real Vortex GPGPU RTL is black-boxed
  // on the acc port (co-simulated out-of-tree with the external SV sources).
  // Otherwise the Chisel GEMM engine occupies the same fabric port.
  vx_dcr_rsp_valid := false.B
  vx_dcr_rsp_data := 0.U
  vx_busy := false.B
  gemm_busy := false.B

  if (config.gpu.vortexRtl) {
    val vx = Module(new VortexAccelerator(32, 512))
    vx.io.dcr.req_valid := vx_dcr_valid
    vx.io.dcr.req_rw := vx_dcr_rw
    vx.io.dcr.req_addr := vx_dcr_addr
    vx.io.dcr.req_data := vx_dcr_data
    vx_dcr_rsp_valid := vx.io.dcr.rsp_valid
    vx_dcr_rsp_data := vx.io.dcr.rsp_data
    vx.io.start := vx_start
    vx_busy := vx.io.busy

    vx.io.mem.req <> split.io.soc.acc_req
    split.io.soc.acc_resp <> vx.io.mem.resp
  } else {
    val gemm = Module(new GemmToMem(8))
    gemm.io.cmd.valid := gemm_start
    gemm.io.cmd.bits.opcode := 0.U
    gemm.io.cmd.bits.data := gemm_base
    gemm_busy := gemm.io.busy

    gemm.io.mem.req <> split.io.soc.acc_req
    split.io.soc.acc_resp <> gemm.io.mem.resp
  }

  split.io.mode := memMode.U(2.W)
  mem_mode := memMode.U(2.W)

  mem_axi.AWID := split.io.mem_axi.AWID
  mem_axi.AWADDR := split.io.mem_axi.AWADDR
  mem_axi.AWLEN := split.io.mem_axi.AWLEN
  mem_axi.AWSIZE := split.io.mem_axi.AWSIZE
  mem_axi.AWBURST := split.io.mem_axi.AWBURST
  mem_axi.AWVALID := split.io.mem_axi.AWVALID
  mem_axi.WDATA := split.io.mem_axi.WDATA
  mem_axi.WSTRB := split.io.mem_axi.WSTRB
  mem_axi.WLAST := split.io.mem_axi.WLAST
  mem_axi.WVALID := split.io.mem_axi.WVALID
  mem_axi.BREADY := split.io.mem_axi.BREADY
  mem_axi.ARID := split.io.mem_axi.ARID
  mem_axi.ARADDR := split.io.mem_axi.ARADDR
  mem_axi.ARLEN := split.io.mem_axi.ARLEN
  mem_axi.ARSIZE := split.io.mem_axi.ARSIZE
  mem_axi.ARBURST := split.io.mem_axi.ARBURST
  mem_axi.ARVALID := split.io.mem_axi.ARVALID
  mem_axi.RREADY := split.io.mem_axi.RREADY
  split.io.mem_axi.AWREADY := mem_axi.AWREADY
  split.io.mem_axi.WREADY := mem_axi.WREADY
  split.io.mem_axi.BVALID := mem_axi.BVALID
  split.io.mem_axi.BRESP := mem_axi.BRESP
  split.io.mem_axi.BID := mem_axi.BID
  split.io.mem_axi.ARREADY := mem_axi.ARREADY
  split.io.mem_axi.RVALID := mem_axi.RVALID
  split.io.mem_axi.RDATA := mem_axi.RDATA
  split.io.mem_axi.RRESP := mem_axi.RRESP
  split.io.mem_axi.RLAST := mem_axi.RLAST
  split.io.mem_axi.RID := mem_axi.RID

  debug_uart := DontCare
}