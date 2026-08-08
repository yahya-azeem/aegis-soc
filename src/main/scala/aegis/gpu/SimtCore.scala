package aegis.gpu

import chisel3._
import chisel3.util._
import aegis._

/**
 * A real SIMT vector core for a Vortex-style cluster.
 *
 * Instead of the synthetic counter stub, this core actually launches a kernel:
 * it streams N operand lines out of the shared HBM3 (through the cluster's L2
 * cache), performs an element-wise vector operation across all lanes, and
 * stores the result lines back before signalling done.
 *
 * The kernel implemented here is an element-wise add (Y = X + Z) on 32 lanes
 * of 16-bit elements per 512-bit line. Launch parameters are latched on
 * `start`, so the host (CPU/bench) can point the core at any shared-memory
 * arrays.
 */
class SimtCore(nLanes: Int = 32) extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val baseX  = Input(UInt(64.W)) // operand A line base address
    val baseZ  = Input(UInt(64.W)) // operand B line base address
    val baseY  = Input(UInt(64.W)) // result line base address
    val nLines = Input(UInt(16.W)) // number of 512-bit lines to process
    val done   = Output(Bool())
    val mem    = new MemInterface // to the cluster L2 cache
  })

  val elemBits = 16
  require(nLanes == io.mem.req.bits.data.getWidth / elemBits)

  val s_idle :: s_rdX :: s_rdXr :: s_rdZ :: s_rdZr :: s_calc :: s_wrY :: s_wrYr :: Nil = Enum(8)
  val state = RegInit(s_idle)

  val xBase = RegInit(0.U(64.W))
  val zBase = RegInit(0.U(64.W))
  val yBase = RegInit(0.U(64.W))
  val nReg  = RegInit(0.U(16.W))
  val line  = RegInit(0.U(16.W))

  val xLine = RegInit(0.U(512.W))
  val zLine = RegInit(0.U(512.W))
  val yLine = Wire(UInt(512.W))
  yLine := (0 until nLanes).map { l =>
    val sum = xLine(l * elemBits + elemBits - 1, l * elemBits) +
              zLine(l * elemBits + elemBits - 1, l * elemBits)
    sum.asUInt << (l * elemBits)
  }.reduce(_ | _)

  val doneReg = RegInit(false.B)
  when(io.start) { doneReg := false.B }

  io.mem.req.valid := (state === s_rdX) || (state === s_rdZ) || (state === s_wrY)
  io.mem.req.bits.addr := MuxLookup(state, 0.U)(Seq(
    s_rdX -> (xBase + (line << 6)),
    s_rdZ -> (zBase + (line << 6)),
    s_wrY -> (yBase + (line << 6))))
  io.mem.req.bits.data := yLine
  io.mem.req.bits.isWrite := state === s_wrY
  io.mem.req.bits.size := 0.U
  io.mem.resp.ready := (state === s_rdXr) || (state === s_rdZr) || (state === s_wrYr)

  io.done := doneReg

  switch(state) {
    is(s_idle) {
      when(io.start) {
        xBase := io.baseX
        zBase := io.baseZ
        yBase := io.baseY
        nReg  := io.nLines
        line  := 0.U
        state := s_rdX
      }
    }
    is(s_rdX)  { when(io.mem.req.fire) { state := s_rdXr } }
    is(s_rdXr) {
      when(io.mem.resp.fire) {
        xLine := io.mem.resp.bits
        state := s_rdZ
      }
    }
    is(s_rdZ)  { when(io.mem.req.fire) { state := s_rdZr } }
    is(s_rdZr) {
      when(io.mem.resp.fire) {
        zLine := io.mem.resp.bits
        state := s_calc
      }
    }
    is(s_calc) {
      state := s_wrY
    }
    is(s_wrY)  { when(io.mem.req.fire) { state := s_wrYr } }
    is(s_wrYr) {
      when(io.mem.resp.fire) {
        line := line + 1.U
        when(line === (nReg - 1.U)) {
          nReg := 0.U
          doneReg := true.B
          state := s_idle
        }.otherwise {
          state := s_rdX
        }
      }
    }
  }
}