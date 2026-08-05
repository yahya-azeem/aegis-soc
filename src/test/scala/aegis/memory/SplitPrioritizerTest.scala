package aegis.memory

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis.AegisConfig

class SplitPrioritizerTest extends AnyFlatSpec with ChiselSim {
  behavior of "SplitPrioritizer"

  private def sim = new SplitPrioritizer()(AegisConfig())

  it should "route a CPU write to the CPU response port and leave GPU idle" in {
    simulate(sim) { dut =>
      dut.io.mode.poke(SplitMode.gaming.U)
      dut.io.soc.gpu_req.valid.poke(false.B)
      dut.io.soc.cpu_resp.ready.poke(true.B)
      dut.io.soc.gpu_resp.ready.poke(true.B)
      dut.io.mem_axi.AWREADY.poke(true.B)
      dut.io.mem_axi.WREADY.poke(true.B)
      dut.io.mem_axi.BVALID.poke(false.B)
      dut.io.mem_axi.ARREADY.poke(true.B)
      dut.io.mem_axi.RVALID.poke(false.B)

      dut.io.soc.cpu_req.valid.poke(true.B)
      dut.io.soc.cpu_req.bits.addr.poke("h1000".U)
      dut.io.soc.cpu_req.bits.data.poke("hABCD".U(512.W))
      dut.io.soc.cpu_req.bits.isWrite.poke(true.B)
      dut.clock.step()
      dut.io.soc.cpu_req.valid.poke(false.B)

      dut.io.mem_axi.AWVALID.expect(true.B)
      dut.io.mem_axi.AWADDR.expect("h1000".U)
      dut.io.pg_active.expect(false.B)
      dut.clock.step()

      dut.io.mem_axi.WVALID.expect(true.B)
      dut.io.mem_axi.WDATA.expect("hABCD".U(512.W))
      dut.clock.step()

      dut.io.mem_axi.BVALID.poke(true.B)
      dut.io.soc.cpu_resp.valid.expect(true.B)
      dut.io.soc.gpu_resp.valid.expect(false.B)
      dut.clock.step()
      dut.io.mem_axi.BVALID.poke(false.B)
      dut.io.soc.cpu_resp.valid.expect(false.B)
    }
  }

  it should "return read data to the CPU and enable open-page in AI mode" in {
    simulate(sim) { dut =>
      dut.io.mode.poke(SplitMode.ai.U)
      dut.io.soc.gpu_req.valid.poke(false.B)
      dut.io.soc.cpu_resp.ready.poke(true.B)
      dut.io.soc.gpu_resp.ready.poke(true.B)
      dut.io.mem_axi.AWREADY.poke(true.B)
      dut.io.mem_axi.WREADY.poke(true.B)
      dut.io.mem_axi.BVALID.poke(false.B)
      dut.io.mem_axi.ARREADY.poke(true.B)
      dut.io.mem_axi.RVALID.poke(false.B)

      dut.io.soc.cpu_req.valid.poke(true.B)
      dut.io.soc.cpu_req.bits.addr.poke("h2000".U)
      dut.io.soc.cpu_req.bits.isWrite.poke(false.B)
      dut.clock.step()
      dut.io.soc.cpu_req.valid.poke(false.B)

      dut.io.mem_axi.ARVALID.expect(true.B)
      dut.io.mem_axi.ARADDR.expect("h2000".U)
      dut.io.pg_active.expect(true.B)
      dut.clock.step()

      dut.io.mem_axi.RVALID.poke(true.B)
      dut.io.mem_axi.RDATA.poke("hBEEF".U(512.W))
      dut.io.soc.cpu_resp.valid.expect(true.B)
      dut.clock.step()
      dut.io.mem_axi.RVALID.poke(false.B)

      dut.io.soc.cpu_resp.bits.data.expect("hBEEF".U(512.W))
      dut.io.soc.cpu_resp.valid.expect(false.B)
    }
  }

  it should "prioritize the CPU in gaming mode when both ports are pending" in {
    simulate(sim) { dut =>
      dut.io.mode.poke(SplitMode.gaming.U)
      dut.io.soc.cpu_resp.ready.poke(true.B)
      dut.io.soc.gpu_resp.ready.poke(true.B)
      dut.io.mem_axi.AWREADY.poke(true.B)
      dut.io.mem_axi.WREADY.poke(true.B)
      dut.io.mem_axi.BVALID.poke(false.B)
      dut.io.mem_axi.ARREADY.poke(true.B)
      dut.io.mem_axi.RVALID.poke(false.B)

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

      dut.io.mem_axi.AWVALID.expect(true.B)
      dut.io.mem_axi.AWADDR.expect("h3000".U)
    }
  }
}