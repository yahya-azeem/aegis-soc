package aegis.interconnect

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class CrossbarMatrixTest extends AnyFlatSpec with ChiselSim {
  behavior of "CrossbarMatrix"

  it should "forward a CPU request to the memory and return its response" in {
    simulate(new CrossbarMatrix) { dut =>
      // accept a CPU request while idle and confirm it stalls new ones
      dut.io.cpu_a_valid.poke(true.B)
      dut.io.cpu_a_bits.poke("h100".U)
      dut.io.cpu_a_ready.expect(true.B)
      dut.clock.step()
      dut.io.cpu_a_valid.poke(false.B)

      dut.io.cpu_a_ready.expect(false.B)
      dut.io.mem_cpu_req.valid.expect(true.B)
      dut.io.mem_cpu_req.bits.addr.expect("h100".U)
      dut.io.mem_cpu_req.ready.poke(true.B)
      dut.clock.step()

      // drive the response; it should appear on the TL d channel and retire the request
      dut.io.mem_cpu_resp.valid.poke(true.B)
      dut.io.mem_cpu_resp.bits.poke("hBEEF".U(512.W))
      dut.io.cpu_d_valid.expect(true.B)
      dut.io.cpu_d_bits.expect("hBEEF".U(512.W))
      dut.io.cpu_d_ready.poke(true.B)
      dut.clock.step()
      dut.io.mem_cpu_resp.valid.poke(false.B)

      dut.io.cpu_a_ready.expect(true.B)
      dut.io.mem_cpu_req.valid.expect(false.B)
    }
  }

  it should "forward a GPU request and return its response independently" in {
    simulate(new CrossbarMatrix) { dut =>
      dut.io.gpu_a_valid.poke(true.B)
      dut.io.gpu_a_bits.poke("h7000".U)
      dut.clock.step()
      dut.io.gpu_a_valid.poke(false.B)

      dut.io.mem_gpu_req.valid.expect(true.B)
      dut.io.mem_gpu_req.bits.addr.expect("h7000".U)
      dut.io.mem_gpu_req.ready.poke(true.B)
      dut.clock.step()

      dut.io.mem_gpu_resp.valid.poke(true.B)
      dut.io.mem_gpu_resp.bits.poke("hCAFE".U(512.W))
      dut.io.gpu_d_valid.expect(true.B)
      dut.io.gpu_d_bits.expect("hCAFE".U(512.W))
      dut.io.gpu_d_ready.poke(true.B)
      dut.clock.step()
    }
  }

  it should "route CPU and GPU requests concurrently without interference" in {
    simulate(new CrossbarMatrix) { dut =>
      dut.io.cpu_a_valid.poke(true.B)
      dut.io.cpu_a_bits.poke("h100".U)
      dut.io.gpu_a_valid.poke(true.B)
      dut.io.gpu_a_bits.poke("h7000".U)
      dut.clock.step()
      dut.io.cpu_a_valid.poke(false.B)
      dut.io.gpu_a_valid.poke(false.B)

      dut.io.mem_cpu_req.bits.addr.expect("h100".U)
      dut.io.mem_gpu_req.bits.addr.expect("h7000".U)

      dut.io.mem_cpu_resp.valid.poke(true.B)
      dut.io.mem_cpu_resp.bits.poke("h1111".U(512.W))
      dut.io.cpu_d_ready.poke(true.B)
      dut.clock.step()
      dut.io.mem_cpu_resp.valid.poke(false.B)

      assert(dut.io.mem_cpu_req.valid.peek().litToBoolean == false, "cpu pending not cleared")
    }
  }
}