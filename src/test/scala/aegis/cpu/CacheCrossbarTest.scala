package aegis.cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class CacheCrossbarTest extends AnyFlatSpec with ChiselSim {
  behavior of "L3VCache"

  it should "report a miss on first access and a hit on the second" in {
    simulate(new L3VCache(8)) { dut =>
      dut.io.crossbar.valid.poke(true.B)
      dut.io.crossbar.source.poke(0.U)
      dut.io.crossbar.addr.poke("h10".U)
      dut.io.crossbar.data.poke("hAAAA".U(512.W))

      dut.io.hit.expect(false.B)
      dut.clock.step()

      dut.io.crossbar.addr.poke("h10".U)
      dut.io.hit.expect(true.B)
      dut.clock.step()
    }
  }

  it should "allocate a new way on a miss so the address hits afterwards" in {
    simulate(new L3VCache(8)) { dut =>
      dut.io.crossbar.valid.poke(true.B)
      dut.io.crossbar.source.poke(0.U)
      dut.io.crossbar.addr.poke("h20".U)
      dut.io.crossbar.data.poke("h5555".U(512.W))
      dut.io.hit.expect(false.B)
      dut.clock.step()

      dut.io.crossbar.addr.poke("h20".U)
      dut.io.hit.expect(true.B)
      dut.clock.step()
    }
  }
}

class CoreCrossbarTest extends AnyFlatSpec with ChiselSim {
  behavior of "CoreCrossbar"

  it should "arbitrate 4 cores round-robin to the L3" in {
    simulate(new CoreCrossbar(4)) { dut =>
      for (i <- 0 until 4) {
        dut.io.core(i).valid.poke(true.B)
        dut.io.core(i).addr.poke(i.U)
        dut.io.core(i).data.poke(i.U)
        dut.io.core(i).source.poke(i.U)
      }
      dut.io.l3.ready.poke(true.B)

      val served = scala.collection.mutable.ArrayBuffer[Int]()
      for (_ <- 0 until 6) {
        val a = dut.io.l3.addr.peek().litValue.toInt
        served += a
        dut.clock.step()
      }

      assert(served.toSet == (0 until 4).toSet, s"expected all cores, got $served")
      for (k <- 1 until served.size) {
        val step = (served(k) - served(k - 1) + 4) % 4
        assert(step == 1, s"round-robin order broken: $served")
      }
    }
  }
}