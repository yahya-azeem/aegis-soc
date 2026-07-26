package aegis

import chisel3._
import chisel3.util._

package object util {
  def log2Ceil(x: Int): Int = {
    require(x > 0)
    1 << (32 - Integer.numberOfLeadingZeros(x - 1))
  }

  implicit class UIntOps(val u: UInt) extends AnyVal {
    def toBytes: UInt = u << 3
    def toKB: UInt = u << 10
    def toMB: UInt = u << 20
    def toGB: UInt = u << 30
  }

  object Const {
    val WORD_BYTES = 4
    val DWORD_BYTES = 8
    val CACHE_LINE_BYTES = 64
    val PAGE_SIZE = 4096
    val DRAM_BASE = 0x80000000L
    val MROM_BASE = 0x200000L
    val CLINT_BASE = 0x2000000L
    val PLIC_BASE = 0xC000000L
    val UART_BASE = 0x10000000L
    val VIRTIO_BASE = 0x10001000L
  }
}
