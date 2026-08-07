package aegis.cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class RiscVICoreTest extends AnyFlatSpec with ChiselSim {
  private def runProgram(prog: Seq[Int])(check: (RiscVICore, Int) => Unit): Unit = {
    simulate(new RiscVICore()) { dut =>
      prog.zipWithIndex.foreach { case (w, i) =>
        dut.io.prog_we.poke(true.B)
        dut.io.prog_addr.poke(i.U)
        dut.io.prog_data.poke((w.toLong & 0xffffffffL).U(32.W))
        dut.clock.step()
      }
      dut.io.prog_we.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var guard = 0
      while (!dut.io.halt.peek().litToBoolean && guard < 400) {
        dut.clock.step()
        guard += 1
      }
      assert(dut.io.halt.peek().litToBoolean, s"core did not halt (pc=${dut.io.pc.peek().litValue})")
      check(dut, guard)
    }
  }

  "RiscVICore" should "execute arithmetic and logic instructions" in {
    val prog = Seq(
      Asm.addi(1, 0, 10),    // x1 = 10
      Asm.addi(2, 0, 20),    // x2 = 20
      Asm.add(3, 1, 2),      // x3 = 30
      Asm.sub(4, 2, 1),      // x4 = 10
      Asm.and(5, 1, 2),      // x5 = 10 & 20 = 0
      Asm.or(6, 1, 2),       // x6 = 30
      Asm.xor(7, 1, 2),      // x7 = 30
      Asm.slli(8, 1, 2),     // x8 = 40
      Asm.srli(9, 8, 3),     // x9 = 5
      Asm.slti(10, 1, 11),   // x10 = 1 < 11 = 1
      Asm.addi(11, 0, -5),   // x11 = -5
      Asm.add(12, 1, 11),    // x12 = 5
      Asm.lui(13, 0x12345),  // x13 = 0x12345000
      Asm.halt
    )
    runProgram(prog) { (dut, _) =>
      val rf = (1 to 13).map(i => dut.io.regs(i).peek().litValue.toInt)
      assert(rf(0) == 10, s"x1=$rf(0)")
      assert(rf(1) == 20, s"x2=$rf(1)")
      assert(rf(2) == 30, s"x3=$rf(2)")
      assert(rf(3) == 10, s"x4=$rf(3)")
      assert(rf(4) == 0, s"x5=$rf(4)")
      assert(rf(5) == 30, s"x6=$rf(5)")
      assert(rf(6) == 30, s"x7=$rf(6)")
      assert(rf(7) == 40, s"x8=$rf(7)")
      assert(rf(8) == 5, s"x9=$rf(8)")
      assert(rf(9) == 1, s"x10=$rf(9)")
      assert(rf(10) == -5 || rf(10) == 0xfffffffb, s"x11=$rf(10)")
      assert(rf(11) == 5, s"x12=$rf(11)")
      assert(rf(12) == 0x12345000, s"x13=$rf(12)")
    }
  }

  it should "round-trip a store and load through data memory" in {
    val prog = Seq(
      Asm.addi(1, 0, 100),   // x1 = 100
      Asm.sw(0, 1, 0),       // mem[0] = 100
      Asm.lw(2, 0, 0),       // x2 = mem[0] = 100
      Asm.addi(3, 2, 5),     // x3 = 105
      Asm.sw(0, 3, 4),       // mem[4] = 105
      Asm.lw(4, 0, 4),       // x4 = 105
      Asm.add(5, 2, 4),      // x5 = 205
      Asm.halt
    )
    runProgram(prog) { (dut, _) =>
      assert(dut.io.regs(2).peek().litValue.toInt == 100, "lw did not return stored value")
      assert(dut.io.regs(4).peek().litValue.toInt == 105, "second lw wrong")
      assert(dut.io.regs(5).peek().litValue.toInt == 205, "add after loads wrong")
      assert(dut.io.dmem(0).peek().litValue.toInt == 100, "mem[0]")
      assert(dut.io.dmem(1).peek().litValue.toInt == 105, "mem[1]")
    }
  }

  it should "take and skip branches and jump links" in {
    // idx0: addi x1,x0,1     pc=0
    // idx1: addi x2,x0,2     pc=4
    // idx2: beq  x1,x2, +?   pc=8   not taken (1!=2) -> fall through
    // idx3: addi x3,x0,33    pc=12  x3=33
    // idx4: jal  x0, +12     pc=16  jump to pc=28 (idx7)
    // idx5: addi x4,x0,77    pc=20  skipped
    // idx6: addi x4,x0,88    pc=24  skipped
    // idx7: addi x5,x0,5     pc=28  x5=5
    // idx8: halt             pc=32
    val prog = Seq(
      Asm.addi(1, 0, 1),
      Asm.addi(2, 0, 2),
      Asm.beq(1, 2, 0),
      Asm.addi(3, 0, 33),
      Asm.jal(0, 12),
      Asm.addi(4, 0, 77),
      Asm.addi(4, 0, 88),
      Asm.addi(5, 0, 5),
      Asm.halt
    )
    runProgram(prog) { (dut, _) =>
      assert(dut.io.regs(3).peek().litValue.toInt == 33, "fall-through path wrong")
      assert(dut.io.regs(5).peek().litValue.toInt == 5, "jal target path wrong")
      assert(dut.io.regs(4).peek().litValue.toInt == 0, "skipped instructions executed")
    }
  }

  it should "execute a jalr with link return" in {
    // idx0: addi x1,x0,8     pc=0    x1=8
    // idx1: jalr x2,x1,4     pc=4    link x2=8, jump to (8+4)=12 -> idx3
    // idx2: addi x3,x0,44    pc=8    skipped
    // idx3: addi x4,x0,4     pc=12   x4=4
    // idx4: jal  x0,+16      pc=16   jump to pc=32 (idx8)
    // idx5: addi x5,x0,99    pc=20   skipped
    // idx6: addi x5,x0,88    pc=24   skipped
    // idx7: addi x6,x0,7     pc=28   skipped
    // idx8: halt             pc=32
    val prog = Seq(
      Asm.addi(1, 0, 8),
      Asm.jalr(2, 1, 4),
      Asm.addi(3, 0, 44),
      Asm.addi(4, 0, 4),
      Asm.jal(0, 16),
      Asm.addi(5, 0, 99),
      Asm.addi(5, 0, 88),
      Asm.addi(6, 0, 7),
      Asm.halt
    )
    runProgram(prog) { (dut, _) =>
      assert(dut.io.regs(2).peek().litValue.toInt == 8, "jalr link value wrong")
      assert(dut.io.regs(3).peek().litValue.toInt == 0, "skipped instr executed")
      assert(dut.io.regs(4).peek().litValue.toInt == 4, "jalr target wrong")
      assert(dut.io.regs(5).peek().litValue.toInt == 0, "jal-skip path wrong")
      assert(dut.io.regs(6).peek().litValue.toInt == 0, "skipped idx7 executed")
    }
  }
}
