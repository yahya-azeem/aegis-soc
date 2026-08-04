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

  io.mem.cpu_req.valid := crossbar.io.mem_cpu_req.valid
  io.mem.cpu_req.bits := crossbar.io.mem_cpu_req.bits
  crossbar.io.mem_cpu_req.ready := io.mem.cpu_req.ready

  io.mem.gpu_req.valid := crossbar.io.mem_gpu_req.valid
  io.mem.gpu_req.bits := crossbar.io.mem_gpu_req.bits
  crossbar.io.mem_gpu_req.ready := io.mem.gpu_req.ready

  crossbar.io.mem_cpu_resp <> io.mem.cpu_resp
  crossbar.io.mem_gpu_resp <> io.mem.gpu_resp

  io.cpu_tl.a_ready := io.cpu_tl.a_valid
  io.cpu_tl.d_valid := io.cpu_tl.a_valid
  io.cpu_tl.d_bits := 0.U
  io.gpu_tl.a_ready := io.gpu_tl.a_valid
  io.gpu_tl.d_valid := io.gpu_tl.a_valid
  io.gpu_tl.d_bits := 0.U
}

class CrossbarMatrix extends Module {
  val io = IO(new Bundle {
    val cpu_ipi = Input(UInt(8.W))
    val gpu_irq = Input(Bool())
    val mem_cpu_req = Decoupled(new MemReq)
    val mem_gpu_req = Decoupled(new MemReq)
    val mem_cpu_resp = Flipped(Decoupled(UInt(512.W)))
    val mem_gpu_resp = Flipped(Decoupled(UInt(512.W)))
  })

  io.mem_cpu_req.valid := false.B
  io.mem_gpu_req.valid := false.B
  io.mem_cpu_req.bits := DontCare
  io.mem_gpu_req.bits := DontCare
  io.mem_cpu_resp := DontCare
  io.mem_gpu_resp := DontCare
}


