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
  val acc_avail = io.soc.acc_req.valid

  // CPU always has priority; GPU and accelerator share the remaining
  // bandwidth round-robin so neither stalls the memory stack.
  val serve_cpu = cpu_avail && (!(gpu_avail || acc_avail) || cpu_priority || !rr)
  val serve_gpu = gpu_avail && !serve_cpu
  val serve_acc = acc_avail && !serve_cpu && !serve_gpu

  hbm.io.req.valid := serve_cpu || serve_gpu || serve_acc
  hbm.io.req.bits.addr := MuxCase(0.U, Seq(
    serve_cpu -> io.soc.cpu_req.bits.addr,
    serve_gpu -> io.soc.gpu_req.bits.addr,
    serve_acc -> io.soc.acc_req.bits.addr))
  hbm.io.req.bits.data := MuxCase(0.U, Seq(
    serve_cpu -> io.soc.cpu_req.bits.data,
    serve_gpu -> io.soc.gpu_req.bits.data,
    serve_acc -> io.soc.acc_req.bits.data))
  hbm.io.req.bits.isWrite := MuxCase(false.B, Seq(
    serve_cpu -> io.soc.cpu_req.bits.isWrite,
    serve_gpu -> io.soc.gpu_req.bits.isWrite,
    serve_acc -> io.soc.acc_req.bits.isWrite))
  hbm.io.req.bits.size := 0.U

  io.soc.cpu_req.ready := hbm.io.req.ready && serve_cpu
  io.soc.gpu_req.ready := hbm.io.req.ready && serve_gpu
  io.soc.acc_req.ready := hbm.io.req.ready && serve_acc

  val src_cpu = RegInit(true.B)
  val src_gpu = RegInit(false.B)
  when(hbm.io.req.fire) {
    src_cpu := serve_cpu
    src_gpu := serve_gpu
    rr := serve_gpu
  }

  io.soc.cpu_resp.valid := hbm.io.resp.valid && src_cpu
  io.soc.gpu_resp.valid := hbm.io.resp.valid && src_gpu
  io.soc.acc_resp.valid := hbm.io.resp.valid && !src_cpu && !src_gpu
  io.soc.cpu_resp.bits := hbm.io.resp.bits
  io.soc.gpu_resp.bits := hbm.io.resp.bits
  io.soc.acc_resp.bits := hbm.io.resp.bits
  hbm.io.resp.ready := Mux(src_cpu, io.soc.cpu_resp.ready,
                        Mux(src_gpu, io.soc.gpu_resp.ready, io.soc.acc_resp.ready))
}