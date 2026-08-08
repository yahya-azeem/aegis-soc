package aegis.gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis._
import aegis.bridge.AXIToMemReq
import aegis.memory.{SplitPrioritizer, SplitMode}

/** SimtCore through a real L2 cache + AXI adapter into the shared HBM3. */
class SimtCoreMemTop(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val baseX  = Input(UInt(64.W))
    val baseZ  = Input(UInt(64.W))
    val baseY  = Input(UInt(64.W))
    val nLines = Input(UInt(16.W))
    val done   = Output(Bool())
    val host   = new MemPort // bench drives via the CPU side
  })

  val split = Module(new SplitPrioritizer)
  val l2    = Module(new GPUL2Cache(1))
  val adp   = Module(new AXIToMemReq)
  val simt  = Module(new SimtCore(32))

  split.io.soc <> io.host
  split.io.mode := SplitMode.ai.U
  split.io.mem_axi.AWREADY := false.B
  split.io.mem_axi.WREADY := false.B
  split.io.mem_axi.BVALID := false.B
  split.io.mem_axi.BRESP := 0.U
  split.io.mem_axi.BID := 0.U
  split.io.mem_axi.ARREADY := false.B
  split.io.mem_axi.RVALID := false.B
  split.io.mem_axi.RDATA := 0.U
  split.io.mem_axi.RRESP := 0.U
  split.io.mem_axi.RLAST := false.B
  split.io.mem_axi.RID := 0.U

  simt.io.start := io.start
  simt.io.baseX := io.baseX
  simt.io.baseZ := io.baseZ
  simt.io.baseY := io.baseY
  simt.io.nLines := io.nLines
  io.done := simt.io.done

  // one cluster = the SIMT core, through the real L2 + AXI adapter
  simt.io.mem <> l2.io.cluster(0)

  adp.io.axi.AWID := l2.io.mem.AWID
  adp.io.axi.AWADDR := l2.io.mem.AWADDR
  adp.io.axi.AWLEN := l2.io.mem.AWLEN
  adp.io.axi.AWSIZE := l2.io.mem.AWSIZE
  adp.io.axi.AWBURST := l2.io.mem.AWBURST
  adp.io.axi.AWVALID := l2.io.mem.AWVALID
  adp.io.axi.WDATA := l2.io.mem.WDATA
  adp.io.axi.WSTRB := l2.io.mem.WSTRB
  adp.io.axi.WLAST := l2.io.mem.WLAST
  adp.io.axi.WVALID := l2.io.mem.WVALID
  adp.io.axi.BREADY := l2.io.mem.BREADY
  adp.io.axi.ARID := l2.io.mem.ARID
  adp.io.axi.ARADDR := l2.io.mem.ARADDR
  adp.io.axi.ARLEN := l2.io.mem.ARLEN
  adp.io.axi.ARSIZE := l2.io.mem.ARSIZE
  adp.io.axi.ARBURST := l2.io.mem.ARBURST
  adp.io.axi.ARVALID := l2.io.mem.ARVALID
  adp.io.axi.RREADY := l2.io.mem.RREADY
  l2.io.mem.AWREADY := adp.io.axi.AWREADY
  l2.io.mem.WREADY := adp.io.axi.WREADY
  l2.io.mem.BVALID := adp.io.axi.BVALID
  l2.io.mem.BRESP := adp.io.axi.BRESP
  l2.io.mem.BID := adp.io.axi.BID
  l2.io.mem.ARREADY := adp.io.axi.ARREADY
  l2.io.mem.RVALID := adp.io.axi.RVALID
  l2.io.mem.RDATA := adp.io.axi.RDATA
  l2.io.mem.RRESP := adp.io.axi.RRESP
  l2.io.mem.RLAST := adp.io.axi.RLAST
  l2.io.mem.RID := adp.io.axi.RID

  adp.io.mem.req <> split.io.soc.acc_req
  split.io.soc.acc_resp <> adp.io.mem.resp
}

class SimtCoreTest extends AnyFlatSpec with ChiselSim {
  behavior of "SimtCore (real SIMD cluster against shared HBM3)"

  private def sim = new SimtCoreMemTop()(AegisConfig())

  private def hostReq(dut: SimtCoreMemTop, addr: Long, isW: Boolean, data: BigInt): Unit = {
    dut.io.host.acc_req.valid.poke(false.B) // ensure acc not asserted from bench
    dut.io.host.cpu_req.valid.poke(true.B)
    dut.io.host.cpu_req.bits.addr.poke(addr.U)
    dut.io.host.cpu_req.bits.data.poke(data.U(512.W))
    dut.io.host.cpu_req.bits.isWrite.poke(isW.B)
    dut.io.host.cpu_resp.ready.poke(true.B)
    var guard = 0
    while (!(dut.io.host.cpu_req.valid.peek().litToBoolean && dut.io.host.cpu_req.ready.peek().litToBoolean) && guard < 100) {
      dut.clock.step(); guard += 1
    }
    dut.clock.step()
    dut.io.host.cpu_req.valid.poke(false.B)
    var g2 = 0
    while (!dut.io.host.cpu_resp.valid.peek().litToBoolean && g2 < 100) { dut.clock.step(); g2 += 1 }
  }

  private def line(addr: Long, vals: Seq[Int]): BigInt =
    vals.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & 0xffff) << (i * 16))
    }

  it should "launch a kernel Y = X + Z on arrays stored in shared HBM3" in {
    simulate(new SimtCoreMemTop()(AegisConfig())) { dut =>
      dut.io.host.acc_req.valid.poke(false.B)
      dut.io.host.acc_resp.ready.poke(true.B)

      val n = 2 // lines
      val xBase = 0x400L
      val zBase = 0x500L
      val yBase = 0x600L

      val xVals = Seq(
        (0 until 32).map(i => (i * 3) & 0xffff),
        (0 until 32).map(i => (i * 5) & 0xffff))
      val zVals = Seq(
        (0 until 32).map(i => (i * 7) & 0xffff),
        (0 until 32).map(i => (i * 11) & 0xffff))
      val expVals = xVals.zip(zVals).map { case (x, z) => x.zip(z).map { case (a, b) => (a + b) & 0xffff } }

      // seed X and Z
      for (l <- 0 until n) hostReq(dut, xBase + l * 0x40L, isW = true, line(0, xVals(l)))
      for (l <- 0 until n) hostReq(dut, zBase + l * 0x40L, isW = true, line(0, zVals(l)))

      // launch the kernel
      dut.io.start.poke(true.B)
      dut.io.baseX.poke(xBase.U)
      dut.io.baseZ.poke(zBase.U)
      dut.io.baseY.poke(yBase.U)
      dut.io.nLines.poke(n.U)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 5000) { dut.clock.step(); guard += 1 }
      assert(dut.io.done.peek().litToBoolean, "SIMT kernel never finished")

      // verify results
      for (l <- 0 until n) {
        hostReq(dut, yBase + l * 0x40L, isW = false, 0)
        val bits = dut.io.host.cpu_resp.bits.peek().litValue
        for (i <- 0 until 32) {
          val got = ((bits >> (i * 16)) & 0xffff).toInt
          assert(got == expVals(l)(i),
            s"line $l lane $i: got $got, expected ${expVals(l)(i)}")
        }
      }
    }
  }
}