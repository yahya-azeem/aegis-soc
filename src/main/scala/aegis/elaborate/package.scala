package aegis.elaborate

import aegis._

object TopElaborate extends App {
  implicit val config: AegisConfig = AegisConfig()
  println(s"=== ${config.socName} SoC ===")
  println(s"CPU: ${config.cpu.nCores}x XiangShan KMV3 @ ${config.cpu.freqMHz}MHz, L3 ${config.cpu.l3CacheSizeMB}MB V-Cache")
  println(s"GPU: ${config.gpu.nClusters}x Vortex clusters, ${config.gpu.nCores} SIMT cores @ ${config.gpu.freqMHz}MHz")
  println(s"Mem: ${config.mem.totalSizeGB}GB HBM3 @ ${config.mem.hbmFreqGbps}Gbps")
  println(s"Fixed: RT=${config.fixedFunc.rayTracing}, AI=${config.fixedFunc.aiUpscaling}")
  println()
  println("To generate Verilog:")
  println("  sbt 'runMain aegis.elaborate.TopElaborate'")
  println("To run tests:")
  println("  sbt test")
}
