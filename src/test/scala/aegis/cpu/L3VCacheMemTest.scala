package aegis.cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class L3VCacheMemTest extends AnyFlatSpec with ChiselSim {
  behavior of "L3VCache AXI read emission"

  it should "issue a single-beat read on io.mem for a crossbar request" in {
    simulate(new L3VCache(8)) { dut =>
      dut.io.mem.ARREADY.poke(false.B)
      dut.io.crossbar.valid.poke(true.B)
      dut.io.crossbar.addr.poke("h3000".U)
      dut.io.crossbar.data.poke("hAAAA".U(512.W))
      dut.io.mem.ARVALID.expect(false.B)
      dut.clock.step()

      dut.io.mem.ARVALID.expect(true.B)
      dut.io.mem.ARADDR.expect("h3000".U)
      dut.io.mem.ARLEN.expect(0.U)
      dut.clock.step()

      dut.io.mem.ARREADY.poke(true.B)
      dut.clock.step()
      dut.io.mem.ARVALID.expect(false.B)
    }
  }

  it should "hold the read address until the AXI slave accepts it" in {
    simulate(new L3VCache(8)) { dut =>
      dut.io.mem.ARREADY.poke(false.B)
      dut.io.crossbar.valid.poke(true.B)
      dut.io.crossbar.addr.poke("h5000".U)
      dut.clock.step()

      // not yet accepted -> keeps driving the same address
      dut.io.mem.ARVALID.expect(true.B)
      dut.io.mem.ARADDR.expect("h5000".U)
      dut.clock.step()
      dut.io.mem.ARADDR.expect("h5000".U)
      dut.clock.step()

      dut.io.mem.ARREADY.poke(true.B)
      dut.clock.step()
      dut.io.mem.ARVALID.expect(false.B)
    }
  }
}