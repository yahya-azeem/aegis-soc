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

  io.axi.AWID := l2_cache.io.mem.AWID
  io.axi.AWADDR := l2_cache.io.mem.AWADDR
  io.axi.AWLEN := l2_cache.io.mem.AWLEN
  io.axi.AWSIZE := l2_cache.io.mem.AWSIZE
  io.axi.AWBURST := l2_cache.io.mem.AWBURST
  io.axi.AWVALID := l2_cache.io.mem.AWVALID
  io.axi.WDATA := l2_cache.io.mem.WDATA
  io.axi.WSTRB := l2_cache.io.mem.WSTRB
  io.axi.WLAST := l2_cache.io.mem.WLAST
  io.axi.WVALID := l2_cache.io.mem.WVALID
  io.axi.BREADY := l2_cache.io.mem.BREADY
  io.axi.ARID := l2_cache.io.mem.ARID
  io.axi.ARADDR := l2_cache.io.mem.ARADDR
  io.axi.ARLEN := l2_cache.io.mem.ARLEN
  io.axi.ARSIZE := l2_cache.io.mem.ARSIZE
  io.axi.ARBURST := l2_cache.io.mem.ARBURST
  io.axi.ARVALID := l2_cache.io.mem.ARVALID
  io.axi.RREADY := l2_cache.io.mem.RREADY
  l2_cache.io.mem.AWREADY := io.axi.AWREADY
  l2_cache.io.mem.WREADY := io.axi.WREADY
  l2_cache.io.mem.BVALID := io.axi.BVALID
  l2_cache.io.mem.BRESP := io.axi.BRESP
  l2_cache.io.mem.BID := io.axi.BID
  l2_cache.io.mem.ARREADY := io.axi.ARREADY
  l2_cache.io.mem.RVALID := io.axi.RVALID
  l2_cache.io.mem.RDATA := io.axi.RDATA
  l2_cache.io.mem.RRESP := io.axi.RRESP
  l2_cache.io.mem.RLAST := io.axi.RLAST
  l2_cache.io.mem.RID := io.axi.RID

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

  val active  = RegInit(true.B)
  val pc      = RegInit(0.U(64.W))
  val count   = RegInit(0.U(8.W))

  cores.scalar.pc     := pc
  cores.scalar.instr  := 0x00000013.U(32.W)
  cores.scalar.valid  := active
  cores.vector.vaddr  := pc
  cores.vector.vdata  := (count * 4.U).asUInt
  cores.vector.valid  := active

  io.mem.req.valid := active
  io.mem.req.bits.addr  := pc
  io.mem.req.bits.data  := (count * 4.U).asUInt
  io.mem.req.bits.isWrite := false.B
  io.mem.req.bits.size := 4.U

  when(io.mem.req.fire) {
    pc := pc + 8.U
    count := count + 1.U
    when(count === 7.U) { active := false.B }
  }

  io.mem.resp.ready := false.B

  io.irq := !active
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

class GPUL2Cache(val nClusters: Int = 8) extends Module {
  val io = IO(new Bundle {
    val cluster = Vec(nClusters, Flipped(new MemInterface))
    val mem = new AXIBundle(64, 512)
  })

  // round-robin arbitration across clusters (same select as before)
  val last = RegInit(0.U(log2Ceil(nClusters).W))
  val v = (0 until nClusters).map(i => io.cluster(i).req.valid)
  val lower = (0 until nClusters).map(i => (i.U > last) && v(i))
  val upper = (0 until nClusters).map(i => (i.U <= last) && v(i))
  val any = lower.reduce(_ || _) || upper.reduce(_ || _)
  val sel = Mux(lower.reduce(_ || _), PriorityEncoder(lower), PriorityEncoder(upper))
  val onehot = (0 until nClusters).map(i => i.U === sel)

  // real AXI transaction FSM: capture one request, drive AW+W / AR+R, and
  // return the data that actually came back from memory to the waiting cluster.
  val s_idle :: s_aw :: s_wd :: s_b :: s_ar :: s_rc :: s_resp :: Nil = Enum(7)
  val state = RegInit(s_idle)
  val sel_r   = RegInit(0.U(log2Ceil(nClusters).W))
  val addr_r  = RegInit(0.U(64.W))
  val wdata_r = RegInit(0.U(512.W))
  val rdata_r = RegInit(0.U(512.W))
  val isW_r   = RegInit(false.B)

  for (i <- 0 until nClusters) {
    io.cluster(i).req.ready  := (state === s_idle) && (i.U === sel)
    io.cluster(i).resp.valid := (state === s_b || state === s_resp) && (i.U === sel_r)
    io.cluster(i).resp.bits  := Mux(isW_r, 0.U, rdata_r)
  }

  io.mem.AWID := 0.U
  io.mem.AWADDR := addr_r
  io.mem.AWLEN := 0.U
  io.mem.AWSIZE := 6.U
  io.mem.AWBURST := 0.U
  io.mem.AWVALID := state === s_aw
  io.mem.WDATA := wdata_r
  io.mem.WSTRB := ~0.U((512 / 8).W)
  io.mem.WLAST := true.B
  io.mem.WVALID := state === s_wd
  io.mem.BREADY := state === s_b
  io.mem.ARID := 0.U
  io.mem.ARADDR := addr_r
  io.mem.ARLEN := 0.U
  io.mem.ARSIZE := 6.U
  io.mem.ARBURST := 0.U
  io.mem.ARVALID := state === s_ar
  io.mem.RREADY := state === s_rc

  switch(state) {
    is(s_idle) {
      when(any) {
        sel_r := sel
        addr_r := Mux1H(onehot, (0 until nClusters).map(i => io.cluster(i).req.bits.addr))
        wdata_r := Mux1H(onehot, (0 until nClusters).map(i => io.cluster(i).req.bits.data))
        last := sel
        when(Mux1H(onehot, (0 until nClusters).map(i => io.cluster(i).req.bits.isWrite))) {
          state := s_aw
        }.otherwise {
          state := s_ar
        }
      }
    }
    is(s_aw)   { when(io.mem.AWREADY) { state := s_wd } }
    is(s_wd)   { when(io.mem.WREADY)  { state := s_b } }
    is(s_b)    { when(io.mem.BVALID) { state := s_idle } }
    is(s_ar)   { when(io.mem.ARREADY) { state := s_rc } }
    is(s_rc)   { when(io.mem.RVALID)  { rdata_r := io.mem.RDATA; state := s_resp } }
    is(s_resp) { when(io.cluster(sel_r).resp.fire) { state := s_idle } }
  }
}


