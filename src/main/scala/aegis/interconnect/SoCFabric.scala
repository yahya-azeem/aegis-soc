package aegis.interconnect

import chisel3._
import chisel3.util._
import aegis._

class SoCFabric(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val cpu = Flipped(new CPUIO)
    val gpu = Flipped(new GPUIO)
    val mem = Flipped(new MemPort)
    val cpu_tl = Flipped(new TileLinkBundle(config.tlBeatBytes))
    val gpu_tl = Flipped(new TileLinkBundle(config.tlBeatBytes))
  })

  val crossbar = Module(new CrossbarMatrix)

  crossbar.io.cpu_ipi := io.cpu.ipi
  crossbar.io.gpu_irq := io.gpu.irq

  crossbar.io.cpu_a_valid := io.cpu_tl.a_valid
  crossbar.io.cpu_a_bits := io.cpu_tl.a_bits
  io.cpu_tl.a_ready := crossbar.io.cpu_a_ready
  crossbar.io.cpu_d_ready := io.cpu_tl.d_ready
  io.cpu_tl.d_valid := crossbar.io.cpu_d_valid
  io.cpu_tl.d_bits := crossbar.io.cpu_d_bits

  crossbar.io.gpu_a_valid := io.gpu_tl.a_valid
  crossbar.io.gpu_a_bits := io.gpu_tl.a_bits
  io.gpu_tl.a_ready := crossbar.io.gpu_a_ready
  crossbar.io.gpu_d_ready := io.gpu_tl.d_ready
  io.gpu_tl.d_valid := crossbar.io.gpu_d_valid
  io.gpu_tl.d_bits := crossbar.io.gpu_d_bits

  io.mem.cpu_req.valid := crossbar.io.mem_cpu_req.valid
  io.mem.cpu_req.bits := crossbar.io.mem_cpu_req.bits
  crossbar.io.mem_cpu_req.ready := io.mem.cpu_req.ready

  io.mem.gpu_req.valid := crossbar.io.mem_gpu_req.valid
  io.mem.gpu_req.bits := crossbar.io.mem_gpu_req.bits
  crossbar.io.mem_gpu_req.ready := io.mem.gpu_req.ready

  crossbar.io.mem_cpu_resp <> io.mem.cpu_resp
  crossbar.io.mem_gpu_resp <> io.mem.gpu_resp
}

class CrossbarMatrix extends Module {
  val io = IO(new Bundle {
    val cpu_ipi = Input(UInt(8.W))
    val gpu_irq = Input(Bool())

    val cpu_a_valid = Input(Bool())
    val cpu_a_bits = Input(UInt(64.W))
    val cpu_a_ready = Output(Bool())
    val cpu_d_valid = Output(Bool())
    val cpu_d_bits = Output(UInt(512.W))
    val cpu_d_ready = Input(Bool())

    val gpu_a_valid = Input(Bool())
    val gpu_a_bits = Input(UInt(64.W))
    val gpu_a_ready = Output(Bool())
    val gpu_d_valid = Output(Bool())
    val gpu_d_bits = Output(UInt(512.W))
    val gpu_d_ready = Input(Bool())

    val mem_cpu_req = Decoupled(new MemReq)
    val mem_gpu_req = Decoupled(new MemReq)
    val mem_cpu_resp = Flipped(Decoupled(UInt(512.W)))
    val mem_gpu_resp = Flipped(Decoupled(UInt(512.W)))
  })

  val cpu_pending = RegInit(false.B)
  val cpu_addr = Reg(UInt(64.W))
  val gpu_pending = RegInit(false.B)
  val gpu_addr = Reg(UInt(64.W))

  io.cpu_a_ready := !cpu_pending
  io.gpu_a_ready := !gpu_pending

  when(io.cpu_a_valid && io.cpu_a_ready) {
    cpu_pending := true.B
    cpu_addr := io.cpu_a_bits
  }
  when(io.gpu_a_valid && io.gpu_a_ready) {
    gpu_pending := true.B
    gpu_addr := io.gpu_a_bits
  }

  io.mem_cpu_req.valid := cpu_pending
  io.mem_cpu_req.bits.addr := cpu_addr
  io.mem_cpu_req.bits.data := 0.U
  io.mem_cpu_req.bits.isWrite := false.B
  io.mem_cpu_req.bits.size := 0.U

  io.mem_gpu_req.valid := gpu_pending
  io.mem_gpu_req.bits.addr := gpu_addr
  io.mem_gpu_req.bits.data := 0.U
  io.mem_gpu_req.bits.isWrite := false.B
  io.mem_gpu_req.bits.size := 0.U

  io.cpu_d_valid := io.mem_cpu_resp.valid
  io.cpu_d_bits := io.mem_cpu_resp.bits
  io.mem_cpu_resp.ready := io.cpu_d_ready
  when(io.mem_cpu_resp.fire) { cpu_pending := false.B }

  io.gpu_d_valid := io.mem_gpu_resp.valid
  io.gpu_d_bits := io.mem_gpu_resp.bits
  io.mem_gpu_resp.ready := io.gpu_d_ready
  when(io.mem_gpu_resp.fire) { gpu_pending := false.B }
}