package aegis.gpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class GPUL2CacheTest extends AnyFlatSpec with ChiselSim {
  behavior of "GPUL2Cache"

  it should "serve all 8 clusters round-robin and echo each request once" in {
    simulate(new GPUL2Cache) { dut =>
      for (i <- 0 until 8) {
        dut.io.cluster(i).req.valid.poke(true.B)
        dut.io.cluster(i).req.bits.addr.poke(i.U)
        dut.io.cluster(i).req.bits.data.poke(i.U)
        dut.io.cluster(i).req.bits.isWrite.poke((i % 2 == 0).B)
        dut.io.cluster(i).resp.ready.poke(true.B)
      }

      val served = scala.collection.mutable.ArrayBuffer[Int]()
      var guard = 0
      while (served.size < 8 && guard < 100) {
        for (j <- 0 until 8) {
          if (dut.io.cluster(j).resp.valid.peek().litToBoolean) {
            served += j
            dut.io.cluster(j).resp.bits.data.expect(j.U)
          }
        }
        dut.clock.step()
        guard += 1
      }

      assert(served.size == 8, s"expected 8 responses, got $served")
      assert(served.toSet.size == 8, s"expected distinct clusters, got $served")
      for (k <- 1 until served.size) {
        val step = (served(k) - served(k - 1) + 8) % 8
        assert(step == 1, s"round-robin order broken: $served")
      }
    }
  }
}