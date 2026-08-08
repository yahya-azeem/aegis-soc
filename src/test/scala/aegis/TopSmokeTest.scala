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
      dut.gemm_start.poke(false.B)
      dut.gemm_base.poke(0.U)

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

  it should "run the GEMM engine inside the real Top against the shared HBM3" in {
    simulate(new Top(0)(defaultConfig)) { dut =>
      val n = 8
      val aFlat = for (i <- 0 until n; j <- 0 until n) yield 2 * i + j
      val bFlat = for (i <- 0 until n; j <- 0 until n) yield i * n + j
      val expected = for (i <- 0 until n; j <- 0 until n) yield
        (0 until n).map(k => (2 * i + k) * (k * n + j)).sum
      val base = 0x800L

      def operandLine(raw: Seq[Int]): BigInt =
        raw.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
          acc | (BigInt(v & 0xffff) << (i * 16))
        }

      def issue(addr: Long, isW: Boolean, data: BigInt): Unit = {
        dut.gpu.req.valid.poke(true.B)
        dut.gpu.req.bits.addr.poke(addr.U)
        dut.gpu.req.bits.data.poke(data.U(512.W))
        dut.gpu.req.bits.isWrite.poke(isW.B)
        dut.gpu.resp.ready.poke(true.B)
        var guard = 0
        while (!(dut.gpu.req.valid.peek().litToBoolean && dut.gpu.req.ready.peek().litToBoolean) && guard < 100) {
          dut.clock.step(); guard += 1
        }
        dut.clock.step()
        dut.gpu.req.valid.poke(false.B)
        var rg = 0
        while (!dut.gpu.resp.valid.peek().litToBoolean && rg < 100) { dut.clock.step(); rg += 1 }
      }

      // seed operand tiles through the GPU port (the engine's own memory port)
      for (l <- 0 until 2) {
        issue(base + l * 0x40L, isW = true, operandLine(aFlat.slice(l * 32, l * 32 + 32)))
      }
      for (l <- 0 until 2) {
        issue(base + 0x80L + l * 0x40L, isW = true, operandLine(bFlat.slice(l * 32, l * 32 + 32)))
      }

      // start the GEMM
      dut.gemm_start.poke(true.B)
      dut.gemm_base.poke(base.U)
      dut.clock.step()
      dut.gemm_start.poke(false.B)

      var guard = 0
      while (dut.gemm_busy.peek().litToBoolean && guard < 5000) { dut.clock.step(); guard += 1 }
      assert(!dut.gemm_busy.peek().litToBoolean, "GEMM never finished")

      // read back the results and verify
      val got = scala.collection.mutable.ArrayBuffer[Int]()
      for (l <- 0 until 4) {
        issue(base + 0x100L + l * 0x40L, isW = false, 0)
        val bits = dut.gpu.resp.bits.peek().litValue
        for (e <- 0 until 16) got += ((bits >> (e * 32)) & 0xffffffffL).toInt
      }
      assert(got.toList == expected.toList, s"Top GEMM mismatch:\n got=${got.mkString(",")}\n exp=${expected.mkString(",")}")
    }
  }

  it should "run the on-die SIMT GPU kernel inside the Top against the shared HBM3" in {
    simulate(new Top(0)(defaultConfig)) { dut =>
      dut.gpu.req.valid.poke(false.B)
      dut.gpu.resp.ready.poke(true.B)
      dut.gemm_start.poke(false.B)
      dut.gemm_base.poke(0.U)

      val n = 2
      val xBase = 0x400L
      val zBase = 0x500L
      val yBase = 0x600L

      val xVals = Seq((0 until 32).map(i => (i * 3) & 0xffff), (0 until 32).map(i => (i * 5) & 0xffff))
      val zVals = Seq((0 until 32).map(i => (i * 7) & 0xffff), (0 until 32).map(i => (i * 11) & 0xffff))
      val expVals = xVals.zip(zVals).map { case (x, z) => x.zip(z).map { case (a, b) => (a + b) & 0xffff } }

      def operandLine(raw: Seq[Int]): BigInt =
        raw.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
          acc | (BigInt(v & 0xffff) << (i * 16))
        }

      def issue(addr: Long, isW: Boolean, data: BigInt): Unit = {
        dut.gpu.req.valid.poke(true.B)
        dut.gpu.req.bits.addr.poke(addr.U)
        dut.gpu.req.bits.data.poke(data.U(512.W))
        dut.gpu.req.bits.isWrite.poke(isW.B)
        dut.gpu.resp.ready.poke(true.B)
        var guard = 0
        while (!(dut.gpu.req.valid.peek().litToBoolean && dut.gpu.req.ready.peek().litToBoolean) && guard < 100) {
          dut.clock.step(); guard += 1
        }
        dut.clock.step()
        dut.gpu.req.valid.poke(false.B)
        var rg = 0
        while (!dut.gpu.resp.valid.peek().litToBoolean && rg < 100) { dut.clock.step(); rg += 1 }
      }

      // seed X and Z arrays
      for (l <- 0 until n) issue(xBase + l * 0x40L, isW = true, operandLine(xVals(l)))
      for (l <- 0 until n) issue(zBase + l * 0x40L, isW = true, operandLine(zVals(l)))

      // launch the SIMT kernel
      dut.simt_start.poke(true.B)
      dut.simt_baseX.poke(xBase.U)
      dut.simt_baseZ.poke(zBase.U)
      dut.simt_baseY.poke(yBase.U)
      dut.simt_nLines.poke(n.U)
      dut.clock.step()
      dut.simt_start.poke(false.B)

      var guard = 0
      while (!dut.simt_done.peek().litToBoolean && guard < 8000) { dut.clock.step(); guard += 1 }
      assert(dut.simt_done.peek().litToBoolean, "SIMT kernel never finished")

      // verify the summed Y array
      for (l <- 0 until n) {
        issue(yBase + l * 0x40L, isW = false, 0)
        val bits = dut.gpu.resp.bits.peek().litValue
        for (i <- 0 until 32) {
          val got = ((bits >> (i * 16)) & 0xffff).toInt
          assert(got == expVals(l)(i), s"line $l lane $i: got $got, expected ${expVals(l)(i)}")
        }
      }
    }
  }
}