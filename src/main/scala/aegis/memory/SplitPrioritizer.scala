package aegis.memory

import chisel3._
import chisel3.util._
import aegis._

object SplitMode {
  val mode = 0
  val gaming = 1
  val ai = 2
}

class HBM3Controller extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new MemReq))
    val resp = Decoupled(UInt(512.W))
    val open_page = Input(Bool())
    val pg_active = Output(Bool())
    val mem_axi = new AXIBundle(64, 512)
  })

  val s_idle :: s_aw :: s_w :: s_b :: s_ar :: s_r :: Nil = Enum(6)
  val state = RegInit(s_idle)

  val addr_r = Reg(UInt(64.W))
  val data_r = Reg(UInt(512.W))
  val rd_data = Reg(UInt(512.W))

  io.resp.valid := false.B
  io.resp.bits := rd_data
  io.req.ready := false.B

  io.mem_axi.AWID := 0.U
  io.mem_axi.AWVALID := state === s_aw
  io.mem_axi.AWADDR := addr_r
  io.mem_axi.AWLEN := 0.U
  io.mem_axi.AWSIZE := 6.U
  io.mem_axi.AWBURST := 0.U
  io.mem_axi.WVALID := state === s_w
  io.mem_axi.WDATA := data_r
  io.mem_axi.WLAST := state === s_w
  io.mem_axi.WSTRB := ~0.U(64.W)
  io.mem_axi.BREADY := state === s_b
  io.mem_axi.ARID := 0.U
  io.mem_axi.ARVALID := state === s_ar
  io.mem_axi.ARADDR := addr_r
  io.mem_axi.ARLEN := 0.U
  io.mem_axi.ARSIZE := 6.U
  io.mem_axi.ARBURST := 0.U
  io.mem_axi.RREADY := state === s_r

  io.pg_active := io.open_page && (state =/= s_idle)

  switch(state) {
    is(s_idle) {
      io.req.ready := true.B
      when(io.req.fire) {
        addr_r := io.req.bits.addr
        data_r := io.req.bits.data
        state := Mux(io.req.bits.isWrite, s_aw, s_ar)
      }
    }
    is(s_aw) {
      when(io.mem_axi.AWREADY) { state := s_w }
    }
    is(s_w) {
      when(io.mem_axi.WREADY) { state := s_b }
    }
    is(s_b) {
      when(io.mem_axi.BVALID) {
        io.resp.valid := true.B
        when(io.resp.ready) { state := s_idle }
      }
    }
    is(s_ar) {
      when(io.mem_axi.ARREADY) { state := s_r }
    }
    is(s_r) {
      io.resp.valid := true.B
      when(io.mem_axi.RVALID) { rd_data := io.mem_axi.RDATA }
      when(io.resp.ready) { state := s_idle }
    }
  }
}

class SplitPrioritizer(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val soc = new MemPort
    val mem_axi = new AXIBundle(config.axiAddrWidth, config.axiDataWidth)
    val mode = Input(UInt(2.W))
    val pg_active = Output(Bool())
  })

  val hbm = Module(new HBM3Controller)
  io.mem_axi <> hbm.io.mem_axi
  io.pg_active := hbm.io.pg_active

  val cpu_priority = io.mode === SplitMode.gaming.U
  hbm.io.open_page := io.mode === SplitMode.ai.U

  val rr = RegInit(false.B)

  val cpu_avail = io.soc.cpu_req.valid
  val gpu_avail = io.soc.gpu_req.valid

  val serve_cpu = cpu_avail && (!gpu_avail || cpu_priority || !rr)
  val serve_gpu = gpu_avail && !serve_cpu

  hbm.io.req.valid := serve_cpu || serve_gpu
  hbm.io.req.bits.addr := Mux(serve_cpu, io.soc.cpu_req.bits.addr, io.soc.gpu_req.bits.addr)
  hbm.io.req.bits.data := Mux(serve_cpu, io.soc.cpu_req.bits.data, io.soc.gpu_req.bits.data)
  hbm.io.req.bits.isWrite := Mux(serve_cpu, io.soc.cpu_req.bits.isWrite, io.soc.gpu_req.bits.isWrite)
  hbm.io.req.bits.size := 0.U

  io.soc.cpu_req.ready := hbm.io.req.ready && serve_cpu
  io.soc.gpu_req.ready := hbm.io.req.ready && serve_gpu

  val src_cpu = RegInit(true.B)
  when(hbm.io.req.fire) {
    src_cpu := serve_cpu
    rr := !serve_cpu
  }

  io.soc.cpu_resp.valid := hbm.io.resp.valid && src_cpu
  io.soc.gpu_resp.valid := hbm.io.resp.valid && !src_cpu
  io.soc.cpu_resp.bits := hbm.io.resp.bits
  io.soc.gpu_resp.bits := hbm.io.resp.bits
  hbm.io.resp.ready := Mux(src_cpu, io.soc.cpu_resp.ready, io.soc.gpu_resp.ready)
}