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

  val s_idle :: s_read :: s_write :: s_resp :: Nil = Enum(4)
  val state = RegInit(s_idle)

  val addr_reg = Reg(UInt(64.W))
  val data_reg = Reg(UInt(axiDataWidth.W))
  val is_write = Reg(Bool())

  switch(state) {
    is(s_idle) {
      when(io.axi.AWVALID) {
        addr_reg := io.axi.AWADDR
        is_write := true.B
        state := s_write
        io.axi.AWREADY := true.B
      }.elsewhen(io.axi.ARVALID) {
        addr_reg := io.axi.ARADDR
        is_write := false.B
        state := s_read
        io.axi.ARREADY := true.B
      }
    }
    is(s_write) {
      when(io.axi.WVALID) {
        data_reg := io.axi.WDATA
        io.axi.WREADY := true.B
        state := s_resp
      }
    }
    is(s_read) {
      io.tl.a_valid := true.B
      io.tl.a_bits := addr_reg
      when(io.tl.a_ready) {
        state := s_resp
      }
    }
    is(s_resp) {
      when(is_write) {
        io.axi.BVALID := true.B
        io.axi.BRESP := 0.U
        when(io.axi.BREADY) {
          state := s_idle
        }
      }.otherwise {
        io.axi.RVALID := true.B
        io.axi.RDATA := data_reg
        io.axi.RRESP := 0.U
        io.axi.RLAST := true.B
        when(io.axi.RREADY) {
          state := s_idle
        }
      }
    }
  }

  io.tl.a_valid := false.B
  io.tl.a_bits := 0.U
  io.tl.d_ready := false.B
}

class TileLinkBundle(val beatBytes: Int) extends Bundle {
  val a_valid = Output(Bool())
  val a_ready = Input(Bool())
  val a_bits = Output(UInt(64.W))
  val d_valid = Input(Bool())
  val d_ready = Output(Bool())
  val d_bits = Input(UInt((beatBytes * 8).W))
}
