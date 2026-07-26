package aegis

import chisel3._
import aegis.cpu.XiangShanWrapper
import aegis.gpu.VortexWrapper
import aegis.bridge.AXITileLinkBridge
import aegis.memory.SplitPrioritizer
import aegis.fixedfunc.{RayFlexWrapper, OpenGeMMWrapper}
import aegis.interconnect.SoCFabric

class Top(implicit config: AegisConfig) extends RawModule {
  override def desiredName = config.socName

  val clock = IO(Input(Clock()))
  val reset = IO(Input(Reset()))
  val mem_axi = IO(new AXIBundle(config.axiAddrWidth, config.axiDataWidth))
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

  bridge_cpu.io.axi <> cpu.io.axi
  bridge_cpu.io.tl <> soc.io.cpu_tl

  bridge_gpu.io.axi <> gpu.io.axi
  bridge_gpu.io.tl <> soc.io.gpu_tl

  mem_ctrl.io.mem_axi <> mem_axi

  dontTouch(clock)
  dontTouch(reset)
}
