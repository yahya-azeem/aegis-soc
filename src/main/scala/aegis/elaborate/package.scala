package aegis.elaborate

import aegis._
import aegis.Top
import circt.stage.ChiselStage

object TopElaborate extends App {
  implicit val config: AegisConfig = AegisConfig()
  println(s"=== ${config.socName} SoC ===")
  println(s"CPU: ${config.cpu.nCores}x XiangShan KMV3 @ ${config.cpu.freqMHz}MHz, L3 ${config.cpu.l3CacheSizeMB}MB V-Cache")
  println(s"GPU: ${config.gpu.nClusters}x Vortex clusters, ${config.gpu.nCores} SIMT cores @ ${config.gpu.freqMHz}MHz")
  println(s"Mem: ${config.mem.totalSizeGB}GB HBM3 @ ${config.mem.hbmFreqGbps}Gbps")
  println(s"Fixed: RT=${config.fixedFunc.rayTracing}, AI=${config.fixedFunc.aiUpscaling}")
  println()

  val verilog = ChiselStage.emitSystemVerilog(new Top()(config))
  val outDir = "build/rtl"
  val outFile = new java.io.File("build", "rtl")
  outFile.mkdirs()
  val pw = new java.io.PrintWriter(new java.io.File(outFile, s"${config.socName}.sv"))
  try pw.write(verilog) finally pw.close()
  println(s"Generated Verilog: ${outFile}/${config.socName}.sv")
}
