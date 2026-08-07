package aegis.cpu

/** Minimal RISC-V (RV32) instruction encoder used to build CPU test programs. */
object Asm {
  private def bit(x: Int, i: Int): Int = (x >> i) & 1
  private def mask(v: Int, hi: Int, lo: Int): Int = (v >> lo) & ((1 << (hi - lo + 1)) - 1)

  private def rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int, op: Int): Int =
    (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | op
  private def iType(imm: Int, rs1: Int, funct3: Int, rd: Int, op: Int): Int =
    ((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | op
  private def sType(imm: Int, rs2: Int, rs1: Int, funct3: Int, op: Int): Int =
    (mask(imm, 11, 5) << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (mask(imm, 4, 0) << 7) | op
  private def bType(imm: Int, rs2: Int, rs1: Int, funct3: Int, op: Int): Int =
    (bit(imm, 12) << 31) | (mask(imm, 10, 5) << 25) | (rs2 << 20) | (rs1 << 15) |
      (funct3 << 12) | (mask(imm, 4, 1) << 8) | (bit(imm, 11) << 7) | op
  private def uType(imm: Int, rd: Int, op: Int): Int = ((imm & 0xfffff) << 12) | (rd << 7) | op
  private def jType(imm: Int, rd: Int, op: Int): Int =
    (bit(imm, 20) << 31) | (mask(imm, 10, 1) << 21) | (bit(imm, 11) << 20) |
      (mask(imm, 19, 12) << 12) | (rd << 7) | op

  def nop: Int = iType(0, 0, 0, 0, 0x13)

  def add(rd: Int, rs1: Int, rs2: Int): Int = rType(0, rs2, rs1, 0, rd, 0x33)
  def sub(rd: Int, rs1: Int, rs2: Int): Int = rType(0x20, rs2, rs1, 0, rd, 0x33)
  def and(rd: Int, rs1: Int, rs2: Int): Int = rType(0, rs2, rs1, 7, rd, 0x33)
  def or (rd: Int, rs1: Int, rs2: Int): Int = rType(0, rs2, rs1, 6, rd, 0x33)
  def xor(rd: Int, rs1: Int, rs2: Int): Int = rType(0, rs2, rs1, 4, rd, 0x33)
  def sll(rd: Int, rs1: Int, rs2: Int): Int = rType(0, rs2, rs1, 1, rd, 0x33)
  def srl(rd: Int, rs1: Int, rs2: Int): Int = rType(0, rs2, rs1, 5, rd, 0x33)
  def sra(rd: Int, rs1: Int, rs2: Int): Int = rType(0x20, rs2, rs1, 5, rd, 0x33)
  def slt(rd: Int, rs1: Int, rs2: Int): Int = rType(0, rs2, rs1, 2, rd, 0x33)
  def sltu(rd: Int, rs1: Int, rs2: Int): Int = rType(0, rs2, rs1, 3, rd, 0x33)

  def addi(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 0, rd, 0x13)
  def slli(rd: Int, rs1: Int, sh: Int): Int = iType(sh, rs1, 1, rd, 0x13)
  def srli(rd: Int, rs1: Int, sh: Int): Int = iType(sh, rs1, 5, rd, 0x13)
  def srai(rd: Int, rs1: Int, sh: Int): Int = iType(0x400 | sh, rs1, 5, rd, 0x13)
  def andi(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 7, rd, 0x13)
  def ori (rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 6, rd, 0x13)
  def xori(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 4, rd, 0x13)
  def slti(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 2, rd, 0x13)
  def sltiu(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 3, rd, 0x13)

  def lw(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 2, rd, 0x03)
  def lh(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 1, rd, 0x03)
  def lb(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 0, rd, 0x03)
  def lbu(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 4, rd, 0x03)
  def lhu(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 5, rd, 0x03)

  def sw(rs1: Int, rs2: Int, imm: Int): Int = sType(imm, rs2, rs1, 2, 0x23)
  def sh(rs1: Int, rs2: Int, imm: Int): Int = sType(imm, rs2, rs1, 1, 0x23)
  def sb(rs1: Int, rs2: Int, imm: Int): Int = sType(imm, rs2, rs1, 0, 0x23)

  def beq(rs1: Int, rs2: Int, off: Int): Int = bType(off, rs2, rs1, 0, 0x63)
  def bne(rs1: Int, rs2: Int, off: Int): Int = bType(off, rs2, rs1, 1, 0x63)
  def blt(rs1: Int, rs2: Int, off: Int): Int = bType(off, rs2, rs1, 4, 0x63)
  def bge(rs1: Int, rs2: Int, off: Int): Int = bType(off, rs2, rs1, 5, 0x63)
  def bltu(rs1: Int, rs2: Int, off: Int): Int = bType(off, rs2, rs1, 6, 0x63)
  def bgeu(rs1: Int, rs2: Int, off: Int): Int = bType(off, rs2, rs1, 7, 0x63)

  def lui(rd: Int, imm20: Int): Int = uType(imm20, rd, 0x37)
  def auipc(rd: Int, imm20: Int): Int = uType(imm20, rd, 0x17)
  def jal(rd: Int, off: Int): Int = jType(off, rd, 0x6f)
  def jalr(rd: Int, rs1: Int, imm: Int): Int = iType(imm, rs1, 0, rd, 0x67)

  def halt: Int = 0xffffffff
}