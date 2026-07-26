package aegis.fixedfunc

import chisel3._
import chisel3.util._
import aegis.{AXIBundle, FixedFuncUnit}

class OpenGeMMWrapper(val tileSize: Int = 128) extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new Bundle {
      val opcode = UInt(8.W)
      val data = UInt(512.W)
    }))
    val resp = Decoupled(new Bundle {
      val data = UInt(512.W)
    })
    val mem = new AXIBundle(64, 512)
  })

  val systolic = Module(new SystolicArray(tileSize))

  systolic.io.cmd <> io.cmd
  io.resp <> systolic.io.resp
  io.mem <> systolic.io.mem
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
    val mem = new AXIBundle(64, 512)
  })

  val weight_mem = SyncReadMem(tileSize * tileSize, UInt(16.W))
  val input_mem = SyncReadMem(tileSize * tileSize, UInt(16.W))
  val output_mem = SyncReadMem(tileSize * tileSize, UInt(32.W))

  val s_idle :: s_load_w :: s_load_i :: s_compute :: s_store :: Nil = Enum(4)
  val state = RegInit(s_idle)
  val addr = RegInit(0.U(log2Ceil(tileSize * tileSize).W))
  val accum = RegInit(0.U(32.W))
  val done = RegInit(false.B)

  switch(state) {
    is(s_idle) {
      when(io.cmd.fire) {
        state := s_load_w
        addr := 0.U
      }
    }
    is(s_load_w) {
      when(addr === (tileSize * tileSize - 1).U) {
        state := s_load_i
        addr := 0.U
      }.otherwise {
        addr := addr + 1.U
      }
    }
    is(s_load_i) {
      when(addr === (tileSize * tileSize - 1).U) {
        state := s_compute
      }.otherwise {
        addr := addr + 1.U
      }
    }
    is(s_compute) {
      for (i <- 0 until tileSize) {
        for (j <- 0 until tileSize) {
          val w = weight_mem.read((i * tileSize + j).U)
          val x = input_mem.read((j * tileSize + i).U)
          val partial = w * x
        }
      }
      state := s_store
    }
    is(s_store) {
      done := true.B
      state := s_idle
    }
  }

  io.cmd.ready := state === s_idle

  io.resp.valid := done
  io.resp.bits.data := accum

  when(done) {
    done := false.B
  }

  io.mem := DontCare
}
