package aegis.gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class VortexClusterTest extends AnyFlatSpec with ChiselSim {
  behavior of "VortexCluster"

  it should "stream a bounded set of memory requests and assert IRQ when finished" in {
    simulate(new VortexCluster) { dut =>
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)

      var count = 0
      var guard = 0
      while (guard < 60 && !dut.io.irq.peek().litToBoolean) {
        if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean) {
          dut.io.mem.req.bits.addr.expect((count * 8).U)
          dut.io.mem.req.bits.isWrite.expect(false.B)
          count += 1
        }
        dut.clock.step()
        guard += 1
      }

      assert(count == 8, s"expected 8 requests, got $count")
      dut.io.irq.expect(true.B)
      dut.io.mem.req.valid.expect(false.B)
    }
  }

  it should "stream scalar and vector pipeline slots alongside the requests" in {
    simulate(new VortexCluster) { dut =>
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)

      dut.cores.scalar.valid.expect(true.B)
      dut.cores.vector.valid.expect(true.B)
      dut.cores.scalar.instr.expect(0x13.U)
      dut.cores.scalar.pc.expect(0.U)
      dut.cores.vector.vaddr.expect(0.U)
      dut.clock.step()

      dut.cores.scalar.pc.expect(8.U)
      dut.cores.vector.vaddr.expect(8.U)
      dut.cores.vector.vdata.expect(4.U(1024.W))
    }
  }
}