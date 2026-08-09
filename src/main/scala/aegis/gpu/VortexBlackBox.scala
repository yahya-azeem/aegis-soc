package aegis.gpu

import chisel3._
import chisel3.util._
import aegis._
import aegis.bridge.AXIToMemReq

/**
 * Flat-port Chisel view of the real Vortex GPGPU wrapper (VortexShell.sv).
 *
 * `VortexShell` wraps the unmodified Vortex_axi RTL: AXI_NUM_BANKS=1, 32-bit
 * addresses, 512-bit data, 8-bit IDs. The AXI master buses of the real GPGPU
 * are re-exposed here verbatim so a plain Chisel BlackBox instantiation and a
 * raw-Verilator co-sim harness can share one port list.
 *
 * This BlackBox is co-simulated via the out-of-tree verilator flow in
 * test/vortex/ (ChiselSim cannot compile the external Vortex RTL).
 */
class VortexAxiBlackBox extends BlackBox {
  override def desiredName = "VortexShell"
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val reset = Input(Bool())

    // AXI write request channel
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())
    val m_axi_awaddr = Output(UInt(32.W))
    val m_axi_awid = Output(UInt(8.W))
    val m_axi_awlen = Output(UInt(8.W))
    val m_axi_awsize = Output(UInt(3.W))
    val m_axi_awburst = Output(UInt(2.W))
    val m_axi_awlock = Output(UInt(2.W))
    val m_axi_awcache = Output(UInt(4.W))
    val m_axi_awprot = Output(UInt(3.W))
    val m_axi_awqos = Output(UInt(4.W))
    val m_axi_awregion = Output(UInt(4.W))

    // AXI write data channel
    val m_axi_wvalid = Output(Bool())
    val m_axi_wready = Input(Bool())
    val m_axi_wdata = Output(UInt(512.W))
    val m_axi_wstrb = Output(UInt(64.W))
    val m_axi_wlast = Output(Bool())

    // AXI write response channel
    val m_axi_bvalid = Input(Bool())
    val m_axi_bready = Output(Bool())
    val m_axi_bid = Input(UInt(8.W))
    val m_axi_bresp = Input(UInt(2.W))

    // AXI read request channel
    val m_axi_arvalid = Output(Bool())
    val m_axi_arready = Input(Bool())
    val m_axi_araddr = Output(UInt(32.W))
    val m_axi_arid = Output(UInt(8.W))
    val m_axi_arlen = Output(UInt(8.W))
    val m_axi_arsize = Output(UInt(3.W))
    val m_axi_arburst = Output(UInt(2.W))
    val m_axi_arlock = Output(UInt(2.W))
    val m_axi_arcache = Output(UInt(4.W))
    val m_axi_arprot = Output(UInt(3.W))
    val m_axi_arqos = Output(UInt(4.W))
    val m_axi_arregion = Output(UInt(4.W))

    // AXI read response channel
    val m_axi_rvalid = Input(Bool())
    val m_axi_rready = Output(Bool())
    val m_axi_rdata = Input(UInt(512.W))
    val m_axi_rlast = Input(Bool())
    val m_axi_rid = Input(UInt(8.W))
    val m_axi_rresp = Input(UInt(2.W))

    // DCR write request / read response
    val dcr_req_valid = Input(Bool())
    val dcr_req_rw = Input(Bool())
    val dcr_req_addr = Input(UInt(12.W))
    val dcr_req_data = Input(UInt(32.W))
    val dcr_rsp_valid = Output(Bool())
    val dcr_rsp_data = Output(UInt(32.W))

    // control / status
    val start = Input(Bool())
    val busy = Output(Bool())
  })
}

/**
 * Pins the real Vortex GPGPU into the shared HBM3 fabric. The GPGPU AXI master
 * is the requester on the SoC `acc` port: its reads/writes go through the same
 * split-prioritizer -> HBM3 stack as the CPU, SIMT core and GEMM engine.
 */
class VortexAccelerator(
  axiAddrWidth: Int = 64,
  dataWidth:    Int = 512,
)(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val mem = new MemInterface
    val dcr = new Bundle {
      val req_valid = Input(Bool())
      val req_rw = Input(Bool())
      val req_addr = Input(UInt(12.W))
      val req_data = Input(UInt(32.W))
      val rsp_valid = Output(Bool())
      val rsp_data = Output(UInt(32.W))
    }
    val start = Input(Bool())
    val busy = Output(Bool())
  })

  val vx = Module(new VortexAxiBlackBox)
  val adp = Module(new AXIToMemReq(axiAddrWidth, dataWidth))

  vx.io.clk := clock
  vx.io.reset := reset
  vx.io.start := io.start
  io.busy := vx.io.busy
  io.dcr.rsp_valid := vx.io.dcr_rsp_valid
  io.dcr.rsp_data := vx.io.dcr_rsp_data

  vx.io.dcr_req_valid := io.dcr.req_valid
  vx.io.dcr_req_rw := io.dcr.req_rw
  vx.io.dcr_req_addr := io.dcr.req_addr
  vx.io.dcr_req_data := io.dcr.req_data

  // AXI write request channel
  adp.io.axi.AWVALID := vx.io.m_axi_awvalid
  adp.io.axi.AWADDR := vx.io.m_axi_awaddr
  adp.io.axi.AWID := vx.io.m_axi_awid
  adp.io.axi.AWLEN := vx.io.m_axi_awlen
  adp.io.axi.AWSIZE := vx.io.m_axi_awsize
  adp.io.axi.AWBURST := vx.io.m_axi_awburst
  vx.io.m_axi_awready := adp.io.axi.AWREADY

  // AXI write data channel
  adp.io.axi.WVALID := vx.io.m_axi_wvalid
  adp.io.axi.WDATA := vx.io.m_axi_wdata
  adp.io.axi.WSTRB := vx.io.m_axi_wstrb
  adp.io.axi.WLAST := vx.io.m_axi_wlast
  vx.io.m_axi_wready := adp.io.axi.WREADY

  // AXI write response channel
  vx.io.m_axi_bvalid := adp.io.axi.BVALID
  vx.io.m_axi_bid := adp.io.axi.BID
  vx.io.m_axi_bresp := adp.io.axi.BRESP
  adp.io.axi.BREADY := vx.io.m_axi_bready

  // AXI read request channel
  adp.io.axi.ARVALID := vx.io.m_axi_arvalid
  adp.io.axi.ARADDR := vx.io.m_axi_araddr
  adp.io.axi.ARID := vx.io.m_axi_arid
  adp.io.axi.ARLEN := vx.io.m_axi_arlen
  adp.io.axi.ARSIZE := vx.io.m_axi_arsize
  adp.io.axi.ARBURST := vx.io.m_axi_arburst
  vx.io.m_axi_arready := adp.io.axi.ARREADY

  // AXI read response channel
  vx.io.m_axi_rvalid := adp.io.axi.RVALID
  vx.io.m_axi_rdata := adp.io.axi.RDATA
  vx.io.m_axi_rlast := adp.io.axi.RLAST
  vx.io.m_axi_rid := adp.io.axi.RID
  vx.io.m_axi_rresp := adp.io.axi.RRESP
  adp.io.axi.RREADY := vx.io.m_axi_rready

  io.mem <> adp.io.mem
}