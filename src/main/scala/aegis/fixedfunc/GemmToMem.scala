package aegis.fixedfunc

import chisel3._
import chisel3.util._
import aegis._

/**
 * Fixed-function GEMM engine that operates out of the SHARED HBM3.
 *
 * Unlike the register-fed SystolicArray (loaded through its own command
 * channel), this engine reads its operand tiles from shared memory, computes a
 * real matrix product on a systolic-style datapath, and writes the result tile
 * back to shared memory -- so the CPU/GPU can seed it with data placed by any
 * other agent on the SoC.
 *
 * Addressing (tile = T, 512-bit lines, 32x 16-bit elements per line):
 *   - A tile: T x T 16-bit elements, flattened row-major, at baseAddr
 *   - B tile: T x T 16-bit elements, flattened row-major, at baseAddr + T*T/2 B
 *   - C tile: T x T 32-bit results,   flattened row-major, at baseAddr + T*T B
 *
 * A START command with the base address in `data` runs the whole pipeline.
 */
class GemmToMem(val tile: Int = 8) extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new Bundle {
      val opcode = UInt(8.W)
      val data = UInt(64.W) // base address of the work data
    }))
    val busy = Output(Bool())
    val mem = new MemInterface // 512-bit req/resp to the split/shared stack
  })

  val n2 = tile * tile
  val elemsPerLine = 512 / 16 // 32 16-bit elements per 512-bit line
  val linesOperand = n2 / elemsPerLine
  val elemsCPerLine = 512 / 32 // 16 32-bit elements per line
  val linesC = n2 / elemsCPerLine

  val s_idle :: s_fetchA :: s_fetchB :: s_calc :: s_store :: Nil = Enum(5)
  val state = RegInit(s_idle)

  val baseAddr = RegInit(0.U(64.W))

  val aTile = RegInit(VecInit(Seq.fill(n2)(0.U(16.W))))
  val bTile = RegInit(VecInit(Seq.fill(n2)(0.U(16.W))))
  val cTile = RegInit(VecInit(Seq.fill(n2)(0.U(32.W))))

  val lineCnt = RegInit(0.U(8.W)) // fetch/store line index
  val i = RegInit(0.U(8.W)) // compute row
  val k = RegInit(0.U(8.W)) // reduction step
  val j = RegInit(0.U(8.W)) // compute col

  io.cmd.ready := state === s_idle
  io.busy := state =/= s_idle

  // ---- address generation ----
  val aAddr = baseAddr + (lineCnt << 6)
  val bAddr = baseAddr + (linesOperand.U << 6) + (lineCnt << 6)
  val cAddr = baseAddr + ((2 * linesOperand).U << 6) + (lineCnt << 6)

  // ---- fetch slice: each line carries `elemsPerLine` contiguous elements ----
  val baseIdx = lineCnt * elemsPerLine.U
  for (e <- 0 until elemsPerLine) {
    when(state === s_fetchA) { aTile(baseIdx + e.U) := io.mem.resp.bits(e * 16 + 15, e * 16) }
    when(state === s_fetchB) { bTile(baseIdx + e.U) := io.mem.resp.bits(e * 16 + 15, e * 16) }
  }

  // ---- store slice: each line carries `elemsCPerLine` contiguous results ----
  val storeRow = Wire(Vec(elemsCPerLine, UInt(32.W)))
  for (e <- 0 until elemsCPerLine) {
    storeRow(e) := cTile(lineCnt * elemsCPerLine.U + e.U)
  }
  val storeData = Cat(storeRow.reverse)

  io.mem.req.valid := (state === s_fetchA) || (state === s_fetchB) || (state === s_store)
  io.mem.req.bits.addr := MuxLookup(state, 0.U)(Seq(
    s_fetchA -> aAddr,
    s_fetchB -> bAddr,
    s_store  -> cAddr))
  io.mem.req.bits.data := storeData
  io.mem.req.bits.isWrite := state === s_store
  io.mem.req.bits.size := 0.U
  io.mem.resp.ready := (state === s_fetchA) || (state === s_fetchB) || (state === s_store)

  // ---- systolic accumulation ----
  val elemIdx = i * tile.U + j
  val acc = cTile(elemIdx) + aTile(i * tile.U + k) * bTile(k * tile.U + j)

  switch(state) {
    is(s_idle) {
      when(io.cmd.fire) {
        baseAddr := io.cmd.bits.data
        lineCnt := 0.U
        state := s_fetchA
      }
    }
    is(s_fetchA) {
      when(io.mem.resp.fire) {
        lineCnt := lineCnt + 1.U
        when(lineCnt === (linesOperand - 1).U) { lineCnt := 0.U; state := s_fetchB }
      }
    }
    is(s_fetchB) {
      when(io.mem.resp.fire) {
        lineCnt := lineCnt + 1.U
        when(lineCnt === (linesOperand - 1).U) {
          lineCnt := 0.U
          i := 0.U
          k := 0.U
          j := 0.U
          state := s_calc
        }
      }
    }
    is(s_calc) {
      cTile(elemIdx) := acc
      when(j === (tile - 1).U) {
        j := 0.U
        when(k === (tile - 1).U) {
          k := 0.U
          when(i === (tile - 1).U) {
            lineCnt := 0.U
            state := s_store
          }.otherwise { i := i + 1.U }
        }.otherwise { k := k + 1.U }
      }.otherwise { j := j + 1.U }
    }
    is(s_store) {
      when(io.mem.resp.fire) {
        lineCnt := lineCnt + 1.U
        when(lineCnt === (linesC - 1).U) { state := s_idle }
      }
    }
  }
}