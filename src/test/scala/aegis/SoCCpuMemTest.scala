package aegis

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis.cpu.{CoreMemToHBM, RiscVICore, Asm}
import aegis.gpu.GPUL2Cache
import aegis.bridge.AXIToMemReq
import aegis.memory.{SplitPrioritizer, SplitMode}

/**
 * Real SoC: the RV32I core (via CoreMemToHBM) and the GPU L2 cache (via the
 * AXI adapter) are both attached to the SAME 512-bit HBM3 stack through the
 * split-prioritizer. This is the shared-memory fabric the SoC is built on.
 */
class SoCSharedMemTop(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val prog_we   = Input(Bool())
    val prog_addr = Input(UInt(log2Ceil(256).W))
    val prog_data = Input(UInt(32.W))
    val start     = Input(Bool())
    val halt      = Output(Bool())
    val regs      = Output(Vec(32, UInt(32.W)))
    val gpu       = Flipped(new MemInterface) // drive a single GPU cluster directly
  })

  // ---- CPU side: real RV32I core ----
  val core = Module(new RiscVICore(useExtMem = true))
  val cpuAdp = Module(new CoreMemToHBM)
  core.io.mem <> cpuAdp.io.word
  core.io.prog_we := io.prog_we
  core.io.prog_addr := io.prog_addr
  core.io.prog_data := io.prog_data
  core.io.start := io.start
  io.halt := core.io.halt
  io.regs := core.io.regs

  // ---- GPU side: one cluster through the real L2 cache ----
  val l2   = Module(new GPUL2Cache(1))
  val gpuAdp = Module(new AXIToMemReq)
  io.gpu <> l2.io.cluster(0)

  gpuAdp.io.axi.AWID := l2.io.mem.AWID
  gpuAdp.io.axi.AWADDR := l2.io.mem.AWADDR
  gpuAdp.io.axi.AWLEN := l2.io.mem.AWLEN
  gpuAdp.io.axi.AWSIZE := l2.io.mem.AWSIZE
  gpuAdp.io.axi.AWBURST := l2.io.mem.AWBURST
  gpuAdp.io.axi.AWVALID := l2.io.mem.AWVALID
  gpuAdp.io.axi.WDATA := l2.io.mem.WDATA
  gpuAdp.io.axi.WSTRB := l2.io.mem.WSTRB
  gpuAdp.io.axi.WLAST := l2.io.mem.WLAST
  gpuAdp.io.axi.WVALID := l2.io.mem.WVALID
  gpuAdp.io.axi.BREADY := l2.io.mem.BREADY
  gpuAdp.io.axi.ARID := l2.io.mem.ARID
  gpuAdp.io.axi.ARADDR := l2.io.mem.ARADDR
  gpuAdp.io.axi.ARLEN := l2.io.mem.ARLEN
  gpuAdp.io.axi.ARSIZE := l2.io.mem.ARSIZE
  gpuAdp.io.axi.ARBURST := l2.io.mem.ARBURST
  gpuAdp.io.axi.ARVALID := l2.io.mem.ARVALID
  gpuAdp.io.axi.RREADY := l2.io.mem.RREADY
  l2.io.mem.AWREADY := gpuAdp.io.axi.AWREADY
  l2.io.mem.WREADY := gpuAdp.io.axi.WREADY
  l2.io.mem.BVALID := gpuAdp.io.axi.BVALID
  l2.io.mem.BRESP := gpuAdp.io.axi.BRESP
  l2.io.mem.BID := gpuAdp.io.axi.BID
  l2.io.mem.ARREADY := gpuAdp.io.axi.ARREADY
  l2.io.mem.RVALID := gpuAdp.io.axi.RVALID
  l2.io.mem.RDATA := gpuAdp.io.axi.RDATA
  l2.io.mem.RRESP := gpuAdp.io.axi.RRESP
  l2.io.mem.RLAST := gpuAdp.io.axi.RLAST
  l2.io.mem.RID := gpuAdp.io.axi.RID

  // ---- shared memory fabric ----
  val split = Module(new SplitPrioritizer)
  cpuAdp.io.hbm.req <> split.io.soc.cpu_req
  split.io.soc.cpu_resp <> cpuAdp.io.hbm.resp
  gpuAdp.io.mem.req <> split.io.soc.gpu_req
  split.io.soc.gpu_resp <> gpuAdp.io.mem.resp
  split.io.soc.acc_req.valid := false.B
  split.io.soc.acc_req.bits := DontCare
  split.io.soc.acc_resp.ready := true.B
  split.io.mode := SplitMode.ai.U
  split.io.mem_axi.AWREADY := false.B
  split.io.mem_axi.WREADY := false.B
  split.io.mem_axi.BVALID := false.B
  split.io.mem_axi.BRESP := 0.U
  split.io.mem_axi.BID := 0.U
  split.io.mem_axi.ARREADY := false.B
  split.io.mem_axi.RVALID := false.B
  split.io.mem_axi.RDATA := 0.U
  split.io.mem_axi.RRESP := 0.U
  split.io.mem_axi.RLAST := false.B
  split.io.mem_axi.RID := 0.U
}

class SoCCpuMemTest extends AnyFlatSpec with ChiselSim {
  behavior of "full SoC shared HBM3 memory fabric"

  private def loadProg(dut: SoCSharedMemTop, prog: Seq[Int]): Unit = {
    prog.zipWithIndex.foreach { case (w, i) =>
      dut.io.prog_we.poke(true.B)
      dut.io.prog_addr.poke(i.U)
      dut.io.prog_data.poke((w.toLong & 0xffffffffL).U(32.W))
      dut.clock.step()
    }
    dut.io.prog_we.poke(false.B)
  }

  private def runUntilHalt(dut: SoCSharedMemTop): Unit = {
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    var g = 0
    while (!dut.io.halt.peek().litToBoolean && g < 8000) { dut.clock.step(); g += 1 }
    assert(dut.io.halt.peek().litToBoolean, "CPU never halted")
  }

  it should "let the CPU store a value the GPU cluster later reads from the same HBM3" in {
    simulate(new SoCSharedMemTop()(AegisConfig())) { dut =>
      dut.io.gpu.req.valid.poke(false.B)
      dut.io.gpu.resp.ready.poke(true.B)

      // CPU: store 1234 at 0x40 (word[64]) then halt
      loadProg(dut, Seq(
        Asm.addi(1, 0, 1234),
        Asm.sw(0, 1, 0x40),
        Asm.halt
      ))
      runUntilHalt(dut)

      // GPU cluster reads the same 512-bit line back
      dut.io.gpu.req.valid.poke(true.B)
      dut.io.gpu.req.bits.addr.poke(0x40L.U)
      dut.io.gpu.req.bits.isWrite.poke(false.B)

      var got = BigInt(0)
      var g = 0
      while (g < 2000 && !dut.io.gpu.resp.valid.peek().litToBoolean) { dut.clock.step(); g += 1 }
      assert(dut.io.gpu.resp.valid.peek().litToBoolean, "GPU read never returned")
      got = dut.io.gpu.resp.bits.peek().litValue
      // the CPU stored the 32-bit word 1234; it lands in bits[31:0] of the line
      assert((got & 0xffffffffL) == 1234, s"GPU read ${got.toString(16)}; expected low word 1234")
    }
  }

  it should "let the GPU write a line the CPU then loads as a word" in {
    simulate(new SoCSharedMemTop()(AegisConfig())) { dut =>
      dut.io.gpu.req.valid.poke(false.B)
      dut.io.gpu.resp.ready.poke(true.B)

      // GPU stores 0xDEADBEEF into the low word of line 0x80
      dut.io.gpu.req.valid.poke(true.B)
      dut.io.gpu.req.bits.addr.poke(0x80L.U)
      dut.io.gpu.req.bits.data.poke(BigInt("0" * 120 + "DEADBEEF", 16).U(512.W))
      dut.io.gpu.req.bits.isWrite.poke(true.B)

      var g = 0
      while (!dut.io.gpu.resp.valid.peek().litToBoolean && g < 2000) { dut.clock.step(); g += 1 }
      assert(dut.io.gpu.resp.valid.peek().litToBoolean, "GPU store never completed")
      dut.io.gpu.req.valid.poke(false.B)

      // CPU loads the word back and stores it in x2, then halts via a sentinel
      loadProg(dut, Seq(
        Asm.lw(2, 0, 0x80),
        Asm.halt
      ))
      runUntilHalt(dut)
      assert(dut.io.regs(2).peek().litValue.toInt == 0xDEADBEEF, s"CPU load got ${dut.io.regs(2).peek().litValue}")
    }
  }
}