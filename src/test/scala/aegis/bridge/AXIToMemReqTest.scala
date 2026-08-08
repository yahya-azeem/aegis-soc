package aegis.bridge

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis._
import aegis.memory.{SplitPrioritizer, SplitMode}

class AXIToMemTop(implicit config: AegisConfig) extends Module {
  val io = IO(Flipped(new AXIBundle(config.axiAddrWidth, config.axiDataWidth)))
  val adp   = Module(new AXIToMemReq)
  val split = Module(new SplitPrioritizer)

  // master-driven lines: forward the adapter's slave inputs from the top
  adp.io.axi.AWID := io.AWID
  adp.io.axi.AWADDR := io.AWADDR
  adp.io.axi.AWLEN := io.AWLEN
  adp.io.axi.AWSIZE := io.AWSIZE
  adp.io.axi.AWBURST := io.AWBURST
  adp.io.axi.AWVALID := io.AWVALID
  adp.io.axi.WDATA := io.WDATA
  adp.io.axi.WSTRB := io.WSTRB
  adp.io.axi.WLAST := io.WLAST
  adp.io.axi.WVALID := io.WVALID
  adp.io.axi.BREADY := io.BREADY
  adp.io.axi.ARID := io.ARID
  adp.io.axi.ARADDR := io.ARADDR
  adp.io.axi.ARLEN := io.ARLEN
  adp.io.axi.ARSIZE := io.ARSIZE
  adp.io.axi.ARBURST := io.ARBURST
  adp.io.axi.ARVALID := io.ARVALID
  adp.io.axi.RREADY := io.RREADY
  // slave-driven lines (source from the adapter to the top)
  io.AWREADY := adp.io.axi.AWREADY
  io.WREADY := adp.io.axi.WREADY
  io.BVALID := adp.io.axi.BVALID
  io.BRESP := adp.io.axi.BRESP
  io.BID := adp.io.axi.BID
  io.ARREADY := adp.io.axi.ARREADY
  io.RVALID := adp.io.axi.RVALID
  io.RDATA := adp.io.axi.RDATA
  io.RRESP := adp.io.axi.RRESP
  io.RLAST := adp.io.axi.RLAST
  io.RID := adp.io.axi.RID

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

class AXIToMemReqTest extends AnyFlatSpec with ChiselSim {
  behavior of "AXIToMemReq AXI slave -> HBM3"

  it should "round-trip a real store and load through the shared HBM3 stack" in {
    simulate(new AXIToMemTop()(AegisConfig())) { dut =>
val hex = "CAFEBABE_CAFEBABE_CAFEBABE_CAFEBABE_DEADBEEF_DEADBEEF_DEADBEEF_DEADBEEF"
      val dataBig = BigInt(hex.replace("_", ""), 16)
      // ---- store: AW + W of one 512-bit line, then B handshake ----
      dut.io.AWADDR.poke("h40".U)
      dut.io.AWVALID.poke(true.B)
      dut.io.WDATA.poke(("h" + hex).U(512.W))
      dut.io.WVALID.poke(true.B)
      dut.io.BREADY.poke(true.B)
      dut.io.RREADY.poke(true.B)
      dut.io.ARVALID.poke(false.B)

      var bSeen = false
      var guard = 0
      while (guard < 40 && !bSeen) { bSeen = dut.io.BVALID.peek().litToBoolean; dut.clock.step(); guard += 1 }
      assert(bSeen, "store write response (B) never returned")
      // clear AW/W so we don't loop
      dut.io.AWVALID.poke(false.B)
      dut.io.WVALID.poke(false.B)

      // ---- load: AR of same address, capture R data ----
      dut.io.ARADDR.poke("h40".U)
      dut.io.ARVALID.poke(true.B)
      var rValid = false
      var got = BigInt(0)
      var rg = 0
      while (rg < 80 && !rValid) {
        if (dut.io.RVALID.peek().litToBoolean) {
          rValid = true
          got = dut.io.RDATA.peek().litValue
        }
        dut.clock.step()
        rg += 1
      }
      assert(rValid, "read response R never came back")
      assert(got == dataBig, s"loaded data mismatch:\n  got  ${got.toString(16)}\n  want ${dataBig.toString(16)}")
    }
  }
}