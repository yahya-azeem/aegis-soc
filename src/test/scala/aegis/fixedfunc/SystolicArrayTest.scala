package aegis.fixedfunc

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class SystolicArrayTest extends AnyFlatSpec with ChiselSim {
  behavior of "SystolicArray"

  def cmdData(idx: Int, value: Int): BigInt = (BigInt(value) << 16) + idx

  it should "compute a real 2x2 matrix multiplication" in {
    simulate(new SystolicArray(2)) { dut =>
      // weights [[1,2],[3,4]]
      val w = Seq(1, 2, 3, 4)
      // inputs [[5,6],[7,8]]
      val x = Seq(5, 6, 7, 8)

      def fire(op: Int, data: BigInt): Unit = {
        var guard = 0
        while (!dut.io.cmd.ready.peek().litToBoolean && guard < 10) { dut.clock.step(); guard += 1 }
        dut.io.cmd.valid.poke(true.B)
        dut.io.cmd.bits.opcode.poke(op.U)
        dut.io.cmd.bits.data.poke(data.U(512.W))
        dut.clock.step()
        dut.io.cmd.valid.poke(false.B)
      }

      for (i <- 0 until 4) fire(0, cmdData(i, w(i)))
      for (i <- 0 until 4) fire(1, cmdData(i, x(i)))

      // trigger compute, wait for done marker
      fire(2, 0)
      var done = false
      var guard = 0
      while (!done && guard < 20) {
        if (dut.io.resp.valid.peek().litToBoolean) {
          dut.io.resp.ready.poke(true.B)
          done = true
        }
        dut.clock.step()
        guard += 1
      }
      assert(done, "compute never completed")
      dut.io.resp.ready.poke(false.B)

      // expected C = [[19,22],[43,50]]
      val expected = Seq(19, 22, 43, 50)
      val got = scala.collection.mutable.ArrayBuffer[BigInt]()
      for (i <- 0 until 4) {
        fire(3, i)
        var read_ready = false
        var guard2 = 0
        while (!read_ready && guard2 < 10) {
          if (dut.io.resp.valid.peek().litToBoolean) {
            got += dut.io.resp.bits.data.peek().litValue & ((BigInt(1) << 64) - 1)
            dut.io.resp.ready.poke(true.B)
            read_ready = true
          }
          dut.clock.step()
          guard2 += 1
        }
        dut.io.resp.ready.poke(false.B)
      }

      assert(got.toList.map(_.toLong) == expected.toList.map(_.toLong), s"GEMM mismatch: got $got, expected $expected")
    }
  }
}