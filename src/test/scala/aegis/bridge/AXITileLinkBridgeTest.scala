package aegis.bridge

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class AXITileLinkBridgeTest extends AnyFlatSpec with ChiselSim {
  behavior of "AXITileLinkBridge"

  it should "convert an AXI write into a TileLink store and return BVALID" in {
    simulate(new AXITileLinkBridge(64, 512)) { dut =>
      dut.io.axi.AWVALID.poke(true.B)
      dut.io.axi.AWADDR.poke(0x1000.U)
      dut.clock.step()
      dut.io.axi.AWVALID.poke(false.B)

      dut.io.axi.WVALID.poke(true.B)
      dut.io.axi.WDATA.poke("hDEAD_BEEF".U(512.W))
      dut.clock.step()
      dut.io.axi.WVALID.poke(false.B)

      dut.io.tl.a_ready.poke(true.B)
      dut.clock.step()

      dut.io.axi.BVALID.expect(true.B)
      dut.io.axi.BREADY.poke(true.B)
      dut.clock.step()
      dut.io.axi.BVALID.expect(false.B)
    }
  }

  it should "emit the write address on the TileLink a channel" in {
    simulate(new AXITileLinkBridge(64, 512)) { dut =>
      dut.io.axi.AWVALID.poke(true.B)
      dut.io.axi.AWADDR.poke(0x1234.U)
      dut.clock.step()
      dut.io.axi.AWVALID.poke(false.B)

      dut.io.axi.WVALID.poke(true.B)
      dut.clock.step()
      dut.io.axi.WVALID.poke(false.B)

      dut.io.tl.a_valid.expect(true.B)
      dut.io.tl.a_bits.expect(BigInt("8000000000001234", 16).U)
      dut.io.tl.a_ready.poke(true.B)
      dut.clock.step()
    }
  }

  it should "convert an AXI read into a TileLink load and return the response data" in {
    simulate(new AXITileLinkBridge(64, 512)) { dut =>
      dut.io.axi.ARVALID.poke(true.B)
      dut.io.axi.ARADDR.poke(0x8000.U)
      dut.clock.step()
      dut.io.axi.ARVALID.poke(false.B)

      dut.io.tl.a_valid.expect(true.B)
      dut.io.tl.a_bits.expect(0x8000.U)
      dut.io.tl.a_ready.poke(true.B)
      dut.clock.step()

      dut.io.tl.d_ready.expect(true.B)
      dut.io.tl.d_valid.poke(true.B)
      dut.io.tl.d_bits.poke("hCAFE_F00D".U(512.W))
      dut.clock.step()
      dut.io.tl.d_valid.poke(false.B)

      dut.io.axi.RVALID.expect(true.B)
      dut.io.axi.RDATA.expect("hCAFE_F00D".U(512.W))
      dut.io.axi.RLAST.expect(true.B)
      dut.io.axi.RREADY.poke(true.B)
      dut.clock.step()
    }
  }
}