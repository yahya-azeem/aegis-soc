package aegis

import chisel3._
import chisel3.util._

class AXIBundle(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val AWID = Output(UInt(8.W))
  val AWADDR = Output(UInt(addrWidth.W))
  val AWLEN = Output(UInt(8.W))
  val AWSIZE = Output(UInt(3.W))
  val AWBURST = Output(UInt(2.W))
  val AWVALID = Output(Bool())
  val AWREADY = Input(Bool())
  val WDATA = Output(UInt(dataWidth.W))
  val WSTRB = Output(UInt((dataWidth / 8).W))
  val WLAST = Output(Bool())
  val WVALID = Output(Bool())
  val WREADY = Input(Bool())
  val BID = Input(UInt(8.W))
  val BRESP = Input(UInt(2.W))
  val BVALID = Input(Bool())
  val BREADY = Output(Bool())
  val ARID = Output(UInt(8.W))
  val ARADDR = Output(UInt(addrWidth.W))
  val ARLEN = Output(UInt(8.W))
  val ARSIZE = Output(UInt(3.W))
  val ARBURST = Output(UInt(2.W))
  val ARVALID = Output(Bool())
  val ARREADY = Input(Bool())
  val RID = Input(UInt(8.W))
  val RDATA = Input(UInt(dataWidth.W))
  val RRESP = Input(UInt(2.W))
  val RLAST = Input(Bool())
  val RVALID = Input(Bool())
  val RREADY = Output(Bool())
}

class TileLinkBundle(val beatBytes: Int) extends Bundle {
  val a_valid = Output(Bool())
  val a_ready = Input(Bool())
  val a_bits = Output(UInt(64.W))
  val d_valid = Input(Bool())
  val d_ready = Output(Bool())
  val d_bits = Input(UInt((beatBytes * 8).W))
}

class UARTIO extends Bundle {
  val tx = Output(Bool())
  val rx = Input(Bool())
}

class MemReq extends Bundle {
  val addr = UInt(64.W)
  val data = UInt(512.W)
  val isWrite = Bool()
  val size = UInt(3.W)
}

/** 32-bit word-level memory request used by the CPU core's data port. */
class WordMemReq extends Bundle {
  val addr = UInt(64.W)
  val data = UInt(32.W)
  val isWrite = Bool()
  val size = UInt(2.W) // 0 = word, 1 = half, 2 = byte
}

class WordMemPort extends Bundle {
  val req = Decoupled(new WordMemReq)
  val resp = Flipped(Decoupled(UInt(32.W)))
}

class MemInterface extends Bundle {
  val req = Decoupled(new MemReq)
  val resp = Flipped(Decoupled(UInt(512.W)))
}

class MemPort extends Bundle {
  val cpu_req = Flipped(Decoupled(new MemReq))
  val gpu_req = Flipped(Decoupled(new MemReq))
  val cpu_resp = Decoupled(UInt(512.W))
  val gpu_resp = Decoupled(UInt(512.W))
}

class FixedFuncUnit extends Bundle {
  val cmd = Flipped(Decoupled(new Bundle {
    val opcode = UInt(8.W)
    val data = UInt(512.W)
  }))
  val resp = Decoupled(new Bundle {
    val data = UInt(512.W)
  })
}

class CPUIO extends Bundle {
  val ipi = Output(UInt(8.W))
  val msi = Output(UInt(8.W))
}

class GPUIO extends Bundle {
  val irq = Output(Bool())
}

class L2Interface extends Bundle {
  val addr = Output(UInt(64.W))
  val data = Output(UInt(512.W))
  val valid = Output(Bool())
  val ready = Input(Bool())
}

class CrossbarInterface extends Bundle {
  val addr = Output(UInt(64.W))
  val data = Output(UInt(512.W))
  val valid = Output(Bool())
  val ready = Input(Bool())
  val source = Output(UInt(4.W))
}
