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
  mem_ctrl.io.mem_axi := DontCare
  mem_axi := DontCare
  debug_uart := DontCare
}
