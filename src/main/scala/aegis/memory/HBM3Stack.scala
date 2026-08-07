package aegis.memory

import chisel3._
import chisel3.util._
import aegis._

/**
 * Real, self-contained HBM3-style memory stack.
 *
 * Unlike the earlier stub that merely forwarded requests out to an external AXI
 * test port (and echoed whatever data the bench poked back), this model owns an
 * actual banked DRAM array and serves read/write requests itself:
 *
 *   - a flat word array bit-sliced into banks x rows x columns, held in
 *     on-model registers so written data physically persists;
 *   - a linear address -> {bank,row,column} decode;
 *   - a per-bank open-row (page) table with activate / precharge timing
 *     (tACT / tPRE) so the open-page policy is honoured;
 *   - a refresh counter that periodically walks the banks to keep them alive.
 *
 * `io.mem` is retained purely as an observability mirror of the current
 * transaction on the external HBM3 PHY interface (useful for co-simulation /
 * silicon bring-up). Completion never depends on that port: reads always come
 * from the internal array, so the subsystem is fully self-sufficient.
 */
class HBM3Stack(
  val bankBits: Int = 2,
  val colBits:  Int = 3,
  val rowBits:  Int = 3,
) extends Module {
  val dataWidth = 512
  val offBits   = 6 // 64-byte cache line / 512-bit word
  val nBanks    = 1 << bankBits
  val nCols     = 1 << colBits
  val nRows     = 1 << rowBits
  val bob       = log2Ceil(nCols)

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new MemReq))
    val resp = Decoupled(UInt(dataWidth.W))
    val open_page = Input(Bool())
    val pg_active = Output(Bool())
    val mem = new AXIBundle(64, dataWidth)
  })

  // internal DRAM array: linear index = (bank*nRows + row)*nCols + col
  val cells = RegInit(VecInit(Seq.fill(nBanks * nRows * nCols)(0.U(dataWidth.W))))

  // per-bank page table
  val pageOpen = RegInit(VecInit(Seq.fill(nBanks)(false.B)))
  val pageRow  = RegInit(VecInit(Seq.fill(nBanks)(0.U(rowBits.W))))

  val tACT = 2
  val tPRE = 2
  val tREF = 16

  // captured request
  val rAddr = Reg(UInt(64.W))
  val rData = Reg(UInt(dataWidth.W))
  val rW    = Reg(Bool())

  val rLine = rAddr >> offBits
  val rCol  = rLine(bob - 1, 0)
  val rBank = rLine(bob + bankBits - 1, bob)
  val rRow  = rLine(bob + bankBits + rowBits - 1, bob + bankBits)
  val rIdx  = Cat(rBank, rRow, rCol)

  // decode from the live request (used for the state transition on accept)
  val qLine  = io.req.bits.addr >> offBits
  val qBank  = qLine(bob + bankBits - 1, bob)
  val qRow   = qLine(bob + bankBits + rowBits - 1, bob + bankBits)
  val qHit   = pageOpen(qBank) && (pageRow(qBank) === qRow)

  val s_idle :: s_prech :: s_act :: s_cmd :: s_ref :: Nil = Enum(5)
  val state = RegInit(s_idle)
  val cnt   = RegInit(0.U(5.W))
  val refCnt = RegInit(tREF.U)

  io.req.ready := state === s_idle && refCnt =/= 0.U

  // response
  io.resp.valid := state === s_cmd
  io.resp.bits := Mux(rW, 0.U, cells(rIdx))

  // HBM3 PHY observability mirror
  io.mem.AWID := 0.U
  io.mem.AWADDR := rAddr
  io.mem.AWLEN := 0.U
  io.mem.AWSIZE := 6.U
  io.mem.AWBURST := 0.U
  io.mem.AWVALID := state === s_cmd && rW
  io.mem.WDATA := rData
  io.mem.WSTRB := ~0.U((dataWidth / 8).W)
  io.mem.WLAST := true.B
  io.mem.WVALID := state === s_cmd && rW
  io.mem.BREADY := true.B
  io.mem.ARID := 0.U
  io.mem.ARADDR := rAddr
  io.mem.ARLEN := 0.U
  io.mem.ARSIZE := 6.U
  io.mem.ARBURST := 0.U
  io.mem.ARVALID := state === s_cmd && !rW
  io.mem.RREADY := true.B

  io.pg_active := pageOpen.reduce(_ || _)

  when(refCnt =/= 0.U && state === s_idle) { refCnt := refCnt - 1.U }

  switch(state) {
    is(s_idle) {
      when(refCnt === 0.U) {
        state := s_ref
        cnt := 0.U
      }.elsewhen(io.req.fire) {
        rAddr := io.req.bits.addr
        rData := io.req.bits.data
        rW := io.req.bits.isWrite
        when(qHit) {
          state := s_cmd
        }.elsewhen(pageOpen(qBank)) {
          state := s_prech
          cnt := 0.U
        }.otherwise {
          state := s_act
          cnt := 0.U
        }
      }
    }
    is(s_prech) {
      cnt := cnt + 1.U
      when(cnt === (tPRE - 1).U) {
        pageOpen(rBank) := false.B
        cnt := 0.U
        state := s_act
      }
    }
    is(s_act) {
      cnt := cnt + 1.U
      when(cnt === (tACT - 1).U) {
        pageOpen(rBank) := true.B
        pageRow(rBank) := rRow
        cnt := 0.U
        state := s_cmd
      }
    }
    is(s_cmd) {
      when(rW) { cells(rIdx) := rData }
      when(!io.open_page) { pageOpen(rBank) := false.B }
      when(io.resp.ready) { state := s_idle }
    }
    is(s_ref) {
      cnt := cnt + 1.U
      when(cnt === tREF.U) {
        cnt := 0.U
        refCnt := tREF.U
        state := s_idle
      }
    }
  }
}
