package aegis

import chisel3._
import chisel3.util._

/** Full AXI4 master bundle used between the SoC blocks and the HBM3 stack. */
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

/** Optional UART pins exposed on the SoC top for bring-up. */
class UARTIO extends Bundle {
  val tx = Output(Bool())
  val rx = Input(Bool())
}

/** 512-bit line request used on the shared-memory fabric. */
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

/** A single 512-bit request/response port into the shared stack. */
class MemInterface extends Bundle {
  val req = Decoupled(new MemReq)
  val resp = Flipped(Decoupled(UInt(512.W)))
}

/** The three requester ports presented to the split-prioritizer. */
class MemPort extends Bundle {
  val cpu_req = Flipped(Decoupled(new MemReq))
  val gpu_req = Flipped(Decoupled(new MemReq))
  val acc_req = Flipped(Decoupled(new MemReq))
  val cpu_resp = Decoupled(UInt(512.W))
  val gpu_resp = Decoupled(UInt(512.W))
  val acc_resp = Decoupled(UInt(512.W))
}
