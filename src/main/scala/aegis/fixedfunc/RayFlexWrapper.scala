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
    val mem = new AXIBundle(64, 256)
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

  val resp_arb = Module(new RRArbiter(chiselTypeOf(p(0).resp.bits), nPipelines, true))
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

  val s_idle :: s_bvh :: s_intersect :: s_done :: Nil = Enum(4)
  val state = RegInit(s_idle)

  val ray_origin = Reg(UInt(192.W))
  val ray_dir = Reg(UInt(192.W))
  val hit_dist = Reg(UInt(32.W))

  switch(state) {
    is(s_idle) {
      when(io.cmd.fire) {
        ray_origin := io.cmd.bits.data(191, 0)
        ray_dir := io.cmd.bits.data(383, 192)
        state := s_bvh
      }
    }
    is(s_bvh) {
      state := s_intersect
    }
    is(s_intersect) {
      hit_dist := hit_dist + 1.U
      state := s_done
    }
    is(s_done) {
      when(io.resp.fire) {
        state := s_idle
      }
    }
  }

  io.cmd.ready := state === s_idle
  io.resp.valid := state === s_done
  io.resp.bits.data := hit_dist
}
