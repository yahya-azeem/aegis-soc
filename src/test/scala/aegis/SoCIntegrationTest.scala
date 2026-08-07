package aegis

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis.bridge.AXITileLinkBridge
import aegis.interconnect.SoCFabric
import aegis.memory.SplitPrioritizer
import aegis.memory.SplitMode

class AxiMasterModel extends Module {
  val io = IO(new Bundle {
    val axi   = new AXIBundle(64, 512)
    val start = Input(Bool())
    val isWrite = Input(Bool())
    val addr  = Input(UInt(64.W))
    val wdata = Input(UInt(512.W))
    val done  = Output(Bool())
    val resp  = Output(UInt(512.W))
  })

  val s_idle :: s_aw :: s_w :: s_wb :: s_ar :: s_r :: Nil = Enum(6)
  val state = RegInit(s_idle)
  val data_r = RegInit(0.U(512.W))

  io.axi.AWVALID := state === s_aw
  io.axi.AWADDR := io.addr
  io.axi.AWID := 0.U
  io.axi.AWLEN := 0.U
  io.axi.AWSIZE := 6.U
  io.axi.AWBURST := 0.U
  io.axi.WVALID := state === s_w
  io.axi.WDATA := io.wdata
  io.axi.WSTRB := ~0.U(64.W)
  io.axi.WLAST := state === s_w
  io.axi.ARVALID := state === s_ar
  io.axi.ARADDR := io.addr
  io.axi.ARID := 0.U
  io.axi.ARLEN := 0.U
  io.axi.ARSIZE := 6.U
  io.axi.ARBURST := 0.U
  io.axi.BREADY := state === s_wb
  io.axi.RREADY := state === s_r

  io.done := (state === s_wb && io.axi.BVALID) || (state === s_r && io.axi.RVALID)
  io.resp := Mux(state === s_r, io.axi.RDATA, data_r)

  when(state === s_r && io.axi.RVALID) { data_r := io.axi.RDATA }

  switch(state) {
    is(s_idle) { when(io.start) { state := Mux(io.isWrite, s_aw, s_ar) } }
    is(s_aw) { when(io.axi.AWREADY) { state := s_w } }
    is(s_w)  { when(io.axi.WREADY) { state := s_wb } }
    is(s_wb) { when(io.axi.BVALID) { state := s_idle } }
    is(s_ar) { when(io.axi.ARREADY) { state := s_r } }
    is(s_r)  { when(io.axi.RVALID) { state := s_idle } }
  }
}

class SoCIntegrationTop(implicit config: AegisConfig) extends Module {
  val memAxi = IO(new AXIBundle(config.axiAddrWidth, config.axiDataWidth))
  val mode   = IO(Input(UInt(2.W)))
  val start  = IO(Input(Bool()))
  val isWrite = IO(Input(Bool()))
  val addr   = IO(Input(UInt(64.W)))
  val wdata  = IO(Input(UInt(512.W)))
  val done   = IO(Output(Bool()))
  val resp   = IO(Output(UInt(512.W)))

  val cpu     = Module(new AxiMasterModel)
  val bridge  = Module(new AXITileLinkBridge(config.tlBeatBytes, config.axiDataWidth))
  val soc     = Module(new SoCFabric)
  val mem     = Module(new SplitPrioritizer)

  cpu.io.axi <> bridge.io.axi

  cpu.io.start := start
  cpu.io.isWrite := isWrite
  cpu.io.addr := addr
  cpu.io.wdata := wdata
  done := cpu.io.done
  resp := cpu.io.resp

  bridge.io.tl <> soc.io.cpu_tl
  soc.io.mem <> mem.io.soc
  mem.io.mode := mode
  soc.io.gpu_tl := DontCare

  memAxi.AWID := mem.io.mem_axi.AWID
  memAxi.AWADDR := mem.io.mem_axi.AWADDR
  memAxi.AWLEN := mem.io.mem_axi.AWLEN
  memAxi.AWSIZE := mem.io.mem_axi.AWSIZE
  memAxi.AWBURST := mem.io.mem_axi.AWBURST
  memAxi.AWVALID := mem.io.mem_axi.AWVALID
  memAxi.WDATA := mem.io.mem_axi.WDATA
  memAxi.WSTRB := mem.io.mem_axi.WSTRB
  memAxi.WLAST := mem.io.mem_axi.WLAST
  memAxi.WVALID := mem.io.mem_axi.WVALID
  memAxi.BREADY := mem.io.mem_axi.BREADY
  memAxi.ARID := mem.io.mem_axi.ARID
  memAxi.ARADDR := mem.io.mem_axi.ARADDR
  memAxi.ARLEN := mem.io.mem_axi.ARLEN
  memAxi.ARSIZE := mem.io.mem_axi.ARSIZE
  memAxi.ARBURST := mem.io.mem_axi.ARBURST
  memAxi.ARVALID := mem.io.mem_axi.ARVALID
  memAxi.RREADY := mem.io.mem_axi.RREADY
  mem.io.mem_axi.AWREADY := memAxi.AWREADY
  mem.io.mem_axi.WREADY := memAxi.WREADY
  mem.io.mem_axi.BID := memAxi.BID
  mem.io.mem_axi.BRESP := memAxi.BRESP
  mem.io.mem_axi.BVALID := memAxi.BVALID
  mem.io.mem_axi.ARREADY := memAxi.ARREADY
  mem.io.mem_axi.RID := memAxi.RID
  mem.io.mem_axi.RDATA := memAxi.RDATA
  mem.io.mem_axi.RRESP := memAxi.RRESP
  mem.io.mem_axi.RLAST := memAxi.RLAST
  mem.io.mem_axi.RVALID := memAxi.RVALID

  soc.io.cpu := DontCare
  soc.io.gpu := DontCare
}

class SoCIntegrationTest extends AnyFlatSpec with ChiselSim {
  behavior of "SoC integration (bridge -> fabric -> splitter)"

  private def sim = new SoCIntegrationTop()(AegisConfig())

  it should "round-trip a CPU read through the full memory path" in {
    simulate(sim) { dut =>
      dut.mode.poke(SplitMode.gaming.U)
      dut.start.poke(false.B)

      // a read must flow end-to-end through the internal HBM3 stack and complete
      dut.start.poke(true.B)
      dut.isWrite.poke(false.B)
      dut.addr.poke("h4000".U)
      dut.clock.step()
      dut.start.poke(false.B)

      var rguard = 0
      while (!dut.done.peek().litToBoolean && rguard < 40) { dut.clock.step(); rguard += 1 }
      assert(dut.done.peek().litToBoolean, "read never completed end-to-end")
    }
  }

  it should "forward a CPU write to the memory stack and return a write response" in {
    simulate(sim) { dut =>
      dut.mode.poke(SplitMode.gaming.U)
      dut.start.poke(false.B)

      dut.start.poke(true.B)
      dut.isWrite.poke(true.B)
      dut.addr.poke("h8000".U)
      dut.wdata.poke("hCAFE".U(512.W))
      dut.clock.step()
      dut.start.poke(false.B)

      var doneSeen = false
      var guard = 0
      while (!doneSeen && guard < 40) {
        if (dut.done.peek().litToBoolean) doneSeen = true
        dut.clock.step()
        guard += 1
      }
      assert(doneSeen, "write never completed end-to-end")
    }
  }
}
