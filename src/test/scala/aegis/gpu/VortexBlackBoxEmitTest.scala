package aegis.gpu

import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage
import aegis.{AegisConfig, Top}

/**
 * Elaboration-only tests for the real-Vortex-RTL BlackBox integration. These
 * check the emitted SystemVerilog structurally; they never simulate, so the
 * external Vortex RTL (co-simulated out-of-tree in test/vortex) is not needed.
 */
class VortexBlackBoxEmitTest extends AnyFlatSpec {
  behavior of "VortexAxiBlackBox emission"

  it should "instantiate the flat VortexShell wrapper with the real AXI pins when vortexRtl is on" in {
    val vxConfig = AegisConfig(gpu = AegisConfig().gpu.copy(vortexRtl = true))
    val verilog = ChiselStage.emitSystemVerilog(new Top()(vxConfig))
    assert(verilog.contains("VortexShell vx ("), "expected a VortexShell BlackBox instantiation")
    assert(verilog.contains("m_axi_awaddr"), "expected AXI write-address pin")
    assert(verilog.contains("m_axi_arready"), "expected AXI read-address ready pin")
    assert(verilog.contains("dcr_req_valid"), "expected DCR request pin")
    assert(verilog.contains(".start"), "expected start pin")
    assert(verilog.contains(".busy"), "expected busy pin")
  }

  it should "not instantiate the real Vortex RTL by default" in {
    val verilog = ChiselStage.emitSystemVerilog(new Top()(AegisConfig()))
    assert(!verilog.contains("VortexShell vx ("), "default emission should keep the Chisel GPU path")
    assert(verilog.contains("VortexAccelerator") == false)
  }
}