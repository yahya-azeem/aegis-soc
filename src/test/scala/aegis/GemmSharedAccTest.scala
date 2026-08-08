package aegis

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import aegis.fixedfunc.GemmToMem
import aegis.memory.{SplitPrioritizer, SplitMode}

/**
 * Fixed-function GEMM engine attached to the shared HBM3 through the
 * split-prioritizer's accelerator port. Operand tiles are written into the
 * stack by the CPU port, the engine computes a real matrix product, and the
 * result tile comes back through the CPU port.
 */
class GemmSharedTop(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val baseAddr = Input(UInt(64.W))
    val busy = Output(Bool())
    val cpu = new MemPort
  })

  val split = Module(new SplitPrioritizer)
  val gemm  = Module(new GemmToMem(8))

  split.io.soc <> io.cpu
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

  gemm.io.cmd.valid := io.start
  gemm.io.cmd.bits.opcode := 0.U
  gemm.io.cmd.bits.data := io.baseAddr

  gemm.io.mem.req <> split.io.soc.acc_req
  split.io.soc.acc_resp <> gemm.io.mem.resp

  io.busy := gemm.io.busy
}

class GemmSharedAccTest extends AnyFlatSpec with ChiselSim {
  behavior of "GEMM in shared memory"

  private def sim = new GemmSharedTop()(AegisConfig())

  private def issueReq(dut: GemmSharedTop, addr: Long, isW: Boolean, data: BigInt): Unit = {
    dut.io.cpu.cpu_req.valid.poke(true.B)
    dut.io.cpu.cpu_req.bits.addr.poke(addr.U)
    dut.io.cpu.cpu_req.bits.data.poke(data.U(512.W))
    dut.io.cpu.cpu_req.bits.isWrite.poke(isW.B)
    dut.io.cpu.cpu_resp.ready.poke(true.B)
    var guard = 0
    while (!(dut.io.cpu.cpu_req.valid.peek().litToBoolean && dut.io.cpu.cpu_req.ready.peek().litToBoolean) && guard < 100) {
      dut.clock.step(); guard += 1
    }
    dut.clock.step()
    dut.io.cpu.cpu_req.valid.poke(false.B)
  }

  private def waitCpuResp(dut: GemmSharedTop): Unit = {
    var guard = 0
    while (!dut.io.cpu.cpu_resp.valid.peek().litToBoolean && guard < 100) { dut.clock.step(); guard += 1 }
    assert(dut.io.cpu.cpu_resp.valid.peek().litToBoolean, "CPU port response never returned")
  }

  private def operandLine(raw: Seq[Int]): BigInt = {
    require(raw.size == 32)
    raw.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & 0xffff) << (i * 16))
    }
  }

  private def resultLine(raw: Seq[Int]): BigInt = {
    require(raw.size == 16)
    raw.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & 0xffffffffL) << (i * 32))
    }
  }

  it should "compute A*B from operand tiles stored in shared HBM3" in {
    simulate(sim) { dut =>
      val n = 8
      // A[i][j] = 2*i + j ;  B[i][j] = i*n + j  (row-major flattened)
      val aFlat = for (i <- 0 until n; j <- 0 until n) yield 2 * i + j
      val bFlat = for (i <- 0 until n; j <- 0 until n) yield i * n + j

      val expected = for (i <- 0 until n; j <- 0 until n) yield
        (0 until n).map(k => (2 * i + k) * (k * n + j)).sum

      val base = 0x800L

      // write A (lines 0..1), B (lines 2..3)
      for (l <- 0 until 2) {
        issueReq(dut, base + l * 0x40L, isW = true, operandLine(aFlat.slice(l * 32, l * 32 + 32)))
        waitCpuResp(dut)
      }
      for (l <- 0 until 2) {
        issueReq(dut, base + 0x80L + l * 0x40L, isW = true, operandLine(bFlat.slice(l * 32, l * 32 + 32)))
        waitCpuResp(dut)
      }

      // start the GEMM
      dut.io.start.poke(true.B)
      dut.io.baseAddr.poke(base.U)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var guard = 0
      while (dut.io.busy.peek().litToBoolean && guard < 5000) { dut.clock.step(); guard += 1 }
      assert(!dut.io.busy.peek().litToBoolean, "GEMM never finished")

      // read back the 4 result lines of 16 x 32-bit lanes and compare
      val got = scala.collection.mutable.ArrayBuffer[Int]()
      for (l <- 0 until 4) {
        issueReq(dut, base + 0x100L + l * 0x40L, isW = false, 0)
        waitCpuResp(dut)
        val bits = dut.io.cpu.cpu_resp.bits.peek().litValue
        for (e <- 0 until 16) {
          got += ((bits >> (e * 32)) & 0xffffffffL).toInt
        }
      }
      assert(got.toList == expected.toList,
        s"GEMM mismatch:\n got=${got.mkString(",")}\n exp=${expected.mkString(",")}")
    }
  }
}