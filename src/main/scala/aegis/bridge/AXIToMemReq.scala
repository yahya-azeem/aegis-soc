package aegis.bridge

import chisel3._
import chisel3.util._
import aegis._

/**
 * AXI4 slave adapter that bridges a 512-bit single-beat master (e.g. the GPU
 * L2 cache) onto the SoC memory fabric (split-prioritizer -> HBM3 stack).
 *
 * It is the GPU-side counterpart of CoreMemToHBM (which adapts the RV32I CPU's
 * word port): it accepts an AXI read/write for one 512-bit line, drives the
 * 512-bit MemRequest into shared HBM3, and returns the data read back on the
 * AXI read channel or an AXI write response.
 */
class AXIToMemReq(
  axiAddrWidth: Int = 64,
  dataWidth:    Int = 512,
) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AXIBundle(axiAddrWidth, dataWidth)) // master is the I/O master
    val mem = new MemInterface // req (out) to split-prioritizer, resp (in) from stack
  })

  val s_idle :: s_wwait :: s_wreq :: s_wwrsp :: s_wrespo :: s_areq :: s_aresp :: s_rvalid :: Nil = Enum(8)
  val state = RegInit(s_idle)

  val addr = Reg(UInt(axiAddrWidth.W))
  val wdata = Reg(UInt(dataWidth.W))
  val rdata = Reg(UInt(dataWidth.W))

  io.axi.AWREADY := false.B
  io.axi.WREADY := false.B
  io.axi.ARREADY := false.B
  io.axi.BVALID := false.B
  io.axi.BRESP := 0.U
  io.axi.BID := 0.U
  io.axi.RVALID := false.B
  io.axi.RDATA := 0.U
  io.axi.RRESP := 0.U
  io.axi.RLAST := false.B
  io.axi.RID := 0.U

  io.mem.req.valid := false.B
  io.mem.req.bits.addr := addr
  io.mem.req.bits.data := wdata
  io.mem.req.bits.isWrite := false.B
  io.mem.req.bits.size := 6.U
  io.mem.resp.ready := false.B

  switch(state) {
    is(s_idle) {
      when(io.axi.AWVALID) {
        addr := io.axi.AWADDR
        io.axi.AWREADY := true.B
        state := s_wwait
      }.elsewhen(io.axi.ARVALID) {
        addr := io.axi.ARADDR
        io.axi.ARREADY := true.B
        state := s_areq
      }
    }
    is(s_wwait) {
      io.axi.WREADY := true.B
      when(io.axi.WVALID) {
        wdata := io.axi.WDATA
        state := s_wreq
      }
    }
    is(s_wreq) {
      io.mem.req.valid := true.B
      io.mem.req.bits.isWrite := true.B
      when(io.mem.req.fire) { state := s_wwrsp }
    }
    is(s_wwrsp) {
      io.mem.resp.ready := true.B
      when(io.mem.resp.valid) { state := s_wrespo }
    }
    is(s_wrespo) {
      io.axi.BVALID := true.B
      when(io.axi.BREADY) { state := s_idle }
    }
    is(s_areq) {
      io.mem.req.valid := true.B
      io.mem.req.bits.data := 0.U
      when(io.mem.req.fire) { state := s_aresp }
    }
    is(s_aresp) {
      io.mem.resp.ready := true.B
      when(io.mem.resp.valid) {
        rdata := io.mem.resp.bits
        state := s_rvalid
      }
    }
    is(s_rvalid) {
      io.axi.RVALID := true.B
      io.axi.RDATA := rdata
      io.axi.RLAST := true.B
      when(io.axi.RREADY) { state := s_idle }
    }
  }
}