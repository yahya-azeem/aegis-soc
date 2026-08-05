package aegis.bridge

import chisel3._
import chisel3.util._
import aegis._

class AXITileLinkBridge(
  tlBeatBytes: Int = 64,
  axiDataWidth: Int = 512,
) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AXIBundle(64, axiDataWidth))
    val tl = new TileLinkBundle(tlBeatBytes)
  })

  val s_idle :: s_wait_w :: s_send_a :: s_wait_d :: s_bresp :: s_rresp :: Nil = Enum(6)
  val state = RegInit(s_idle)

  val addr_reg = Reg(UInt(64.W))
  val rdata_reg = Reg(UInt(axiDataWidth.W))
  val is_write = Reg(Bool())

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

  io.tl.a_valid := false.B
  io.tl.a_bits := 0.U
  io.tl.d_ready := false.B

  switch(state) {
    is(s_idle) {
      when(io.axi.AWVALID) {
        addr_reg := io.axi.AWADDR
        is_write := true.B
        io.axi.AWREADY := true.B
        state := s_wait_w
      }.elsewhen(io.axi.ARVALID) {
        addr_reg := io.axi.ARADDR
        is_write := false.B
        io.axi.ARREADY := true.B
        state := s_send_a
      }
    }
    is(s_wait_w) {
      io.axi.WREADY := true.B
      when(io.axi.WVALID) {
        state := s_send_a
      }
    }
    is(s_send_a) {
      io.tl.a_valid := true.B
      io.tl.a_bits := addr_reg
      when(io.tl.a_ready) {
        state := Mux(is_write, s_bresp, s_wait_d)
      }
    }
    is(s_wait_d) {
      io.tl.d_ready := true.B
      when(io.tl.d_valid) {
        rdata_reg := io.tl.d_bits
        state := s_rresp
      }
    }
    is(s_bresp) {
      io.axi.BVALID := true.B
      io.axi.BRESP := 0.U
      when(io.axi.BREADY) {
        state := s_idle
      }
    }
    is(s_rresp) {
      io.axi.RVALID := true.B
      io.axi.RDATA := rdata_reg
      io.axi.RRESP := 0.U
      io.axi.RLAST := true.B
      when(io.axi.RREADY) {
        state := s_idle
      }
    }
  }
}
