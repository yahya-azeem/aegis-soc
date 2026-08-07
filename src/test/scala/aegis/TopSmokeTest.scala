package aegis

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class TopSmokeTest extends AnyFlatSpec with ChiselSim {
  behavior of "Top"

  it should "elaborate with a live HBM AXI master and correct default mode" in {
    simulate(new Top(0)(defaultConfig)) { dut =>
      dut.mem_axi.AWREADY.poke(true.B)
      dut.mem_axi.WREADY.poke(true.B)
      dut.mem_axi.BVALID.poke(false.B)
      dut.mem_axi.ARREADY.poke(true.B)
      dut.mem_axi.RVALID.poke(false.B)

      dut.mem_mode.expect(0.U)
      dut.mem_axi.AWVALID.expect(false.B)
      dut.mem_axi.ARVALID.expect(false.B)
      dut.clock.step()

      dut.mem_mode.expect(0.U)
      dut.mem_axi.AWVALID.expect(false.B)
    }
  }
}