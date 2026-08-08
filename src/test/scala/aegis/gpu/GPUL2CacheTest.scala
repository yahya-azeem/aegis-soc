package aegis.gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis._
import aegis.bridge.AXIToMemReq
import aegis.memory.{SplitPrioritizer, SplitMode}

class GPUL2MemTop(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val cluster = Vec(2, Flipped(new MemInterface))
  })

  val l2    = Module(new GPUL2Cache(2))
  val adp   = Module(new AXIToMemReq)
  val split = Module(new SplitPrioritizer)

  io.cluster <> l2.io.cluster

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

  adp.io.mem.req <> split.io.soc.gpu_req
  split.io.soc.gpu_resp <> adp.io.mem.resp
  split.io.soc.cpu_req.valid := false.B
  split.io.soc.cpu_req.bits := DontCare
  split.io.soc.cpu_resp.ready := true.B
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
}

class GPUL2CacheTest extends AnyFlatSpec with ChiselSim {
  behavior of "GPUL2Cache through the shared HBM3 stack"

  it should "return real memory data to a cluster for a read after another cluster wrote it" in {
    simulate(new GPUL2MemTop()(AegisConfig())) { dut =>
      // disable both clusters first
      dut.io.cluster(0).req.valid.poke(false.B)
      dut.io.cluster(1).req.valid.poke(false.B)
      dut.io.cluster(0).resp.ready.poke(true.B)
      dut.io.cluster(1).resp.ready.poke(true.B)

      // cluster 0 stores 0xCAFE... to line 1 and waits for completion
      dut.io.cluster(0).req.valid.poke(true.B)
      dut.io.cluster(0).req.bits.addr.poke("h40".U)
      dut.io.cluster(0).req.bits.data.poke("hCAFEBABE_CAFEBABE_CAFEBABE_CAFEBABE_DEADBEEF_DEADBEEF_DEADBEEF_DEADBEEF".U(512.W))
      dut.io.cluster(0).req.bits.isWrite.poke(true.B)

      var g = 0
      while (g < 100 && !dut.io.cluster(0).resp.valid.peek().litToBoolean) { dut.clock.step(); g += 1 }
      assert(dut.io.cluster(0).resp.valid.peek().litToBoolean, "cluster 0 store never completed")
      dut.io.cluster(0).req.valid.poke(false.B)

      // cluster 1 loads the same line back
      dut.io.cluster(1).req.valid.poke(true.B)
      dut.io.cluster(1).req.bits.addr.poke("h40".U)
      dut.io.cluster(1).req.bits.isWrite.poke(false.B)

      var got = BigInt(0)
      g = 0
      while (g < 100 && !dut.io.cluster(1).resp.valid.peek().litToBoolean) { dut.clock.step(); g += 1 }
      assert(dut.io.cluster(1).resp.valid.peek().litToBoolean, "cluster 1 load never completed")
      got = dut.io.cluster(1).resp.bits.peek().litValue
      assert(got == BigInt("CAFEBABECAFEBABECAFEBABECAFEBABEDEADBEEFDEADBEEFDEADBEEFDEADBEEF", 16),
        s"cluster 1 read wrong data: got ${got.toString(16)}")
    }
  }
}