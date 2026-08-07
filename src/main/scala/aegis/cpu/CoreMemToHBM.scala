package aegis.cpu

import chisel3._
import chisel3.util._
import aegis._

/**
 * Adapts the CPU core's 32-bit word port to the SoC's 512-bit HBM line
 * interface. A 512-bit line holds 16 32-bit words; loads extract the requested
 * lane, stores do a read-modify-write of the whole line. One request at a time
 * in lock-step with the in-order core's single-outstanding semantics.
 */
class CoreMemToHBM extends Module {
  val io = IO(new Bundle {
    val word = Flipped(new WordMemPort)
    val hbm  = new MemInterface // req -> splitter/stack, resp <- stack (512-bit)
  })

  val s_idle :: s_rdreq :: s_rdresp :: s_wrreq :: s_wrresp :: s_rsp :: Nil = Enum(6)
  val state = RegInit(s_idle)

  val addrReg = RegInit(0.U(64.W))
  val dataReg = RegInit(0.U(32.W))
  val isWrReg = RegInit(false.B)
  val sizeReg = RegInit(0.U(2.W))
  val lineReg = RegInit(0.U(512.W))

  val offReg = RegInit(0.U(6.W))
  val pos = offReg << 3 // bit offset within the line

  // ---- load field extraction (right-aligned) ----
  val shiftedLine = lineReg >> pos
  val field = Mux(sizeReg === 0.U, shiftedLine(31, 0),
              Mux(sizeReg === 1.U, shiftedLine(15, 0), shiftedLine(7, 0)))

  // ---- store merge (read-modify-write) ----
  val fMask32 = Mux(sizeReg === 0.U, "hffffffff".U(32.W),
                Mux(sizeReg === 1.U, "hffff".U(32.W), "hff".U(32.W)))
  val maskAt = Cat(0.U(480.W), fMask32) << pos
  val dataAt = Cat(0.U(480.W), dataReg) << pos
  val merged = (lineReg & ~maskAt) | dataAt

  io.word.req.ready := state === s_idle

  io.hbm.req.valid := (state === s_rdreq) || (state === s_wrreq)
  io.hbm.req.bits.addr := Cat(addrReg(63, 6), 0.U(6.W))
  io.hbm.req.bits.data := Mux(state === s_wrreq, merged, 0.U)
  io.hbm.req.bits.isWrite := state === s_wrreq
  io.hbm.req.bits.size := 0.U
  io.hbm.resp.ready := (state === s_rdresp) || (state === s_wrresp)

  io.word.resp.valid := state === s_rsp
  io.word.resp.bits := Mux(isWrReg, 0.U(32.W), field)

  switch(state) {
    is(s_idle) {
      when(io.word.req.fire) {
        addrReg := io.word.req.bits.addr
        dataReg := io.word.req.bits.data
        isWrReg := io.word.req.bits.isWrite
        sizeReg := io.word.req.bits.size
        offReg := io.word.req.bits.addr(5, 0)
        state := s_rdreq
      }
    }
    is(s_rdreq) {
      when(io.hbm.req.fire) { state := s_rdresp }
    }
    is(s_rdresp) {
      when(io.hbm.resp.fire) {
        lineReg := io.hbm.resp.bits
        state := Mux(isWrReg, s_wrreq, s_rsp)
      }
    }
    is(s_wrreq) {
      when(io.hbm.req.fire) { state := s_wrresp }
    }
    is(s_wrresp) {
      when(io.hbm.resp.fire) { state := s_rsp }
    }
    is(s_rsp) {
      when(io.word.resp.fire) { state := s_idle }
    }
  }
}
