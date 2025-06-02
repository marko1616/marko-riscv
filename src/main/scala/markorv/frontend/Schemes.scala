package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.backend.EXUEnum
import markorv.backend.ALUOpcode
import markorv.backend.MDUOpcode
import markorv.backend.LoadStoreOpcode
import markorv.backend.BranchOpcode
import markorv.backend.MISCOpcode

class Instruction extends Bundle {
    val rawBits = UInt(32.W)
    def fromUInt(raw: UInt): Unit = { this.rawBits := raw }
    def opcode  = rawBits(6, 0)
}

class RTypeInstruction extends Instruction {
    def rd     = rawBits(11, 7)
    def funct3 = rawBits(14, 12)
    def rs1    = rawBits(19, 15)
    def rs2    = rawBits(24, 20)
    def funct7 = rawBits(31, 25)
}

class ITypeInstruction extends Instruction {
    def rd     = rawBits(11, 7)
    def funct3 = rawBits(14, 12)
    def rs1    = rawBits(19, 15)
    def imm12  = rawBits(31, 20)
}

class STypeInstruction extends Instruction {
    def imm4_0  = rawBits(11, 7)
    def funct3  = rawBits(14, 12)
    def rs1     = rawBits(19, 15)
    def rs2     = rawBits(24, 20)
    def imm11_5 = rawBits(31, 25)
    def imm12   = Cat(imm11_5, imm4_0)
}

class BTypeInstruction extends Instruction {
    def imm11   = rawBits(7)
    def imm4_1  = rawBits(11, 8)
    def funct3  = rawBits(14, 12)
    def rs1     = rawBits(19, 15)
    def rs2     = rawBits(24, 20)
    def imm10_5 = rawBits(30, 25)
    def imm12   = rawBits(31)
    def imm     = Cat(imm12, imm11, imm10_5, imm4_1, 0.U(1.W))
}

class UTypeInstruction extends Instruction {
    def rd  = rawBits(11, 7)
    def imm20 = rawBits(31, 12)
}

class JTypeInstruction extends Instruction {
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

class RegisterRequests extends Bundle {
    val source1 = UInt(5.W)
    val source2 = UInt(5.W)
}

class IssueTask extends Bundle {
    val operateUnit = new EXUEnum.Type
    val aluOpcode = new ALUOpcode()
    val lsuOpcode = new LoadStoreOpcode
    val miscOpcode = new MISCOpcode
    val branchOpcode = new BranchOpcode
    val mduOpcode = new MDUOpcode
    val predTaken = Bool()
    val predPc = UInt(64.W)
    val params = new DecodedParams
    val regRequests = new RegisterRequests
}

class InstrDecodeBundle extends Bundle {
    val instr = new Instruction
    val predTaken = Bool()
    val predPc = UInt(64.W)
    val pc = UInt(64.W)
}

class FetchQueueEntities extends Bundle {
    val instr = UInt(32.W)
    val isBranch = Bool()
    val predTaken = Bool()
    val predPc = UInt(64.W)
}