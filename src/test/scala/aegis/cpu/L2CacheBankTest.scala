package aegis.cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class L2CacheBankTest extends AnyFlatSpec with ChiselSim {
  behavior of "L2CacheBank"

  it should "forward a core request to the crossbar and show backpressure" in {
    simulate(new L2CacheBank(3)) { dut =>
      dut.io.core.valid.poke(true.B)
      dut.io.core.addr.poke("h2000".U)
      dut.io.core.data.poke("hDEAD".U(512.W))
      dut.io.core.ready.expect(true.B)
      dut.clock.step()
      dut.io.core.valid.poke(false.B)

      // busy until crossbar accepts: core can't accept a second request yet
      dut.io.core.ready.expect(false.B)
      dut.io.crossbar.valid.expect(true.B)
      dut.io.crossbar.addr.expect("h2000".U)
      dut.io.crossbar.data.expect("hDEAD".U(512.W))
      dut.io.crossbar.source.expect(3.U)
      dut.io.crossbar.ready.poke(true.B)
      dut.clock.step()

      dut.io.crossbar.valid.expect(false.B)
      dut.io.core.ready.expect(true.B)
    }
  }
}