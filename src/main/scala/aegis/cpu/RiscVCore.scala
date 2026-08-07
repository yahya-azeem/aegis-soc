package aegis.cpu

import chisel3._
import chisel3.util._

/**
 * A real, self-contained in-order RV32I core.
 *
 * Fetches a program from its own instruction memory, decodes RV32I, computes
 * through an ALU, takes branches, and performs word loads/stores against its
 * own data memory (loads write back through an explicit wait state).
 * Single-issue and in-order, no speculation -- correctness over speed. This
 * is the Phase-2 CPU prototype.
 */
class RiscVICore(nWords: Int = 256, nDWords: Int = 256) extends Module {
  val IW = 32
  val io = IO(new Bundle {
    val prog_we   = Input(Bool())
    val prog_addr = Input(UInt(log2Ceil(nWords).W))
    val prog_data = Input(UInt(IW.W))
    val start     = Input(Bool())
    val halt      = Output(Bool())
    val pc        = Output(UInt(IW.W))
    val regs      = Output(Vec(32, UInt(IW.W)))
    val dmem      = Output(Vec(nDWords, UInt(IW.W)))
    val inst_out  = Output(UInt(IW.W))
  })

  val imem = RegInit(VecInit(Seq.fill(nWords)(0.U(IW.W))))
  val dmem = RegInit(VecInit(Seq.fill(nDWords)(0.U(IW.W))))
  val rf   = RegInit(VecInit(Seq.fill(32)(0.U(IW.W))))
  val pc   = RegInit(0.U(IW.W))

  val running = RegInit(false.B)
  when(io.start) { running := true.B }
  val halted = RegInit(false.B)

  when(io.prog_we) { imem(io.prog_addr) := io.prog_data }

  // ---- load-wait state ----
  val loadWait = RegInit(false.B)
  val ldAddr   = RegInit(0.U(IW.W))
  val ldRd     = RegInit(0.U(5.W))
  val ldByte   = RegInit(false.B)
  val ldHalf   = RegInit(false.B)
  val ldSigned = RegInit(false.B)

  val inst = imem((pc >> 2)(log2Ceil(nWords) - 1, 0))
  val opcode = inst(6, 0)
  val rd  = inst(11, 7)
  val f3  = inst(14, 12)
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)

  val rs1v = rf(rs1)
  val rs2v = rf(rs2)

  // ---- immediates ----
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7),
                  inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = inst(31, 12) << 12
  val immJ = Cat(Fill(11, inst(31)), inst(19, 12), inst(20),
                  inst(30, 21), 0.U(1.W))

  // ---- opcode decode ----
  val isLoad   = opcode === 0x03.U
  val isStore  = opcode === 0x23.U
  val isBranch = opcode === 0x63.U
  val isJal    = opcode === 0x6f.U
  val isJalr   = opcode === 0x67.U
  val isOpImm  = opcode === 0x13.U
  val isOp     = opcode === 0x33.U
  val isLui    = opcode === 0x37.U
  val isAuipc  = opcode === 0x17.U
  val isHalt   = inst === "hffffffff".U(IW.W)

  // ---- operand 2 / ALU ----
  val u2 = Mux(isOp, rs2v, Mux(isStore, immS, immI))
  val addr = rs1v + u2
  val shiftAmt = Mux(isOpImm, immI(4, 0), rs2v(4, 0))
  val sltu = Mux(rs1v < u2, 1.U, 0.U)
  val slt  = Mux(rs1v.asSInt < u2.asSInt, 1.U, 0.U)

  val alu = Wire(UInt(IW.W))
  alu := 0.U
  switch(f3) {
    is(0.U) { alu := Mux(isOp && inst(30), rs1v - rs2v, rs1v + u2) }
    is(1.U) { alu := rs1v << shiftAmt }
    is(2.U) { alu := slt }
    is(3.U) { alu := sltu }
    is(4.U) { alu := rs1v ^ u2 }
    is(5.U) { alu := Mux(inst(30), (rs1v.asSInt >> shiftAmt).asUInt, rs1v >> shiftAmt) }
    is(6.U) { alu := rs1v | u2 }
    is(7.U) { alu := rs1v & u2 }
  }

  // ---- write-back value ----
  val wbVal = Wire(UInt(IW.W))
  wbVal := alu
  when(isLui)   { wbVal := immU }
  when(isAuipc) { wbVal := pc + immU }
  when(isJal || isJalr) { wbVal := pc + 4.U }
  val wbEn = (isOp || isOpImm || isLui || isAuipc || isJal || isJalr) && rd =/= 0.U

  // ---- branch / next PC ----
  val eq  = rs1v === rs2v
  val ne  = !eq
  val lt  = rs1v.asSInt < rs2v.asSInt
  val ge  = !lt
  val ltu = rs1v < rs2v
  val geu = !ltu
  val brTaken = isBranch && (MuxLookup(f3, false.B)(Seq(
    0.U -> eq, 1.U -> ne, 4.U -> lt, 5.U -> ge, 6.U -> ltu, 7.U -> geu)))

  val nextPc = Wire(UInt(IW.W))
  nextPc := pc + 4.U
  when(isBranch && brTaken) { nextPc := pc + immB }
  when(isJal)               { nextPc := pc + immJ }
  when(isJalr)              { nextPc := Cat(addr(31, 1), 0.U(1.W)) }

  // ---- load result ----
  val lwIdx = ldAddr(log2Ceil(nDWords) + 1, 2)
  val loShift = dmem(lwIdx) >> (ldAddr(1, 0) * 8.U)
  val loByte  = loShift(7, 0)
  val loHalf  = loShift(15, 0)
  val sigByte = Mux(ldSigned && loByte(7), "hffffff00".U(IW.W), 0.U)
  val sigHalf = Mux(ldSigned && loHalf(15), "hffff0000".U(IW.W), 0.U)
  val loadRes = Mux(ldByte, sigByte | loByte,
                Mux(ldHalf, sigHalf | loHalf, loShift))

  // ---- execution ----
  when(running && !halted) {
    when(loadWait) {
      when(ldRd =/= 0.U) { rf(ldRd) := loadRes }
      loadWait := false.B
    }.elsewhen(isHalt) {
      halted := true.B
    }.otherwise {
      when(wbEn) { rf(rd) := wbVal }
      when(isLoad) {
        ldAddr := addr
        ldRd   := rd
        ldByte := (f3 === 0.U) || (f3 === 4.U)
        ldHalf := (f3 === 1.U) || (f3 === 5.U)
        ldSigned := (f3 === 0.U) || (f3 === 1.U) || (f3 === 2.U)
        loadWait := true.B
      }.elsewhen(isStore) {
        dmem(addr(log2Ceil(nDWords) + 1, 2)) := rs2v
      }
      pc := nextPc
    }
  }

  io.halt := halted
  io.pc := pc
  io.regs := rf
  io.dmem := dmem
  io.inst_out := inst
}
object RiscVICore extends App {
  emitVerilog(new RiscVICore(), Array("--target-dir", "build/rtl"))
}