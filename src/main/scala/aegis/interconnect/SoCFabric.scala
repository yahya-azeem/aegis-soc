package aegis.interconnect

import chisel3._
import chisel3.util._
import aegis._

class SoCFabric(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val cpu = Flipped(new CPUIO)
    val gpu = Flipped(new GPUIO)
    val mem = Flipped(new MemPort)
    val cpu_tl = new TileLinkBundle(config.tlBeatBytes)
    val gpu_tl = new TileLinkBundle(config.tlBeatBytes)
  })

  val crossbar = Module(new CrossbarMatrix)

  crossbar.io.cpu_ipi := io.cpu.ipi
  crossbar.io.gpu_irq := io.gpu.irq

  io.mem.cpu_req.valid := crossbar.io.mem_cpu_req.valid
  io.mem.cpu_req.bits := crossbar.io.mem_cpu_req.bits
  crossbar.io.mem_cpu_req.ready := io.mem.cpu_req.ready

  io.mem.gpu_req.valid := crossbar.io.mem_gpu_req.valid
  io.mem.gpu_req.bits := crossbar.io.mem_gpu_req.bits
  crossbar.io.mem_gpu_req.ready := io.mem.gpu_req.ready

  crossbar.io.mem_cpu_resp <> io.mem.cpu_resp
  crossbar.io.mem_gpu_resp <> io.mem.gpu_resp

  io.cpu_tl.a_valid := crossbar.io.cpu_tl_a_valid
  io.cpu_tl.a_bits := crossbar.io.cpu_tl_a_bits
  crossbar.io.cpu_tl_a_ready := io.cpu_tl.a_ready

  io.gpu_tl.a_valid := crossbar.io.gpu_tl_a_valid
  io.gpu_tl.a_bits := crossbar.io.gpu_tl_a_bits
  crossbar.io.gpu_tl_a_ready := io.gpu_tl.a_ready

  crossbar.io.cpu_tl_d_valid := io.cpu_tl.d_valid
  crossbar.io.cpu_tl_d_bits := io.cpu_tl.d_bits
  io.cpu_tl.d_ready := crossbar.io.cpu_tl_d_ready

  crossbar.io.gpu_tl_d_valid := io.gpu_tl.d_valid
  crossbar.io.gpu_tl_d_bits := io.gpu_tl.d_bits
  io.gpu_tl.d_ready := crossbar.io.gpu_tl_d_ready
}

class CrossbarMatrix extends Module {
  val io = IO(new Bundle {
    val cpu_ipi = Input(UInt(8.W))
    val gpu_irq = Input(Bool())
    val mem_cpu_req = Decoupled(new MemReq)
    val mem_gpu_req = Decoupled(new MemReq)
    val mem_cpu_resp = Flipped(Decoupled(UInt(512.W)))
    val mem_gpu_resp = Flipped(Decoupled(UInt(512.W)))
    val cpu_tl_a_valid = Output(Bool())
    val cpu_tl_a_bits = Output(UInt(64.W))
    val cpu_tl_a_ready = Input(Bool())
    val gpu_tl_a_valid = Output(Bool())
    val gpu_tl_a_bits = Output(UInt(64.W))
    val gpu_tl_a_ready = Input(Bool())
    val cpu_tl_d_valid = Input(Bool())
    val cpu_tl_d_bits = Input(UInt(512.W))
    val cpu_tl_d_ready = Output(Bool())
    val gpu_tl_d_valid = Input(Bool())
    val gpu_tl_d_bits = Input(UInt(512.W))
    val gpu_tl_d_ready = Output(Bool())
  })

  io.mem_cpu_req.valid := false.B
  io.mem_gpu_req.valid := false.B
  io.mem_cpu_req.bits := DontCare
  io.mem_gpu_req.bits := DontCare

  io.cpu_tl_a_valid := false.B
  io.cpu_tl_a_bits := 0.U
  io.cpu_tl_d_ready := false.B
  io.gpu_tl_a_valid := false.B
  io.gpu_tl_a_bits := 0.U
  io.gpu_tl_d_ready := false.B
}


