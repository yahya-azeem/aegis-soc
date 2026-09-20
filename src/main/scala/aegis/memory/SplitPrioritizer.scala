package aegis.memory

import chisel3._
import chisel3.util._
import aegis._

/** Memory-QoS policy selected on the `mode` pin. */
object SplitMode {
  /** Fair round-robin across the CPU, GPU and accelerator ports. */
  val mode = 0
  /** Latency-sensitive: the CPU gets strict priority over the GPU/accelerator. */
  val gaming = 1
  /** CPU priority plus the HBM3 open-page (row-buffer) policy. */
  val ai = 2
}

/**
 * Arbitration front-end for the single shared HBM3 stack.
 *
 * One [[HBM3Stack]] backs three requesters: the CPU lane, the GPU lane and the
 * fixed-function / accelerator lane. The policy depends on `io.mode`:
 *
 *   - `mode` (0): fair round-robin across all three ports.
 *   - `gaming` (1): the CPU wins whenever it requests; GPU and accelerator
 *     round-robin the remaining cycles.
 *   - `ai` (2): CPU priority as in `gaming`, and the stack keeps DRAM pages
 *     open to maximise row-buffer hits.
 *
 * Responses are tagged with the requester that was granted, so data always
 * returns on the port that issued the request.
 */
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

  val cpu_priority = (io.mode === SplitMode.gaming.U) || (io.mode === SplitMode.ai.U)
  hbm.io.open_page := io.mode === SplitMode.ai.U

  val cpu_avail = io.soc.cpu_req.valid
  val gpu_avail = io.soc.gpu_req.valid
  val acc_avail = io.soc.acc_req.valid
  val any = cpu_avail || gpu_avail || acc_avail

  // Rotating-priority round-robin: `rr` holds the port served last, so the
  // next grant goes to the first requester after it (wrapping around).
  val rr = RegInit(0.U(2.W))
  val reqs = VecInit(cpu_avail, gpu_avail, acc_avail)
  val higher = VecInit((0 until 3).map(i => reqs(i) && (i.U > rr)))
  val lower = VecInit((0 until 3).map(i => reqs(i) && (i.U <= rr)))
  val fairSel = Mux(higher.asUInt.orR, PriorityEncoder(higher), PriorityEncoder(lower))

  // In the CPU-priority modes the CPU is granted unconditionally when present.
  val sel = Mux(cpu_priority && cpu_avail, 0.U, fairSel)

  val serve_cpu = any && (sel === 0.U)
  val serve_gpu = any && (sel === 1.U)
  val serve_acc = any && (sel === 2.U)

  hbm.io.req.valid := any
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
    rr := sel
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
