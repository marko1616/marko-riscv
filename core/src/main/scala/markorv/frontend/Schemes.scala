package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.cache._
import markorv.backend.EXUEnum
import markorv.backend.ALUOpcode
import markorv.backend.MDUOpcode
import markorv.backend.LoadStoreOpcode
import markorv.backend.BranchOpcode
import markorv.backend.MISCOpcode

trait BaseOpcode {
    val OP_LUI      = "b0110111".U(7.W)
    val OP_AUIPC    = "b0010111".U(7.W)
    val OP_IMM      = "b0010011".U(7.W)
    val OP_IMM32    = "b0011011".U(7.W)
    val OP          = "b0110011".U(7.W)
    val OP_32       = "b0111011".U(7.W)
    val OP_LOAD     = "b0000011".U(7.W)
    val OP_STOR     = "b0100011".U(7.W)
    val OP_JAL      = "b1101111".U(7.W)
    val OP_JALR     = "b1100111".U(7.W)
    val OP_BRANCH   = "b1100011".U(7.W)
    val OP_SYSTEM   = "b1110011".U(7.W)
    val OP_MISC_MEM = "b0001111".U(7.W)
    val OP_AMO      = "b0101111".U(7.W)
}

object InstrStatus extends ChiselEnum {
    val instrOk32, instrOk16, instrPageFaultLow, instrPmaFaultLow, instrPageFaultHigh, instrPmaFaultHigh, reserved1, reserved2 = Value
}

class PreFetchedLine(implicit val config: CoreConfig) extends Bundle {
    val code = new ICacheCode.Type
    val data = UInt((8 * config.icacheConfig.dataBytes).W)
}

class Instruction extends Bundle {
    val rawBits = UInt(32.W)
    val status = new InstrStatus.Type

    def isCompressed: Bool = rawBits(1, 0) =/= "b11".U
    def expandedBits: UInt = Mux(isCompressed, CompressedDecoder.expand(rawBits), rawBits)
    def opcodeBits: UInt = expandedBits(6, 0)

    def instructionLengthBytes: UInt = Mux(isCompressed, 2.U, 4.U)
    def fromUInt(rawBits: UInt) = {
        when(rawBits(1, 0) =/= "b11".U) {
            this.rawBits := rawBits(15, 0)
        }.otherwise {
            this.rawBits := rawBits
        }
    }

    def asInstruction32: Instruction32 = {
        val instr32 = WireInit(new Instruction32().zero)
        instr32.fromUInt(expandedBits)
        instr32.status := status
        instr32
    }
}

class Instruction32 extends Bundle {
    val rawBits = UInt(32.W)
    val status = new InstrStatus.Type

    def fromUInt(rawBits: UInt) = {
        this.rawBits := rawBits
    }
    def opcode: UInt = rawBits(6, 0)
}

class RTypeInstruction extends Instruction32 {
    def rd     = rawBits(11, 7)
    def funct3 = rawBits(14, 12)
    def rs1    = rawBits(19, 15)
    def rs2    = rawBits(24, 20)
    def funct7 = rawBits(31, 25)
}

class ITypeInstruction extends Instruction32 {
    def rd     = rawBits(11, 7)
    def funct3 = rawBits(14, 12)
    def rs1    = rawBits(19, 15)
    def imm12  = rawBits(31, 20)
}

class STypeInstruction extends Instruction32 {
    def imm4_0  = rawBits(11, 7)
    def funct3  = rawBits(14, 12)
    def rs1     = rawBits(19, 15)
    def rs2     = rawBits(24, 20)
    def imm11_5 = rawBits(31, 25)
    def imm12   = Cat(imm11_5, imm4_0)
}

class BTypeInstruction extends Instruction32 {
    def imm11   = rawBits(7)
    def imm4_1  = rawBits(11, 8)
    def funct3  = rawBits(14, 12)
    def rs1     = rawBits(19, 15)
    def rs2     = rawBits(24, 20)
    def imm10_5 = rawBits(30, 25)
    def imm12   = rawBits(31)
    def imm     = Cat(imm12, imm11, imm10_5, imm4_1, 0.U(1.W))
}

class UTypeInstruction extends Instruction32 {
    def rd  = rawBits(11, 7)
    def imm20 = rawBits(31, 12)
}

class JTypeInstruction extends Instruction32 {
    def rd       = rawBits(11, 7)
    def imm19_12 = rawBits(19, 12)
    def imm11    = rawBits(20)
    def imm10_1  = rawBits(30, 21)
    def imm20    = rawBits(31)
    def imm      = Cat(imm20, imm19_12, imm11, imm10_1, 0.U(1.W))
}

class DecodedParams extends Bundle {
    val source1 = UInt(64.W)
    val source2 = UInt(64.W)
    val rd = UInt(5.W)
    val pc = UInt(64.W)
}

class LogicRegRequests extends Bundle {
    val lrs1 = UInt(5.W)
    val lrs2 = UInt(5.W)
}

class PhyRegRequests(implicit val c: CoreConfig) extends Bundle {
    val prs1Valid = Bool()
    val prs2Valid = Bool()
    val prs1IsRd = Bool()
    val prs2IsRd = Bool()
    val prs1 = UInt(log2Ceil(c.regFileSize).W)
    val prs2 = UInt(log2Ceil(c.regFileSize).W)
}

class ExuOpcode extends Bundle {
    val aluOpcode = new ALUOpcode
    val lsuOpcode = new LoadStoreOpcode
    val miscOpcode = new MISCOpcode
    val branchOpcode = new BranchOpcode
    val mduOpcode = new MDUOpcode
}

class IssueTask extends Bundle {
    val exu = new EXUEnum.Type
    val exuOpcode = new ExuOpcode
    val predTaken = Bool()
    val predPc = UInt(64.W)
    val params = new DecodedParams
    val lregReq = new LogicRegRequests
}

class InstrDecodeTask extends Bundle {
    val instr = new Instruction32
    val predTaken = Bool()
    val predPc = UInt(64.W)
    val pc = UInt(64.W)
}

class FetchQueueEntities extends Bundle {
    val instr = new Instruction
    val predTaken = Bool()
    val predPc = UInt(64.W)
}