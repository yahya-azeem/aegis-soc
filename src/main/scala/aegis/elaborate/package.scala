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

  def emitAndSplitAndWritePrimary(gen: => chisel3.Module, outDir: String, primaryName: String): Unit = {
    val verilog = ChiselStage.emitSystemVerilog(gen)
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
  println(s"CPU: ${config.cpu.nCores}x XiangShan KMV3 @ ${config.cpu.freqMHz}MHz, L3 ${config.cpu.l3CacheSizeMB}MB V-Cache")
  println(s"GPU: ${config.gpu.nClusters}x Vortex clusters, ${config.gpu.nCores} SIMT cores @ ${config.gpu.freqMHz}MHz")
  println(s"Mem: ${config.mem.totalSizeGB}GB HBM3 @ ${config.mem.hbmFreqGbps}Gbps")
  println(s"Fixed: RT=${config.fixedFunc.rayTracing}, AI=${config.fixedFunc.aiUpscaling}")
  println()

  EmitSupport.emitAndSplitAndWritePrimary(new Top()(config), "build/rtl", "Aegis.sv")
  println("Generated Verilog in build/rtl/")
}

/** Same SoC with the real Vortex RTL black-boxed on the acc port, emitted for
  * the out-of-tree raw-verilator co-sim flow in test/vortex. */
object TopVortexElaborate extends App {
  implicit val config: AegisConfig = AegisConfig(gpu = AegisConfig().gpu.copy(vortexRtl = true))
  println(s"=== ${config.socName} SoC (real Vortex RTL on acc port) ===")
  EmitSupport.emitAndSplitAndWritePrimary(new Top()(config), "build/vortex-smoke/emit", "Aegis.sv")
  println("Generated Verilog in build/vortex-smoke/emit/")
}