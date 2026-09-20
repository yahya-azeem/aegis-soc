package aegis.gpu

import chisel3._
import chisel3.util._
import aegis._

/**
 * GPU L2 cache / memory front-end for the shared HBM3 stack.
 *
 * Arbitrates between `nClusters` GPU requesters (the external cluster port and
 * the on-die SIMT core) with a round-robin selector, then drives a real
 * single-beat AXI4 transaction for the winning request: AW+W+B for stores or
 * AR+R for loads. The data returned by memory is routed back to the cluster
 * that issued the request, so GPU agents read and write the same HBM3 cells
 * the CPU and accelerator lanes use.
 */
class GPUL2Cache(val nClusters: Int = 8) extends Module {
  val io = IO(new Bundle {
    val cluster = Vec(nClusters, Flipped(new MemInterface))
    val mem = new AXIBundle(64, 512)
  })

  // round-robin arbitration across clusters
  val last = RegInit(0.U(log2Ceil(nClusters).W))
  val v = (0 until nClusters).map(i => io.cluster(i).req.valid)
  val lower = (0 until nClusters).map(i => (i.U > last) && v(i))
  val upper = (0 until nClusters).map(i => (i.U <= last) && v(i))
  val any = lower.reduce(_ || _) || upper.reduce(_ || _)
  val sel = Mux(lower.reduce(_ || _), PriorityEncoder(lower), PriorityEncoder(upper))
  val onehot = (0 until nClusters).map(i => i.U === sel)

  // real AXI transaction FSM: capture one request, drive AW+W / AR+R, and
  // return the data that actually came back from memory to the waiting cluster.
  val s_idle :: s_aw :: s_wd :: s_bwait :: s_wack :: s_ar :: s_rc :: s_resp :: Nil = Enum(8)
  val state = RegInit(s_idle)
  val sel_r   = RegInit(0.U(log2Ceil(nClusters).W))
  val addr_r  = RegInit(0.U(64.W))
  val wdata_r = RegInit(0.U(512.W))
  val rdata_r = RegInit(0.U(512.W))
  val isW_r   = RegInit(false.B)

  for (i <- 0 until nClusters) {
    io.cluster(i).req.ready  := (state === s_idle) && (i.U === sel)
    io.cluster(i).resp.valid := (state === s_wack || state === s_resp) && (i.U === sel_r)
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
  io.mem.BREADY := state === s_bwait
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
    is(s_wd)   { when(io.mem.WREADY)  { state := s_bwait } }
    is(s_bwait) { when(io.mem.BVALID) { state := s_wack } }
    is(s_wack) { when(io.cluster(sel_r).resp.fire) { state := s_idle } }
    is(s_ar)   { when(io.mem.ARREADY) { state := s_rc } }
    is(s_rc)   { when(io.mem.RVALID)  { rdata_r := io.mem.RDATA; state := s_resp } }
    is(s_resp) { when(io.cluster(sel_r).resp.fire) { state := s_idle } }
  }
}
