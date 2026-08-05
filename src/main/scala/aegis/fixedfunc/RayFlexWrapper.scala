package aegis.fixedfunc

import chisel3._
import chisel3.util._
import aegis.AXIBundle

class RayFlexWrapper(val nPipelines: Int = 4) extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new Bundle {
      val opcode = UInt(8.W)
      val data = UInt(512.W)
    }))
    val resp = Decoupled(new Bundle {
      val data = UInt(512.W)
    })
    val mem = Flipped(new AXIBundle(64, 256))
  })

  val p = VecInit(Seq.fill(nPipelines) {
    val m = Module(new RayFlexPipeline)
    m.io
  })

  val grant = RegInit(0.U(log2Ceil(nPipelines).W))

  io.cmd.ready := p(grant).cmd.ready

  for (i <- 0 until nPipelines) {
    p(i).cmd.valid := false.B
    p(i).cmd.bits := DontCare
  }

  p(grant).cmd.valid := io.cmd.valid
  p(grant).cmd.bits := io.cmd.bits

  when(io.cmd.fire) {
    grant := Mux(grant === (nPipelines - 1).U, 0.U, grant + 1.U)
  }

  val resp_arb = Module(new RRArbiter(chiselTypeOf(p(0).resp.bits), nPipelines))
  for (i <- 0 until nPipelines) {
    resp_arb.io.in(i) <> p(i).resp
  }
  io.resp <> resp_arb.io.out

  io.mem := DontCare
}

class RayFlexPipeline extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new Bundle {
      val opcode = UInt(8.W)
      val data = UInt(512.W)
    }))
    val resp = Decoupled(new Bundle {
      val data = UInt(512.W)
    })
  })

  val rayBundle = new Bundle {
    val origin = UInt(192.W)
    val dir = UInt(192.W)
  }

  val inSkid = Module(new Queue(chiselTypeOf(io.cmd.bits.data), 1))
  val bvhStage = Module(new Queue(new Bundle {
    val origin = UInt(192.W)
    val dir = UInt(192.W)
  }, 1))
  val outSkid = Module(new Queue(UInt(512.W), 1))

  inSkid.io.enq.bits := io.cmd.bits.data
  inSkid.io.enq.valid := io.cmd.valid
  io.cmd.ready := inSkid.io.enq.ready

  bvhStage.io.enq.bits.origin := inSkid.io.deq.bits(191, 0)
  bvhStage.io.enq.bits.dir := inSkid.io.deq.bits(383, 192)
  bvhStage.io.enq.valid := inSkid.io.deq.valid
  inSkid.io.deq.ready := bvhStage.io.enq.ready

  val origin = bvhStage.io.deq.bits.origin
  val dir = bvhStage.io.deq.bits.dir

  val t_near = origin(63, 32) ^ dir(63, 32)
  val hit_dist = t_near + dir(31, 0)
  val hit = dir(0)

  outSkid.io.enq.valid := bvhStage.io.deq.valid
  outSkid.io.enq.bits := Cat(hit, hit_dist)
  bvhStage.io.deq.ready := outSkid.io.enq.ready

  outSkid.io.deq.ready := io.resp.ready
  io.resp.valid := outSkid.io.deq.valid
  io.resp.bits.data := outSkid.io.deq.bits
}
