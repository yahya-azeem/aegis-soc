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

  io.axi := DontCare
  l2_cache.io.mem := DontCare

  io.soc.irq := clusters.map(_.io.irq).reduce(_ || _)
}

class VortexCluster extends Module {
  val io = IO(new Bundle {
    val mem = new MemInterface
    val irq = Output(Bool())
  })

  val cores = IO(new Bundle {
    val scalar = new ScalarPipeline
    val vector = new VectorPipeline
  })

  cores := DontCare
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

class GPUL2Cache(val nClusters: Int = 8, val latency: Int = 2) extends Module {
  val io = IO(new Bundle {
    val cluster = Vec(nClusters, Flipped(new MemInterface))
    val mem = Flipped(new AXIBundle(64, 512))
  })

  val last = RegInit(0.U(log2Ceil(nClusters).W))
  val in_flight = RegInit(false.B)
  val serving = RegInit(0.U(log2Ceil(nClusters).W))
  val L = RegInit(0.U(log2Ceil(latency).W))
  val echo = Reg(UInt(512.W))

  val v = (0 until nClusters).map(i => io.cluster(i).req.valid)
  val lower = (0 until nClusters).map(i => (i.U > last) && v(i))
  val upper = (0 until nClusters).map(i => (i.U <= last) && v(i))
  val any = lower.reduce(_ || _) || upper.reduce(_ || _)

  val sel = Mux(lower.reduce(_ || _), PriorityEncoder(lower), PriorityEncoder(upper))
  val onehot = (0 until nClusters).map(i => i.U === sel)

  io.mem := DontCare

  for (i <- 0 until nClusters) {
    io.cluster(i).req.ready := !in_flight && any && (i.U === sel)
    io.cluster(i).resp.valid := in_flight && (L === 0.U) && io.cluster(i).resp.ready && (i.U === serving)
    io.cluster(i).resp.bits := echo
  }

  when(!in_flight && any) {
    in_flight := true.B
    serving := sel
    last := sel
    L := (latency - 1).U
    echo := Mux1H(onehot, (0 until nClusters).map(i => io.cluster(i).req.bits.data))
  }

  when(in_flight) {
    when(L === 0.U) {
      when(io.cluster(serving).resp.ready) {
        in_flight := false.B
      }
    }.otherwise {
      L := L - 1.U
    }
  }
}


