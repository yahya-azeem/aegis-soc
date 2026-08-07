package aegis.cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class XiangShanCoreTest extends AnyFlatSpec with ChiselSim {
  behavior of "XiangShanCore"

  it should "drive a bounded burst of stores to the L2 interface" in {
    simulate(new XiangShanCore) { dut =>
      dut.io.l2.ready.poke(false.B)
      dut.clock.step()

      var count = 0
      var guard = 0
      dut.io.l2.ready.poke(true.B)
      while (count < 8 && guard < 60) {
        if (dut.io.l2.valid.peek().litToBoolean) {
          dut.io.l2.addr.expect((count * 16).U)
          dut.io.l2.data.expect((count * 2).U(512.W))
          count += 1
        }
        dut.clock.step()
        guard += 1
      }

      assert(count == 8, s"expected 8 requests, got $count")
      dut.io.l2.valid.expect(false.B)
    }
  }

  it should "backpressure while the L2 is busy and resume afterwards" in {
    simulate(new XiangShanCore) { dut =>
      dut.io.l2.ready.poke(false.B)
      dut.io.l2.valid.expect(true.B)
      dut.io.l2.addr.expect(0.U)
      dut.clock.step()

      dut.io.l2.ready.poke(false.B)
      dut.io.l2.valid.expect(true.B)
      dut.io.l2.addr.expect(0.U)
      dut.clock.step()

      dut.io.l2.ready.poke(true.B)
      dut.clock.step()
      dut.io.l2.addr.expect(16.U)
    }
  }

  it should "produce ipi and msi after the request burst completes" in {
    simulate(new XiangShanCore) { dut =>
      dut.io.l2.ready.poke(true.B)
      var guard = 0
      while (dut.io.l2.valid.peek().litToBoolean && guard < 40) {
        dut.clock.step()
        guard += 1
      }
      dut.clock.step()
      assert(dut.io.ipi.peek().litToBoolean || dut.io.msi.peek().litToBoolean,
        "expected an interrupt request after the burst")
    }
  }
}