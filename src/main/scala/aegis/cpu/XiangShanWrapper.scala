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
    val bank = Module(new L2CacheBank(i))
    bank.io.core <> cores(i).io.l2
    bank
  }

  val l3_slice = Module(new L3VCache(cfg.l3CacheSizeMB * 1024 / cfg.nCores))

  val crossbar = Module(new CoreCrossbar(cfg.nCores))

  for (i <- cores.indices) {
    crossbar.io.core(i) <> l2_banks(i).io.crossbar
  }
  crossbar.io.l3 <> l3_slice.io.crossbar

  io.axi.AWID := l3_slice.io.mem.AWID
  io.axi.AWADDR := l3_slice.io.mem.AWADDR
  io.axi.AWLEN := l3_slice.io.mem.AWLEN
  io.axi.AWSIZE := l3_slice.io.mem.AWSIZE
  io.axi.AWBURST := l3_slice.io.mem.AWBURST
  io.axi.AWVALID := l3_slice.io.mem.AWVALID
  io.axi.WDATA := l3_slice.io.mem.WDATA
  io.axi.WSTRB := l3_slice.io.mem.WSTRB
  io.axi.WLAST := l3_slice.io.mem.WLAST
  io.axi.WVALID := l3_slice.io.mem.WVALID
  io.axi.BREADY := l3_slice.io.mem.BREADY
  io.axi.ARID := l3_slice.io.mem.ARID
  io.axi.ARADDR := l3_slice.io.mem.ARADDR
  io.axi.ARLEN := l3_slice.io.mem.ARLEN
  io.axi.ARSIZE := l3_slice.io.mem.ARSIZE
  io.axi.ARBURST := l3_slice.io.mem.ARBURST
  io.axi.ARVALID := l3_slice.io.mem.ARVALID
  io.axi.RREADY := l3_slice.io.mem.RREADY
  l3_slice.io.mem.AWREADY := io.axi.AWREADY
  l3_slice.io.mem.WREADY := io.axi.WREADY
  l3_slice.io.mem.BVALID := io.axi.BVALID
  l3_slice.io.mem.BRESP := io.axi.BRESP
  l3_slice.io.mem.BID := io.axi.BID
  l3_slice.io.mem.ARREADY := io.axi.ARREADY
  l3_slice.io.mem.RVALID := io.axi.RVALID
  l3_slice.io.mem.RDATA := io.axi.RDATA
  l3_slice.io.mem.RRESP := io.axi.RRESP
  l3_slice.io.mem.RLAST := io.axi.RLAST
  l3_slice.io.mem.RID := io.axi.RID

  io.soc.ipi := VecInit(cores.map(_.io.ipi)).asUInt
  io.soc.msi := VecInit(cores.map(_.io.msi)).asUInt
}

class XiangShanCore extends Module {
  val io = IO(new Bundle {
    val l2 = new L2Interface
    val ipi = Output(Bool())
    val msi = Output(Bool())
  })

  val reqCount = RegInit(0.U(8.W))
  val done     = RegInit(false.B)

  io.l2.valid := !done
  io.l2.addr  := reqCount * 16.U
  io.l2.data  := (reqCount * 2.U).asUInt

  when(io.l2.valid && io.l2.ready) {
    reqCount := reqCount + 1.U
    when(reqCount === 7.U) { done := true.B }
  }

  io.ipi := done && (reqCount % 2.U === 1.U)
  io.msi := done && (reqCount % 2.U === 0.U)
}

class L2CacheBank(val sourceId: Int = 0) extends Module {
  val io = IO(new Bundle {
    val core = Flipped(new L2Interface)
    val crossbar = new CrossbarInterface
  })

  val busy = RegInit(false.B)
  val addr_r = Reg(UInt(64.W))
  val data_r = Reg(UInt(512.W))

  io.core.ready := !busy
  when(io.core.valid && io.core.ready) {
    busy := true.B
    addr_r := io.core.addr
    data_r := io.core.data
  }

  io.crossbar.valid := busy
  io.crossbar.addr := addr_r
  io.crossbar.data := data_r
  io.crossbar.source := sourceId.U(4.W)

  when(busy && io.crossbar.ready) {
    busy := false.B
  }
}

class L3VCache(val sizeKB: Int) extends Module {
  val io = IO(new Bundle {
    val crossbar = Flipped(new CrossbarInterface)
    val mem = new AXIBundle(64, 512)
    val hit = Output(Bool())
  })

  val nSets = 4
  val setBits = 2
  val nWays = 2

  val tag_mem = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(0.U(62.W))))))
  val valid = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(false.B)))))
  val data = RegInit(VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(0.U(512.W))))))
  val lru = RegInit(VecInit(Seq.fill(nSets)(0.U(1.W))))

  val req = io.crossbar
  val idx = req.addr(setBits - 1, 0)
  val in_tag = req.addr(63, setBits)

  val hitWay0 = valid(idx)(0) && (tag_mem(idx)(0) === in_tag)
  val hitWay1 = valid(idx)(1) && (tag_mem(idx)(1) === in_tag)
  val hit = hitWay0 || hitWay1

  val captured  = RegInit(false.B)
  val ar_pending = RegInit(false.B)
  val rd_await  = RegInit(false.B)
  val ar_addr   = RegInit(0.U(64.W))

  when(req.valid && !captured && !ar_pending && !rd_await) {
    captured := true.B
    ar_addr := req.addr
  }
  when(captured && io.mem.ARREADY) {
    ar_pending := true.B
    captured := false.B
  }
  when(ar_pending && io.mem.RVALID) {
    ar_pending := false.B
    rd_await := true.B
  }
  when(rd_await) { rd_await := false.B }

  io.mem.AWID := 0.U
  io.mem.AWADDR := 0.U
  io.mem.AWLEN := 0.U
  io.mem.AWSIZE := 6.U
  io.mem.AWBURST := 0.U
  io.mem.AWVALID := false.B
  io.mem.WDATA := 0.U
  io.mem.WSTRB := 0.U
  io.mem.WLAST := false.B
  io.mem.WVALID := false.B
  io.mem.BREADY := false.B
  io.mem.ARID := 0.U
  io.mem.ARADDR := ar_addr
  io.mem.ARLEN := 0.U
  io.mem.ARSIZE := 6.U
  io.mem.ARBURST := 0.U
  io.mem.ARVALID := captured
  io.mem.RREADY := ar_pending

  io.crossbar.ready := !captured && !ar_pending && !rd_await
  io.hit := hit

  when(req.valid) {
    when(hitWay0) {
      lru(idx) := 1.U
    }.elsewhen(hitWay1) {
      lru(idx) := 0.U
    }.otherwise {
      val wgt = lru(idx)
      when(wgt === 0.U) {
        tag_mem(idx)(0) := in_tag
        data(idx)(0) := req.data
        valid(idx)(0) := true.B
        lru(idx) := 1.U
      }.otherwise {
        tag_mem(idx)(1) := in_tag
        data(idx)(1) := req.data
        valid(idx)(1) := true.B
        lru(idx) := 0.U
      }
    }
  }
}

class CoreCrossbar(val nCores: Int) extends Module {
  val io = IO(new Bundle {
    val core = Vec(nCores, Flipped(new CrossbarInterface))
    val l3 = new CrossbarInterface
  })

  val last = RegInit(0.U(log2Ceil(nCores).W))
  val v = (0 until nCores).map(i => io.core(i).valid)
  val lower = (0 until nCores).map(i => (i.U > last) && v(i))
  val upper = (0 until nCores).map(i => (i.U <= last) && v(i))
  val any = lower.reduce(_ || _) || upper.reduce(_ || _)
  val sel = Mux(lower.reduce(_ || _), PriorityEncoder(lower), PriorityEncoder(upper))
  val onehot = (0 until nCores).map(i => i.U === sel)

  io.l3.valid := any
  io.l3.addr := Mux1H(onehot, (0 until nCores).map(i => io.core(i).addr))
  io.l3.data := Mux1H(onehot, (0 until nCores).map(i => io.core(i).data))
  io.l3.source := Mux1H(onehot, (0 until nCores).map(i => io.core(i).source))

  for (i <- 0 until nCores) {
    io.core(i).ready := (i.U === sel) && io.l3.ready && any
  }

  when(any && io.l3.ready) {
    last := sel
  }
}
