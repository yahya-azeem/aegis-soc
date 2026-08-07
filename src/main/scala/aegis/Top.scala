package aegis

import chisel3._
import aegis.cpu.XiangShanWrapper
import aegis.gpu.VortexWrapper
import aegis.bridge.AXITileLinkBridge
import aegis.memory.SplitPrioritizer
import aegis.interconnect.SoCFabric

class Top(memMode: Int = 0)(implicit config: AegisConfig) extends Module {
  override def desiredName = config.socName

  val mem_axi = IO(new AXIBundle(config.axiAddrWidth, config.axiDataWidth))
  val mem_mode = IO(Output(UInt(2.W)))
  val debug_uart = IO(new UARTIO)

  val soc = Module(new SoCFabric)
  val cpu = Module(new XiangShanWrapper)
  val gpu = Module(new VortexWrapper)
  val mem_ctrl = Module(new SplitPrioritizer)
  val bridge_cpu = Module(new AXITileLinkBridge(config.tlBeatBytes, config.axiDataWidth))
  val bridge_gpu = Module(new AXITileLinkBridge(config.tlBeatBytes, config.axiDataWidth))

soc.io.cpu <> cpu.io.soc
  soc.io.gpu <> gpu.io.soc
  soc.io.mem <> mem_ctrl.io.soc

  val bridge_cpu_wire = Wire(new AXIBundle(config.axiAddrWidth, config.axiDataWidth))
  bridge_cpu.io.axi <> bridge_cpu_wire
  bridge_cpu_wire <> cpu.io.axi

  val bridge_gpu_wire = Wire(new AXIBundle(config.axiAddrWidth, config.axiDataWidth))
  bridge_gpu.io.axi <> bridge_gpu_wire
  bridge_gpu_wire <> gpu.io.axi

  bridge_cpu.io.tl <> soc.io.cpu_tl
  bridge_gpu.io.tl <> soc.io.gpu_tl

  mem_ctrl.io.mode := memMode.U(2.W)
  mem_mode := memMode.U(2.W)

  mem_axi.AWID := mem_ctrl.io.mem_axi.AWID
  mem_axi.AWADDR := mem_ctrl.io.mem_axi.AWADDR
  mem_axi.AWLEN := mem_ctrl.io.mem_axi.AWLEN
  mem_axi.AWSIZE := mem_ctrl.io.mem_axi.AWSIZE
  mem_axi.AWBURST := mem_ctrl.io.mem_axi.AWBURST
  mem_axi.AWVALID := mem_ctrl.io.mem_axi.AWVALID
  mem_axi.WDATA := mem_ctrl.io.mem_axi.WDATA
  mem_axi.WSTRB := mem_ctrl.io.mem_axi.WSTRB
  mem_axi.WLAST := mem_ctrl.io.mem_axi.WLAST
  mem_axi.WVALID := mem_ctrl.io.mem_axi.WVALID
  mem_axi.BREADY := mem_ctrl.io.mem_axi.BREADY
  mem_axi.ARID := mem_ctrl.io.mem_axi.ARID
  mem_axi.ARADDR := mem_ctrl.io.mem_axi.ARADDR
  mem_axi.ARLEN := mem_ctrl.io.mem_axi.ARLEN
  mem_axi.ARSIZE := mem_ctrl.io.mem_axi.ARSIZE
  mem_axi.ARBURST := mem_ctrl.io.mem_axi.ARBURST
  mem_axi.ARVALID := mem_ctrl.io.mem_axi.ARVALID
  mem_axi.RREADY := mem_ctrl.io.mem_axi.RREADY
  mem_ctrl.io.mem_axi.AWREADY := mem_axi.AWREADY
  mem_ctrl.io.mem_axi.WREADY := mem_axi.WREADY
  mem_ctrl.io.mem_axi.BVALID := mem_axi.BVALID
  mem_ctrl.io.mem_axi.BRESP := mem_axi.BRESP
  mem_ctrl.io.mem_axi.BID := mem_axi.BID
  mem_ctrl.io.mem_axi.ARREADY := mem_axi.ARREADY
  mem_ctrl.io.mem_axi.RVALID := mem_axi.RVALID
  mem_ctrl.io.mem_axi.RDATA := mem_axi.RDATA
  mem_ctrl.io.mem_axi.RRESP := mem_axi.RRESP
  mem_ctrl.io.mem_axi.RLAST := mem_axi.RLAST
  mem_ctrl.io.mem_axi.RID := mem_axi.RID

  debug_uart := DontCare
}
