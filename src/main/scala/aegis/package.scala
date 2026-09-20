package object aegis {
  /**
   * Design-time configuration for the Aegis SoC.
   *
   * Only parameters that are genuinely instantiated by [[Top]] live here. The
   * internal geometry of each block is fixed in its own RTL module (see the
   * README for the modeled microarchitecture), so there is a single source of
   * truth for every size and frequency in the design.
   */
  case class AegisConfig(
    socName:      String = "Aegis",
    axiAddrWidth: Int = 64,
    axiDataWidth: Int = 512,
    vortexRtl:    Boolean = false, // BlackBox the real Vortex_axi RTL on the acc port
  )

  val defaultConfig: AegisConfig = AegisConfig()
}
