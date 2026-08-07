package aegis.gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class GPUL2CacheMemTest extends AnyFlatSpec with ChiselSim {
  behavior of "GPUL2Cache AXI read emission"

  it should "issue a single-beat read on io.mem for the selected cluster" in {
    simulate(new GPUL2Cache) { dut =>
      dut.io.mem.ARREADY.poke(false.B)
      for (i <- 0 until 8) {
        dut.io.cluster(i).req.valid.poke(true.B)
        dut.io.cluster(i).req.bits.addr.poke(i.U)
        dut.io.cluster(i).req.bits.data.poke(i.U)
        dut.io.cluster(i).req.bits.isWrite.poke(false.B)
        dut.io.cluster(i).resp.ready.poke(true.B)
      }
      dut.clock.step()

      // round-robin starts at cluster 1 with last = 0
      dut.io.mem.ARVALID.expect(true.B)
      dut.io.mem.ARADDR.expect(1.U)
      dut.clock.step()

      dut.io.mem.ARREADY.poke(true.B)
      dut.clock.step()
      dut.io.mem.ARVALID.expect(false.B)
    }
  }
}