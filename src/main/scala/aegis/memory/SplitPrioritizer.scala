package aegis.memory

import chisel3._
import chisel3.util._
import aegis._

class SplitPrioritizer(implicit config: AegisConfig) extends Module {
  val io = IO(new Bundle {
    val soc = new MemPort
    val mem_axi = new AXIBundle(config.axiAddrWidth, config.axiDataWidth)
    val mode = Input(UInt(2.W))
  })

  val cfg = config.mem

  val mode :: gaming :: ai :: Nil = Enum(3)

  val cpu_qos = RegInit(0.U(4.W))
  val gpu_qos = RegInit(0.U(4.W))

  val cpu_region_base = "h_8000_0000".U
  val gpu_region_base = "h_8000_0000".U
  val cpu_region_end  = if (cfg.cpuLatencyPct < 50) {
    "h_8800_0000".U
  } else {
    "h_8000_0000".U + (cfg.totalSizeGB * 1024 * 1024 / 100 * cfg.cpuLatencyPct).U
  }

  val is_cpu_req = io.soc.cpu_req.valid && (
    io.soc.cpu_req.bits.addr >= cpu_region_base &&
    io.soc.cpu_req.bits.addr < cpu_region_end
  )

  val use_open_page = io.mode === ai

  val hbm_ctrl = Module(new HBM3Controller)

  hbm_ctrl.io.addr := io.soc.cpu_req.bits.addr
  hbm_ctrl.io.write := io.soc.cpu_req.bits.isWrite
  hbm_ctrl.io.data_in := io.soc.cpu_req.bits.data
  hbm_ctrl.io.open_page := use_open_page
  hbm_ctrl.io.ready := true.B

  io.soc := DontCare
  hbm_ctrl.io.mem_axi := DontCare
  io.mem_axi := DontCare
}

class HBM3Controller extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(64.W))
    val write = Input(Bool())
    val data_in = Input(UInt(512.W))
    val data_out = Output(UInt(512.W))
    val valid = Output(Bool())
    val ready = Input(Bool())
    val open_page = Input(Bool())
    val mem_axi = new AXIBundle(64, 512)
  })

  val pg_enable = RegInit(false.B)
  pg_enable := io.open_page

  io.mem_axi := DontCare
  io.mem_axi.AWADDR := io.addr
  io.mem_axi.AWVALID := io.write
  io.mem_axi.WDATA := io.data_in
  io.mem_axi.WVALID := io.write
  io.mem_axi.ARADDR := io.addr
  io.mem_axi.ARVALID := !io.write

  io.data_out := io.mem_axi.RDATA
  io.valid := io.mem_axi.RVALID
}


