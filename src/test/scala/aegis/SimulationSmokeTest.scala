package aegis

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class SmokeDUT extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  io.out := io.in + 1.U
}

class SimulationSmokeTest extends AnyFlatSpec with ChiselSim {
  "SmokeDUT" should "increment" in {
    simulate(new SmokeDUT) { dut =>
      dut.io.in.poke(41.U)
      dut.clock.step()
      dut.io.out.expect(42.U)
    }
  }
}
