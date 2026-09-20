package aegis.elaborate

import aegis._
import circt.stage.ChiselStage

/** Handle CIRCT's concatenated multi-file output. ChiselStage returns one big
  * string where each real file is delimited by "// ----- 8< ----- FILE ..."
  * markers. We re-materialize the tree so verilator can resolve the
  * verification-layer `include`s. The primary module is written as Aegis.sv.
  */
object EmitSupport {
  private val Delim = "// ----- 8< ----- FILE \""
  private val Marker = "(?m)^// ----- 8< ----- FILE \"([^\"]+)\" ----- 8< -----\\s*$".r

  def emitAndSplitAndWritePrimary(
      gen: => chisel3.Module,
      outDir: String,
      primaryName: String,
      firtoolOpts: Array[String] = Array.empty,
  ): Unit = {
    val verilog = ChiselStage.emitSystemVerilog(gen, firtoolOpts = firtoolOpts)
    val dir = new java.io.File(outDir)
    dir.mkdirs()

    if (!verilog.contains(Delim)) {
      writeFile(new java.io.File(dir, primaryName), verilog)
      println(s"Generated SystemVerilog: ${dir}/${primaryName}")
      return
    }

    val first = verilog.indexOf(Delim)
    val header = verilog.substring(0, first)
    val rest = verilog.substring(first)

    writeFile(new java.io.File(dir, primaryName), header)

    val quoted = java.util.regex.Pattern.quote(Delim)
    for (seg <- rest.split("(?=" + quoted + ")")) {
      Marker.findFirstMatchIn(seg).foreach { m =>
        val rel = m.group(1)
        val body = seg.substring(seg.indexOf("\n") + 1)
        writeFile(new java.io.File(dir, rel), body)
        println(s"emitted: ${outDir}/${rel}")
      }
    }
  }

  private def writeFile(f: java.io.File, content: String): Unit = {
    f.getParentFile.mkdirs()
    val pw = new java.io.PrintWriter(f)
    try pw.write(content) finally pw.close()
  }
}

object TopElaborate extends App {
  implicit val config: AegisConfig = AegisConfig()
  println(s"=== ${config.socName} SoC ===")
  println("CPU: 1x RV32I in-order boot core -> 512-bit shared-memory adapter")
  println("GPU: 2-port L2 front-end (external cluster + 32-lane on-die SIMT core)")
  println("Mem: 16KB banked HBM3 model (4 banks x 8 rows x 8 cols x 512-bit), open-page + refresh")
  println("Acc: fixed-function GEMM (tile 8)")
  println()

  EmitSupport.emitAndSplitAndWritePrimary(new Top()(config), "build/rtl", "Aegis.sv")
  println("Generated Verilog in build/rtl/")
}

/** Same SoC with the real Vortex RTL black-boxed on the acc port, emitted for
  * the out-of-tree raw-verilator co-sim flow in test/vortex. */
object TopVortexElaborate extends App {
  implicit val config: AegisConfig = AegisConfig(vortexRtl = true)
  println(s"=== ${config.socName} SoC (real Vortex RTL on acc port) ===")
  EmitSupport.emitAndSplitAndWritePrimary(new Top()(config), "build/vortex-smoke/emit", "Aegis.sv")
  println("Generated Verilog in build/vortex-smoke/emit/")
}

/** Emit the SoC in a form the Yosys Verilog frontend can parse: firtool's
  * `disallowLocalVariables` (no `$unnamed_block` accesses) and
  * `disallowPackedArrays` (no packed multi-dimensional arrays). This is only
  * used for the gate-level/statistics views; the simulation flow uses the
  * default emission above. */
object TopYosysElaborate extends App {
  implicit val config: AegisConfig = AegisConfig()
  val firtoolOpts = Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
  println(s"=== ${config.socName} SoC (Yosys-friendly emission) ===")
  EmitSupport.emitAndSplitAndWritePrimary(new Top()(config), "build/rtl-yosys", "Aegis.sv", firtoolOpts)
  println("Generated Yosys-friendly Verilog in build/rtl-yosys/")
}