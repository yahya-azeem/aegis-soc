package aegis.fixedfunc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class RayFlexPipelineTest extends AnyFlatSpec with ChiselSim {
  behavior of "RayFlexPipeline"

  def rayData(originHigh: BigInt, dir: BigInt): BigInt = {
    (dir << 192) + (originHigh << 32)
  }

  def hitDistOf(v: BigInt): Long = (v & 0xffffffffL).toLong
  def hitOf(v: BigInt): Long = ((v >> 32) & 1).toLong

  it should "report the expected hit distance after the elastic latency" in {
    simulate(new RayFlexPipeline) { dut =>
      dut.io.resp.ready.poke(true.B)
      dut.io.cmd.valid.poke(true.B)
      dut.io.cmd.bits.opcode.poke(0.U)
      dut.io.cmd.bits.data.poke(rayData(1, 1).U(512.W))
      dut.clock.step()
      dut.io.cmd.valid.poke(false.B)

      var seen = false
      for (_ <- 0 until 8) {
        if (dut.io.resp.valid.peek().litToBoolean) {
          seen = true
          val v = dut.io.resp.bits.data.peek().litValue
          assert(hitDistOf(v) == 2, s"hit distance wrong: $v")
          assert(hitOf(v) == 1, s"hit flag wrong: $v")
        }
        dut.clock.step()
      }
      assert(seen, "response never became valid")
    }
  }

  it should "pass rays through in order with a stalled sink (elastic backpressure)" in {
    simulate(new RayFlexPipeline) { dut =>
      dut.io.resp.ready.poke(false.B)

      for (h <- Seq(1, 2, 3)) {
        var guard = 0
        while (!dut.io.cmd.ready.peek().litToBoolean && guard < 10) {
          dut.clock.step()
          guard += 1
        }
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.data.poke(rayData(h, 1).U(512.W))
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)
      }

      val got = scala.collection.mutable.ArrayBuffer[BigInt]()
      var guard = 0
      while (got.size < 3 && guard < 20) {
        if (dut.io.resp.valid.peek().litToBoolean) {
          got += dut.io.resp.bits.data.peek().litValue
        }
        dut.io.resp.ready.poke(true.B)
        dut.clock.step()
        guard += 1
      }

      assert(got.map(hitDistOf).toList == List(2, 3, 4), s"expected ordered hits, got $got")
    }
  }
}