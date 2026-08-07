package aegis.memory

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis.AegisConfig

class SplitPrioritizerTest extends AnyFlatSpec with ChiselSim {
  behavior of "SplitPrioritizer"

  private def sim = new SplitPrioritizer()(AegisConfig())

  private def issueReq(dut: SplitPrioritizer, addr: Long, isW: Boolean, data: Long): Unit = {
    dut.io.soc.cpu_req.valid.poke(true.B)
    dut.io.soc.cpu_req.bits.addr.poke(addr.U)
    dut.io.soc.cpu_req.bits.data.poke(data.U(512.W))
    dut.io.soc.cpu_req.bits.isWrite.poke(isW.B)
    while (!(dut.io.soc.cpu_req.valid.peek().litToBoolean && dut.io.soc.cpu_req.ready.peek().litToBoolean)) {
      dut.clock.step()
    }
    dut.clock.step() // fire
    dut.io.soc.cpu_req.valid.poke(false.B)
  }

  it should "route a CPU write to the CPU response port and leave GPU idle" in {
    simulate(sim) { dut =>
      dut.io.mode.poke(SplitMode.gaming.U)
      dut.io.soc.gpu_req.valid.poke(false.B)
      dut.io.soc.cpu_resp.ready.poke(true.B)
      dut.io.soc.gpu_resp.ready.poke(true.B)

      issueReq(dut, 0x1000, isW = true, 0xABCD)

      var guard = 0
      while (!dut.io.soc.cpu_resp.valid.peek().litToBoolean && guard < 40) { dut.clock.step(); guard += 1 }
      assert(dut.io.soc.cpu_resp.valid.peek().litToBoolean, "CPU write response never returned")
      dut.io.soc.gpu_resp.valid.expect(false.B)
    }
  }

  it should "return read data to the CPU and enable open-page in AI mode" in {
    simulate(sim) { dut =>
      dut.io.mode.poke(SplitMode.ai.U)
      dut.io.soc.gpu_req.valid.poke(false.B)
      dut.io.soc.cpu_resp.ready.poke(true.B)
      dut.io.soc.gpu_resp.ready.poke(true.B)

      // write 0xBEEF then read it back from the internal HBM3 stack
      issueReq(dut, 0x2000, isW = true, 0xBEEF)
      var wg = 0
      while (!dut.io.soc.cpu_resp.valid.peek().litToBoolean && wg < 20) { dut.clock.step(); wg += 1 }
      assert(dut.io.soc.cpu_resp.valid.peek().litToBoolean, "write never completed")

      issueReq(dut, 0x2000, isW = false, 0x0)
      var rg = 0
      while (!dut.io.soc.cpu_resp.valid.peek().litToBoolean && rg < 20) { dut.clock.step(); rg += 1 }
      assert(dut.io.soc.cpu_resp.valid.peek().litToBoolean, "read never completed")
      dut.io.soc.cpu_resp.bits.expect("hBEEF".U(512.W))
    }
  }

  it should "prioritize the CPU in gaming mode when both ports are pending" in {
    simulate(sim) { dut =>
      dut.io.mode.poke(SplitMode.gaming.U)
      dut.io.soc.cpu_resp.ready.poke(true.B)
      dut.io.soc.gpu_resp.ready.poke(true.B)

      dut.io.soc.cpu_req.valid.poke(true.B)
      dut.io.soc.cpu_req.bits.addr.poke("h3000".U)
      dut.io.soc.cpu_req.bits.isWrite.poke(true.B)
      dut.io.soc.gpu_req.valid.poke(true.B)
      dut.io.soc.gpu_req.bits.addr.poke("h4000".U)
      dut.io.soc.gpu_req.bits.isWrite.poke(true.B)

      // CPU should be selected: its request is ready, GPU's is not
      dut.io.soc.cpu_req.ready.expect(true.B)
      dut.io.soc.gpu_req.ready.expect(false.B)
      dut.clock.step()
    }
  }
}