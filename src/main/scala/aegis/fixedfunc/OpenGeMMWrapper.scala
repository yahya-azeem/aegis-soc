package aegis.fixedfunc

import chisel3._
import chisel3.util._
import aegis.AXIBundle

object GemmOp {
  val LOAD_W = 0.U(8.W)
  val LOAD_I = 1.U(8.W)
  val COMPUTE = 2.U(8.W)
  val READ = 3.U(8.W)
}

class SystolicArray(val tileSize: Int) extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new Bundle {
      val opcode = UInt(8.W)
      val data = UInt(512.W)
    }))
    val resp = Decoupled(new Bundle {
      val data = UInt(512.W)
    })
    val mem = Flipped(new AXIBundle(64, 512))
  })

  val n = tileSize
  val n2 = n * n
  val szBits = log2Ceil(n2)

  def ptr(x: UInt): UInt = x(szBits - 1, 0)

  val weight = RegInit(VecInit(Seq.fill(n2)(0.U(16.W))))
  val input = RegInit(VecInit(Seq.fill(n2)(0.U(16.W))))
  val output = RegInit(VecInit(Seq.fill(n2)(0.U(64.W))))

  val s_idle :: s_compute :: s_read :: s_done :: Nil = Enum(4)
  val state = RegInit(s_idle)

  val p_cnt = RegInit(0.U(log2Ceil(n2).W))
  val k_cnt = RegInit(0.U(log2Ceil(n).W))
  val acc = RegInit(0.U(64.W))
  val rd_data = RegInit(0.U(64.W))

  io.mem := DontCare
  io.cmd.ready := state === s_idle
  io.resp.valid := (state === s_read) || (state === s_done)
  io.resp.bits.data := Mux(state === s_done, (n2 * n).U, rd_data)

  val index = io.cmd.bits.data(15, 0)
  val value = io.cmd.bits.data(31, 16)

  switch(state) {
    is(s_idle) {
      when(io.cmd.fire) {
        switch(io.cmd.bits.opcode) {
          is(GemmOp.LOAD_W) { weight(ptr(index)) := value }
          is(GemmOp.LOAD_I) { input(ptr(index)) := value }
          is(GemmOp.COMPUTE) {
            p_cnt := 0.U
            k_cnt := 0.U
            acc := 0.U
            state := s_compute
          }
          is(GemmOp.READ) {
            rd_data := output(ptr(index))
            state := s_read
          }
        }
      }
    }
    is(s_compute) {
      val i = p_cnt / n.U
      val j = p_cnt % n.U
      val w = weight(ptr(i * n.U + k_cnt))
      val x = input(ptr(k_cnt * n.U + j))
      when(k_cnt === (n - 1).U) {
        output(p_cnt) := acc + w * x
        when(p_cnt === (n2 - 1).U) {
          state := s_done
        }.otherwise {
          p_cnt := p_cnt + 1.U
          k_cnt := 0.U
          acc := 0.U
        }
      }.otherwise {
        acc := acc + w * x
        k_cnt := k_cnt + 1.U
      }
    }
  }

  switch(state) {
    is(s_read) {
      when(io.resp.fire) { state := s_idle }
    }
    is(s_done) {
      when(io.resp.fire) { state := s_idle }
    }
  }
}