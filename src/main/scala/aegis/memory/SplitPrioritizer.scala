package aegis.memory

import chisel3._
import chisel3.util._
import aegis._

object SplitMode {
  val mode = 0
  val gaming = 1
  val ai = 2
}

class SplitPrioritizer(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val soc = new MemPort
    val mem_axi = new AXIBundle(config.axiAddrWidth, config.axiDataWidth)
    val mode = Input(UInt(2.W))
    val pg_active = Output(Bool())
  })

  val hbm = Module(new HBM3Stack)
  io.mem_axi <> hbm.io.mem
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