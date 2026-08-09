package object aegis {
  case class AegisConfig(
    cpu:          CPUConfig = CPUConfig(),
    gpu:          GPUConfig = GPUConfig(),
    mem:          MemoryConfig = MemoryConfig(),
    fixedFunc:    FixedFuncConfig = FixedFuncConfig(),
    socName:      String = "Aegis",
    axiAddrWidth: Int = 64,
    axiDataWidth: Int = 512,
    tlBeatBytes:  Int = 64,
  )

  case class CPUConfig(
    nCores:          Int = 4,
    freqMHz:        Int = 3000,
    l1ICacheSizeKB: Int = 64,
    l1DCacheSizeKB: Int = 64,
    l2CacheSizeKB:  Int = 1024,
    l3CacheSizeMB:  Int = 96,
    vlen:           Int = 128,
    dlen:           Int = 1024,
  )

  case class GPUConfig(
    nCores:          Int = 64,
    nClusters:      Int = 8,
    freqMHz:       Int = 1500,
    warpSize:      Int = 32,
    sharedMemKB:   Int = 64,
    registerFileSize: Int = 128,
    tensorCores:   Boolean = true,
    vulkanSupport: Boolean = true,
    vortexRtl:     Boolean = false, // BlackBox the real Vortex_axi RTL on the acc port
  )

  case class MemoryConfig(
    totalSizeGB:     Int = 128,
    hbmChannels:     Int = 8,
    hbmDataWidth:    Int = 1024,
    hbmFreqGbps:     Double = 9.6,
    lpddrChannels:   Int = 4,
    lpddrDataWidth:  Int = 256,
    cpuLatencyPct:   Int = 20,
  )

  case class FixedFuncConfig(
    rayTracing:    Boolean = true,
    nRayCores:    Int = 4,
    aiUpscaling:  Boolean = true,
    gemmTileSize: Int = 128,
    gemmDataWidth: Int = 64,
  )

  val defaultConfig: AegisConfig = AegisConfig()
}
