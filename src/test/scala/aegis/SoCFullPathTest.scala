package aegis

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis.bridge.AXITileLinkBridge
import aegis.interconnect.SoCFabric
import aegis.memory.SplitPrioritizer
import aegis.memory.SplitMode
import aegis.cpu.{CoreCrossbar, L2CacheBank, L3VCache, XiangShanCore}

class SoCFullPathTop(implicit config: AegisConfig) extends Module {
  val memAxi = IO(new AXIBundle(config.axiAddrWidth, config.axiDataWidth))
  val mode   = IO(Input(UInt(2.W)))
  val rdValid = IO(Output(Bool()))
  val rdData  = IO(Output(UInt(512.W)))

  val core    = Module(new XiangShanCore)
  val bank    = Module(new L2CacheBank(0))
  val cxb     = Module(new CoreCrossbar(1))
  val l3      = Module(new L3VCache(64))
  val bridge  = Module(new AXITileLinkBridge(config.tlBeatBytes, config.axiDataWidth))
  val soc     = Module(new SoCFabric)
  val mem     = Module(new SplitPrioritizer)

  bank.io.core <> core.io.l2
  cxb.io.core(0) <> bank.io.crossbar
  cxb.io.l3 <> l3.io.crossbar

  l3.io.mem <> bridge.io.axi

  bridge.io.tl <> soc.io.cpu_tl
  soc.io.mem <> mem.io.soc
  mem.io.mode := mode
  soc.io.gpu_tl := DontCare
  soc.io.cpu := DontCare
  soc.io.gpu := DontCare

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
  mem.io.mem_axi.BVALID := memAxi.BVALID
  mem.io.mem_axi.BRESP := memAxi.BRESP
  mem.io.mem_axi.BID := memAxi.BID
  mem.io.mem_axi.ARREADY := memAxi.ARREADY
  mem.io.mem_axi.RVALID := memAxi.RVALID
  mem.io.mem_axi.RDATA := memAxi.RDATA
  mem.io.mem_axi.RRESP := memAxi.RRESP
  mem.io.mem_axi.RLAST := memAxi.RLAST
  mem.io.mem_axi.RID := memAxi.RID

  rdValid := l3.io.mem.RVALID
  rdData := l3.io.mem.RDATA
}

class SoCFullPathTest extends AnyFlatSpec with ChiselSim {
  behavior of "SoC full CPU datapath"

  it should "stream all core-issued reads to the HBM stack and return their data" in {
    simulate(new SoCFullPathTop()(AegisConfig())) { dut =>
      dut.mode.poke(SplitMode.gaming.U)

      var arCount = 0
      var prevAr = false
      val addrs = scala.collection.mutable.SortedSet[Int]()
      var dataBack = false
      var guard = 0
      while (guard < 400 && !(arCount >= 8 && dataBack)) {
        val ar = dut.memAxi.ARVALID.peek().litToBoolean
        if (ar && !prevAr) {
          arCount += 1
          addrs += dut.memAxi.ARADDR.peek().litValue.toInt
          if (arCount > 2) println(s"AR#${arCount} addr=${dut.memAxi.ARADDR.peek().litValue.toInt}")
        }
        prevAr = ar
        if (dut.rdValid.peek().litToBoolean) { dataBack = true }
        dut.clock.step()
        guard += 1
      }

      assert(arCount >= 8, s"expected >=8 reads, got $arCount")
      for (a <- 0 until 8) {
        assert(addrs.contains(a * 16), s"read address ${a * 16} missing, saw $addrs")
      }
      assert(dataBack, "read data never returned to the L3 boundary")
    }
  }
}