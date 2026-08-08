package aegis

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis.cpu.Asm

class TopSmokeTest extends AnyFlatSpec with ChiselSim {
  behavior of "Top"

  private def loadProg(dut: Top, prog: Seq[Int]): Unit = {
    prog.zipWithIndex.foreach { case (w, i) =>
      dut.prog_we.poke(true.B)
      dut.prog_addr.poke(i.U)
      dut.prog_data.poke((w.toLong & 0xffffffffL).U(32.W))
      dut.clock.step()
    }
    dut.prog_we.poke(false.B)
  }

  private def runUntilHalt(dut: Top): Unit = {
    dut.start.poke(true.B)
    dut.clock.step()
    dut.start.poke(false.B)
    var g = 0
    while (!dut.halt.peek().litToBoolean && g < 8000) { dut.clock.step(); g += 1 }
    assert(dut.halt.peek().litToBoolean, "CPU never halted")
  }

  it should "elaborate with a live HBM AXI master and correct default mode" in {
    simulate(new Top(0)(defaultConfig)) { dut =>
      dut.mem_axi.AWREADY.poke(true.B)
      dut.mem_axi.WREADY.poke(true.B)
      dut.mem_axi.BVALID.poke(false.B)
      dut.mem_axi.ARREADY.poke(true.B)
      dut.mem_axi.RVALID.poke(false.B)

      dut.mem_mode.expect(0.U)
      dut.mem_axi.AWVALID.expect(false.B)
      dut.mem_axi.ARVALID.expect(false.B)
      dut.clock.step()

      dut.mem_mode.expect(0.U)
      dut.mem_axi.AWVALID.expect(false.B)
    }
  }

  it should "boot the real RV32I core and store through the shared HBM3 stack" in {
    simulate(new Top(0)(defaultConfig)) { dut =>
      dut.mem_axi.AWREADY.poke(false.B)
      dut.mem_axi.WREADY.poke(false.B)
      dut.mem_axi.BVALID.poke(false.B)
      dut.mem_axi.ARREADY.poke(false.B)
      dut.mem_axi.RVALID.poke(false.B)
      dut.mem_axi.BRESP.poke(0.U)
      dut.mem_axi.RDATA.poke(0.U)

      dut.gpu.req.valid.poke(false.B)
      dut.gpu.resp.ready.poke(true.B)

      // CPU: store 1234 to 0x40, then read it back to x2, then halt.
      // The whole program runs on the real core through the real HBM3.
      loadProg(dut, Seq(
        Asm.addi(1, 0, 1234),
        Asm.sw(0, 1, 0x40),
        Asm.lw(2, 0, 0x40),
        Asm.halt
      ))
      runUntilHalt(dut)

      assert(dut.regs(2).peek().litValue.toInt == 1234,
        s"store/load through the SoC returned ${dut.regs(2).peek().litValue}")
    }
  }

  it should "let a GPU cluster write a line the CPU later loads" in {
    simulate(new Top(0)(defaultConfig)) { dut =>
      dut.mem_axi.AWREADY.poke(false.B)
      dut.mem_axi.WREADY.poke(false.B)
      dut.mem_axi.BVALID.poke(false.B)
      dut.mem_axi.ARREADY.poke(false.B)
      dut.mem_axi.RVALID.poke(false.B)
      dut.mem_axi.BRESP.poke(0.U)
      dut.mem_axi.RDATA.poke(0.U)

      dut.gpu.req.valid.poke(false.B)
      dut.gpu.resp.ready.poke(true.B)

      // GPU writes 0xBADF00D into the low word of line 0x80
      dut.gpu.req.valid.poke(true.B)
      dut.gpu.req.bits.addr.poke(0x80L.U)
      dut.gpu.req.bits.data.poke(BigInt("0" * 120 + "BADF00D", 16).U(512.W))
      dut.gpu.req.bits.isWrite.poke(true.B)

      var g = 0
      while (!dut.gpu.resp.valid.peek().litToBoolean && g < 2000) { dut.clock.step(); g += 1 }
      assert(dut.gpu.resp.valid.peek().litToBoolean, "GPU store never completed")
      dut.gpu.req.valid.poke(false.B)

      // CPU loads that word back into x2
      loadProg(dut, Seq(
        Asm.lw(2, 0, 0x80),
        Asm.halt
      ))
      runUntilHalt(dut)
      assert(dut.regs(2).peek().litValue.toInt == 0xBADF00D,
        s"CPU load got ${dut.regs(2).peek().litValue}")
    }
  }
}