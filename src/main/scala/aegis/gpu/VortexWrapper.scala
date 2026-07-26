package aegis.gpu

import chisel3._
import chisel3.util._
import aegis._

class VortexWrapper(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val soc = new GPUIO
    val axi = new AXIBundle(config.axiAddrWidth, config.axiDataWidth)
  })

  val cfg = config.gpu

  val clusters = Seq.fill(cfg.nClusters) { Module(new VortexCluster) }

  val l2_cache = Module(new GPUL2Cache)

  for (i <- clusters.indices) {
    clusters(i).io.mem <> l2_cache.io.cluster(i)
  }

  l2_cache.io.mem <> io.axi

  io.soc.irq := clusters.map(_.io.irq).reduce(_ || _)
}

class VortexCluster extends Module {
  val io = IO(new Bundle {
    val mem = Flipped(new MemInterface)
    val irq = Output(Bool())
  })

  val cores = IO(new Bundle {
    val scalar = new ScalarPipeline
    val vector = new VectorPipeline
  })

  io.mem := DontCare
  io.irq := false.B
}

class ScalarPipeline extends Bundle {
  val pc = Output(UInt(64.W))
  val instr = Output(UInt(32.W))
  val valid = Output(Bool())
}

class VectorPipeline extends Bundle {
  val vaddr = Output(UInt(64.W))
  val vdata = Output(UInt(1024.W))
  val valid = Output(Bool())
}

class GPUIO extends Bundle {
  val irq = Output(Bool())
}

class GPUL2Cache extends Module {
  val io = IO(new Bundle {
    val cluster = Vec(8, Flipped(new MemInterface))
    val mem = new AXIBundle(64, 512)
  })

  val arb = Module(new RRArbiter(new MemReq, 8))

  for (i <- 0 until 8) {
    arb.io.in(i).valid := io.cluster(i).req.valid
    arb.io.in(i).bits := io.cluster(i).req.bits
    io.cluster(i).req.ready := arb.io.in(i).ready
  }

  io.mem := DontCare
}


