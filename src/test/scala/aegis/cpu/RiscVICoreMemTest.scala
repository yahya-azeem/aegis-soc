package aegis.cpu

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis._
import aegis.memory.{SplitPrioritizer, SplitMode}

class RiscVICoreMemTop(implicit config: AegisConfig) extends Module {
  val core  = Module(new RiscVICore(useExtMem = true))
  val adp   = Module(new CoreMemToHBM)
  val split = Module(new SplitPrioritizer)

  core.io.mem <> adp.io.word
  split.io.soc.cpu_req <> adp.io.hbm.req
  split.io.soc.cpu_resp <> adp.io.hbm.resp
  split.io.soc.gpu_req := DontCare
  split.io.soc.gpu_resp.ready := false.B
  split.io.mode := SplitMode.gaming.U
  split.io.mem_axi.AWREADY := false.B
  split.io.mem_axi.WREADY := false.B
  split.io.mem_axi.BVALID := false.B
  split.io.mem_axi.BID := 0.U
  split.io.mem_axi.BRESP := 0.U
  split.io.mem_axi.ARREADY := false.B
  split.io.mem_axi.RVALID := false.B
  split.io.mem_axi.RID := 0.U
  split.io.mem_axi.RDATA := 0.U
  split.io.mem_axi.RRESP := 0.U
  split.io.mem_axi.RLAST := false.B

  val io = IO(new Bundle {
    val prog_we   = Input(Bool())
    val prog_addr = Input(UInt(log2Ceil(256).W))
    val prog_data = Input(UInt(32.W))
    val start     = Input(Bool())
    val halt      = Output(Bool())
    val regs      = Output(Vec(32, UInt(32.W)))
    val awaddr    = Output(UInt(64.W))
    val awvalid   = Output(Bool())
    val araddr    = Output(UInt(64.W))
    val arvalid   = Output(Bool())
  })
  core.io.prog_we := io.prog_we
  core.io.prog_addr := io.prog_addr
  core.io.prog_data := io.prog_data
  core.io.start := io.start
  io.halt := core.io.halt
  io.regs := core.io.regs
  io.awaddr := split.io.mem_axi.AWADDR
  io.awvalid := split.io.mem_axi.AWVALID
  io.araddr := split.io.mem_axi.ARADDR
  io.arvalid := split.io.mem_axi.ARVALID
}

class RiscVICoreMemTest extends AnyFlatSpec with ChiselSim {
  behavior of "RiscVICore through the SoC 512-bit memory path"

  it should "round-trip CPU load/store traffic through the HBM3 stack" in {
    simulate(new RiscVICoreMemTop()(AegisConfig())) { dut =>
      val prog = Seq(
        Asm.addi(1, 0, 1234),  // x1 = 1234
        Asm.sw(0, 1, 64),      // mem[64]  = 1234  (line 1, word 0)
        Asm.lw(2, 0, 64),      // x2 = mem[64]
        Asm.addi(3, 0, 999),   // x3 = 999
        Asm.sw(0, 3, 68),      // mem[68]  = 999   (line 1, word 1)
        Asm.lw(4, 0, 68),      // x4 = mem[68]
        Asm.addi(5, 0, 7),     // x5 = 7
        Asm.sw(0, 5, 0),       // mem[0]   = 7     (line 0)
        Asm.lw(6, 0, 0),       // x6 = mem[0]
        Asm.halt
      )
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
      while (!dut.io.halt.peek().litToBoolean && guard < 4000) {
        dut.clock.step()
        guard += 1
      }
      assert(dut.io.halt.peek().litToBoolean, s"core did not halt (pc=${dut.io.regs(0).peek().litValue})")
      assert(dut.io.regs(2).peek().litValue.toInt == 1234, s"lw at 64 wrong: ${dut.io.regs(2).peek().litValue}")
      assert(dut.io.regs(4).peek().litValue.toInt == 999, s"lw at 68 wrong: ${dut.io.regs(4).peek().litValue}")
      assert(dut.io.regs(6).peek().litValue.toInt == 7, s"lw at 0 wrong: ${dut.io.regs(6).peek().litValue}")
    }
  }
}
