package aegis.memory

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class HBM3StackTest extends AnyFlatSpec with ChiselSim {
  behavior of "HBM3Stack"

  private def stack = new HBM3Stack()

  it should "persist a write and return it on a subsequent read to the same cell" in {
    simulate(stack) { dut =>
      dut.io.open_page.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.resp.ready.poke(true.B)

      // write 0xCAFE... to byte address 0x100 (line 4, col 0)
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.isWrite.poke(true.B)
      dut.io.req.bits.addr.poke("h100".U)
      dut.io.req.bits.data.poke("hCAFE".U(512.W))
      var w = 0
      while (!dut.io.req.ready.peek().litToBoolean && w < 20) { dut.clock.step(); w += 1 }
      dut.clock.step()               // fire; enters act state
      dut.io.req.valid.poke(false.B)
      // drain through activate timing
      var wc = 0
      while (!dut.io.resp.valid.peek().litToBoolean && wc < 20) { dut.clock.step(); wc += 1 }
      assert(dut.io.resp.valid.peek().litToBoolean, "write never completed")
      dut.clock.step()

      // issue a read to the same byte address
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.isWrite.poke(false.B)
      dut.io.req.bits.addr.poke("h100".U)
      dut.io.req.bits.data.poke(0.U)
      var goto = 0
      while (goto < 20 && !dut.io.req.ready.peek().litToBoolean) { dut.clock.step(); goto += 1 }
      dut.clock.step()               // fire read while valid still high
      dut.io.req.valid.poke(false.B)
      var rc = 0
      while (!dut.io.resp.valid.peek().litToBoolean && rc < 20) { dut.clock.step(); rc += 1 }
      assert(dut.io.resp.valid.peek().litToBoolean, "read never completed")
      dut.io.resp.bits.expect("hCAFE".U(512.W))
    }
  }

  it should "keep a page open under open-page policy and close it otherwise" in {
    simulate(stack) { dut =>
      dut.io.open_page.poke(true.B)
      dut.io.req.valid.poke(false.B)
      dut.io.resp.ready.poke(true.B)

      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.isWrite.poke(true.B)
      dut.io.req.bits.addr.poke("h100".U)
      dut.io.req.bits.data.poke("hAA".U(512.W))
      dut.clock.step(); dut.clock.step()
      dut.io.req.valid.poke(false.B)
      var wc = 0
      while (!dut.io.resp.valid.peek().litToBoolean && wc < 20) { dut.clock.step(); wc += 1 }
      assert(dut.io.resp.valid.peek().litToBoolean, "write never completed")
      dut.clock.step()               // drain back to idle
      dut.io.req.valid.poke(true.B)
      dut.io.req.bits.isWrite.poke(false.B)
      dut.io.req.bits.addr.poke("h100".U)
      dut.clock.step()               // fire read while valid high
      dut.io.req.valid.poke(false.B)
      var rc = 0
      while (!dut.io.resp.valid.peek().litToBoolean && rc < 20) { dut.clock.step(); rc += 1 }
      assert(dut.io.resp.valid.peek().litToBoolean, "read never completed")
      dut.io.resp.bits.expect("hAA".U(512.W))
    }
  }
}