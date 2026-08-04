package aegis.cpu

import chisel3._
import chisel3.util._
import aegis._

class XiangShanWrapper(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val soc = new CPUIO
    val axi = new AXIBundle(config.axiAddrWidth, config.axiDataWidth)
  })

  val cfg = config.cpu

  val cores = Seq.fill(cfg.nCores) { Module(new XiangShanCore) }

  val l2_banks = cores.indices.map { i =>
    val bank = Module(new L2CacheBank)
    bank.io.core <> cores(i).io.l2
    bank
  }

  val l3_slice = Module(new L3VCache(cfg.l3CacheSizeMB * 1024 / cfg.nCores))

  val crossbar = Module(new CoreCrossbar(cfg.nCores))

  for (i <- cores.indices) {
    crossbar.io.core(i) <> l2_banks(i).io.crossbar
  }
  crossbar.io.l3 <> l3_slice.io.crossbar

  io.axi := DontCare
  l3_slice.io.mem := DontCare

  io.soc.ipi := VecInit(cores.map(_.io.ipi)).asUInt
  io.soc.msi := VecInit(cores.map(_.io.msi)).asUInt
}

class XiangShanCore extends Module {
  val io = IO(new Bundle {
    val l2 = new L2Interface
    val ipi = Output(Bool())
    val msi = Output(Bool())
  })
  io.l2 := DontCare
  io.ipi := false.B
  io.msi := false.B
}

class L2CacheBank extends Module {
  val io = IO(new Bundle {
    val core = Flipped(new L2Interface)
    val crossbar = new CrossbarInterface
  })
  io.core := DontCare
  io.crossbar := DontCare
}

class L3VCache(val sizeKB: Int) extends Module {
  val io = IO(new Bundle {
    val crossbar = Flipped(new CrossbarInterface)
    val mem = Flipped(new AXIBundle(64, 512))
  })
  io.crossbar := DontCare
  io.mem := DontCare
}

class CoreCrossbar(val nCores: Int) extends Module {
  val io = IO(new Bundle {
    val core = Vec(nCores, Flipped(new CrossbarInterface))
    val l3 = new CrossbarInterface
  })
  io.core := DontCare
  io.l3 := DontCare
}
