package markorv.frontend

import chisel3._
import chisel3.util._

object CompressedDecoder extends BaseOpcode {
    private val COMPRESSED_QUADRANT_0 = "b00".U(2.W)
    private val COMPRESSED_QUADRANT_1 = "b01".U(2.W)
    private val COMPRESSED_QUADRANT_2 = "b10".U(2.W)

    private val FUNCT3_ADDI4SPN = "b000".U(3.W)
    private val FUNCT3_LW       = "b010".U(3.W)
    private val FUNCT3_LD       = "b011".U(3.W)
    private val FUNCT3_SW       = "b110".U(3.W)
    private val FUNCT3_SD       = "b111".U(3.W)

    private val FUNCT3_ADDI     = "b000".U(3.W)
    private val FUNCT3_ADDIW    = "b001".U(3.W)
    private val FUNCT3_LI       = "b010".U(3.W)
    private val FUNCT3_LUI      = "b011".U(3.W)
    private val FUNCT3_MISC_ALU = "b100".U(3.W)
    private val FUNCT3_J        = "b101".U(3.W)
    private val FUNCT3_BEQZ     = "b110".U(3.W)
    private val FUNCT3_BNEZ     = "b111".U(3.W)

    private val FUNCT3_SLLI      = "b000".U(3.W)
    private val FUNCT3_LWSP      = "b010".U(3.W)
    private val FUNCT3_LDSP      = "b011".U(3.W)
    private val FUNCT3_JR_MV_ADD = "b100".U(3.W)
    private val FUNCT3_SWSP      = "b110".U(3.W)
    private val FUNCT3_SDSP      = "b111".U(3.W)

    private val FUNCT2_SRLI  = "b00".U(2.W)
    private val FUNCT2_SRAI  = "b01".U(2.W)
    private val FUNCT2_ANDI  = "b10".U(2.W)
    private val FUNCT2_ARITH = "b11".U(2.W)

    private val FUNCT2_SUB = "b00".U(2.W)
    private val FUNCT2_XOR = "b01".U(2.W)
    private val FUNCT2_OR  = "b10".U(2.W)
    private val FUNCT2_AND = "b11".U(2.W)

    private val FUNCT2_SUBW = "b00".U(2.W)
    private val FUNCT2_ADDW = "b01".U(2.W)

    private val FUNCT3_ADDI_SUB_MV_ADD_JR_JALR = "b000".U(3.W)
    private val FUNCT3_SLL                     = "b001".U(3.W)
    private val FUNCT3_SLT                     = "b010".U(3.W)
    private val FUNCT3_XOR32                   = "b100".U(3.W)
    private val FUNCT3_SRL_SRA                 = "b101".U(3.W)
    private val FUNCT3_OR32                    = "b110".U(3.W)
    private val FUNCT3_AND32                   = "b111".U(3.W)

    private val FUNCT7_ADD = "b0000000".U(7.W)
    private val FUNCT7_SUB = "b0100000".U(7.W)

    private val ZERO_REGISTER  = 0.U(5.W)
    private val RETURN_ADDRESS = 1.U(5.W)
    private val STACK_POINTER  = 2.U(5.W)

    private def signExtend(value: UInt, width: Int): UInt =
        Cat(Fill(width - value.getWidth, value(value.getWidth - 1)), value)

    private def compressedPrimeRegister(bits: UInt): UInt =
        Cat("b01".U(2.W), bits)

    private def encodeIType(
        immediate12: UInt,
        sourceRegister1: UInt,
        funct3: UInt,
        destinationRegister: UInt,
        opcode: UInt
    ): UInt =
        Cat(
          immediate12(11, 0),
          sourceRegister1(4, 0),
          funct3(2, 0),
          destinationRegister(4, 0),
          opcode(6, 0)
        )

    private def encodeRType(
        funct7: UInt,
        sourceRegister2: UInt,
        sourceRegister1: UInt,
        funct3: UInt,
        destinationRegister: UInt,
        opcode: UInt
    ): UInt =
        Cat(
          funct7(6, 0),
          sourceRegister2(4, 0),
          sourceRegister1(4, 0),
          funct3(2, 0),
          destinationRegister(4, 0),
          opcode(6, 0)
        )

    private def encodeSType(
        immediate12: UInt,
        sourceRegister1: UInt,
        sourceRegister2: UInt,
        funct3: UInt,
        opcode: UInt
    ): UInt =
        Cat(
          immediate12(11, 5),
          sourceRegister2(4, 0),
          sourceRegister1(4, 0),
          funct3(2, 0),
          immediate12(4, 0),
          opcode(6, 0)
        )

    private def encodeBType(
        immediate13: UInt,
        sourceRegister1: UInt,
        sourceRegister2: UInt,
        funct3: UInt,
        opcode: UInt
    ): UInt =
        Cat(
          immediate13(12),
          immediate13(10, 5),
          sourceRegister2(4, 0),
          sourceRegister1(4, 0),
          funct3(2, 0),
          immediate13(4, 1),
          immediate13(11),
          opcode(6, 0)
        )

    private def encodeUType(
        immediate20: UInt,
        destinationRegister: UInt,
        opcode: UInt
    ): UInt =
        Cat(
          immediate20(19, 0),
          destinationRegister(4, 0),
          opcode(6, 0)
        )

    private def encodeJType(
        immediate21: UInt,
        destinationRegister: UInt,
        opcode: UInt
    ): UInt =
        Cat(
          immediate21(20),
          immediate21(10, 1),
          immediate21(11),
          immediate21(19, 12),
          destinationRegister(4, 0),
          opcode(6, 0)
        )

    private def addi4spnImmediate(compressed: UInt): UInt =
        Cat(
          0.U(2.W),
          compressed(10, 7),
          compressed(12, 11),
          compressed(5),
          compressed(6),
          0.U(2.W)
        )

    private def addi16spImmediate(compressed: UInt): UInt =
        signExtend(
          Cat(
            compressed(12),
            compressed(4, 3),
            compressed(5),
            compressed(2),
            compressed(6),
            0.U(4.W)
          ),
          12
        )

    private def ciImmediate(compressed: UInt): UInt =
        signExtend(Cat(compressed(12), compressed(6, 2)), 12)

    private def ciShiftAmount(compressed: UInt): UInt =
        Cat(compressed(12), compressed(6, 2))

    private def cbBranchOffset(compressed: UInt): UInt =
        signExtend(
          Cat(
            compressed(12),
            compressed(6, 5),
            compressed(2),
            compressed(11, 10),
            compressed(4, 3),
            0.U(1.W)
          ),
          13
        )

    private def cjJumpOffset(compressed: UInt): UInt =
        signExtend(
          Cat(
            compressed(12),
            compressed(8),
            compressed(10, 9),
            compressed(6),
            compressed(7),
            compressed(2),
            compressed(11),
            compressed(5, 3),
            0.U(1.W)
          ),
          21
        )

    private def clWordOffset(compressed: UInt): UInt =
        Cat(
          0.U(5.W),
          compressed(5),
          compressed(12, 10),
          compressed(6),
          0.U(2.W)
        )

    private def clDoubleOffset(compressed: UInt): UInt =
        Cat(
          0.U(4.W),
          compressed(6, 5),
          compressed(12, 10),
          0.U(3.W)
        )

    private def lwspOffset(compressed: UInt): UInt =
        Cat(
          0.U(4.W),
          compressed(3, 2),
          compressed(12),
          compressed(6, 4),
          0.U(2.W)
        )

    private def ldspOffset(compressed: UInt): UInt =
        Cat(
          0.U(3.W),
          compressed(4, 2),
          compressed(12),
          compressed(6, 5),
          0.U(3.W)
        )

    private def swspOffset(compressed: UInt): UInt =
        Cat(
          0.U(4.W),
          compressed(8, 7),
          compressed(12, 9),
          0.U(2.W)
        )

    private def sdspOffset(compressed: UInt): UInt =
        Cat(
          0.U(3.W),
          compressed(9, 7),
          compressed(12, 10),
          0.U(3.W)
        )

    def expand(rawInstruction: UInt): UInt = {
        val c = rawInstruction(15, 0)

        val quadrant = c(1, 0)
        val funct3   = c(15, 13)

        val rd  = c(11, 7)
        val rs1 = c(11, 7)
        val rs2 = c(6, 2)

        val primeRs1 = compressedPrimeRegister(c(9, 7))
        val primeRd  = compressedPrimeRegister(c(4, 2))
        val primeRs2 = compressedPrimeRegister(c(4, 2))

        val miscAluSubopcode    = c(11, 10)
        val arithmeticSubopcode = c(6, 5)

        val immAddi4spn = addi4spnImmediate(c)
        val immAddi16sp = addi16spImmediate(c)
        val immCI       = ciImmediate(c)
        val shamtCI     = ciShiftAmount(c)
        val immCB       = cbBranchOffset(c)
        val immCJ       = cjJumpOffset(c)
        val immCLW      = clWordOffset(c)
        val immCLD      = clDoubleOffset(c)
        val immLWSP     = lwspOffset(c)
        val immLDSP     = ldspOffset(c)
        val immSWSP     = swspOffset(c)
        val immSDSP     = sdspOffset(c)

        val luiNzImm = Cat(c(12), c(6, 2))
        val luiImm20 = Cat(
          Fill(15, c(12)),
          c(6, 2)
        )

        val srliImm = Cat(0.U(6.W), shamtCI)
        val sraiImm = Cat("b010000".U(6.W), shamtCI)

        val instAddi4spn = encodeIType(
          immAddi4spn,
          STACK_POINTER,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          primeRd,
          OP_IMM
        )

        val instCLw = encodeIType(
          immCLW,
          primeRs1,
          FUNCT3_SLT,
          primeRd,
          OP_LOAD
        )

        val instCLd = encodeIType(
          immCLD,
          primeRs1,
          FUNCT3_LUI,
          primeRd,
          OP_LOAD
        )

        val instCSw = encodeSType(
          immCLW,
          primeRs1,
          primeRs2,
          FUNCT3_SLT,
          OP_STOR
        )

        val instCSd = encodeSType(
          immCLD,
          primeRs1,
          primeRs2,
          FUNCT3_LUI,
          OP_STOR
        )

        val instCAddi = encodeIType(
          immCI,
          rd,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          rd,
          OP_IMM
        )

        val instCAddiw = encodeIType(
          immCI,
          rd,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          rd,
          OP_IMM32
        )

        val instCLi = encodeIType(
          immCI,
          ZERO_REGISTER,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          rd,
          OP_IMM
        )

        val instCAddi16sp = encodeIType(
          immAddi16sp,
          STACK_POINTER,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          STACK_POINTER,
          OP_IMM
        )

        val instCLui = encodeUType(
          luiImm20,
          rd,
          OP_LUI
        )

        val instCSrli = encodeIType(
          srliImm,
          compressedPrimeRegister(c(9, 7)),
          FUNCT3_SRL_SRA,
          compressedPrimeRegister(c(9, 7)),
          OP_IMM
        )

        val instCSrai = encodeIType(
          sraiImm,
          compressedPrimeRegister(c(9, 7)),
          FUNCT3_SRL_SRA,
          compressedPrimeRegister(c(9, 7)),
          OP_IMM
        )

        val instCAndi = encodeIType(
          immCI,
          compressedPrimeRegister(c(9, 7)),
          FUNCT3_AND32,
          compressedPrimeRegister(c(9, 7)),
          OP_IMM
        )

        val instCSub = encodeRType(
          FUNCT7_SUB,
          compressedPrimeRegister(c(4, 2)),
          compressedPrimeRegister(c(9, 7)),
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          compressedPrimeRegister(c(9, 7)),
          OP
        )

        val instCXor = encodeRType(
          FUNCT7_ADD,
          compressedPrimeRegister(c(4, 2)),
          compressedPrimeRegister(c(9, 7)),
          FUNCT3_XOR32,
          compressedPrimeRegister(c(9, 7)),
          OP
        )

        val instCOr = encodeRType(
          FUNCT7_ADD,
          compressedPrimeRegister(c(4, 2)),
          compressedPrimeRegister(c(9, 7)),
          FUNCT3_OR32,
          compressedPrimeRegister(c(9, 7)),
          OP
        )

        val instCAnd = encodeRType(
          FUNCT7_ADD,
          compressedPrimeRegister(c(4, 2)),
          compressedPrimeRegister(c(9, 7)),
          FUNCT3_AND32,
          compressedPrimeRegister(c(9, 7)),
          OP
        )

        val instCSubw = encodeRType(
          FUNCT7_SUB,
          compressedPrimeRegister(c(4, 2)),
          compressedPrimeRegister(c(9, 7)),
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          compressedPrimeRegister(c(9, 7)),
          OP_32
        )

        val instCAddw = encodeRType(
          FUNCT7_ADD,
          compressedPrimeRegister(c(4, 2)),
          compressedPrimeRegister(c(9, 7)),
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          compressedPrimeRegister(c(9, 7)),
          OP_32
        )

        val instCJ = encodeJType(
          immCJ,
          ZERO_REGISTER,
          OP_JAL
        )

        val instCBeqz = encodeBType(
          immCB,
          compressedPrimeRegister(c(9, 7)),
          ZERO_REGISTER,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          OP_BRANCH
        )

        val instCBnez = encodeBType(
          immCB,
          compressedPrimeRegister(c(9, 7)),
          ZERO_REGISTER,
          FUNCT3_SLL,
          OP_BRANCH
        )

        val instCSlli = encodeIType(
          Cat(0.U(6.W), shamtCI),
          rd,
          FUNCT3_SLL,
          rd,
          OP_IMM
        )

        val instCLwsp = encodeIType(
          immLWSP,
          STACK_POINTER,
          FUNCT3_SLT,
          rd,
          OP_LOAD
        )

        val instCLdsp = encodeIType(
          immLDSP,
          STACK_POINTER,
          FUNCT3_LUI,
          rd,
          OP_LOAD
        )

        val instCJr = encodeIType(
          0.U(12.W),
          rs1,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          ZERO_REGISTER,
          OP_JALR
        )

        val instCMv = encodeRType(
          FUNCT7_ADD,
          rs2,
          ZERO_REGISTER,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          rd,
          OP
        )

        val instCEbreak = encodeIType(
          1.U(12.W),
          ZERO_REGISTER,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          ZERO_REGISTER,
          OP_SYSTEM
        )

        val instCJalr = encodeIType(
          0.U(12.W),
          rs1,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          RETURN_ADDRESS,
          OP_JALR
        )

        val instCAdd = encodeRType(
          FUNCT7_ADD,
          rs2,
          rd,
          FUNCT3_ADDI_SUB_MV_ADD_JR_JALR,
          rd,
          OP
        )

        val instCSwsp = encodeSType(
          immSWSP,
          STACK_POINTER,
          rs2,
          FUNCT3_SLT,
          OP_STOR
        )

        val instCSdsp = encodeSType(
          immSDSP,
          STACK_POINTER,
          rs2,
          FUNCT3_LUI,
          OP_STOR
        )

        val selAddi4spn =
            quadrant === COMPRESSED_QUADRANT_0 &&
                funct3 === FUNCT3_ADDI4SPN &&
                immAddi4spn =/= 0.U

        val selCLw =
            quadrant === COMPRESSED_QUADRANT_0 &&
                funct3 === FUNCT3_LW

        val selCLd =
            quadrant === COMPRESSED_QUADRANT_0 &&
                funct3 === FUNCT3_LD

        val selCSw =
            quadrant === COMPRESSED_QUADRANT_0 &&
                funct3 === FUNCT3_SW

        val selCSd =
            quadrant === COMPRESSED_QUADRANT_0 &&
                funct3 === FUNCT3_SD

        val selCAddi =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_ADDI

        val selCAddiw =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_ADDIW &&
                rd =/= ZERO_REGISTER

        val selCLi =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_LI

        val selCAddi16sp =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_LUI &&
                rd === STACK_POINTER &&
                immAddi16sp =/= 0.U

        val selCLui =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_LUI &&
                rd =/= STACK_POINTER &&
                luiNzImm =/= 0.U

        val selCSrli =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_MISC_ALU &&
                miscAluSubopcode === FUNCT2_SRLI

        val selCSrai =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_MISC_ALU &&
                miscAluSubopcode === FUNCT2_SRAI

        val selCAndi =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_MISC_ALU &&
                miscAluSubopcode === FUNCT2_ANDI

        val selCSub =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_MISC_ALU &&
                miscAluSubopcode === FUNCT2_ARITH &&
                c(12) === 0.U &&
                arithmeticSubopcode === FUNCT2_SUB

        val selCXor =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_MISC_ALU &&
                miscAluSubopcode === FUNCT2_ARITH &&
                c(12) === 0.U &&
                arithmeticSubopcode === FUNCT2_XOR

        val selCOr =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_MISC_ALU &&
                miscAluSubopcode === FUNCT2_ARITH &&
                c(12) === 0.U &&
                arithmeticSubopcode === FUNCT2_OR

        val selCAnd =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_MISC_ALU &&
                miscAluSubopcode === FUNCT2_ARITH &&
                c(12) === 0.U &&
                arithmeticSubopcode === FUNCT2_AND

        val selCSubw =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_MISC_ALU &&
                miscAluSubopcode === FUNCT2_ARITH &&
                c(12) === 1.U &&
                arithmeticSubopcode === FUNCT2_SUBW

        val selCAddw =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_MISC_ALU &&
                miscAluSubopcode === FUNCT2_ARITH &&
                c(12) === 1.U &&
                arithmeticSubopcode === FUNCT2_ADDW

        val selCJ =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_J

        val selCBeqz =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_BEQZ

        val selCBnez =
            quadrant === COMPRESSED_QUADRANT_1 &&
                funct3 === FUNCT3_BNEZ

        val selCSlli =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_SLLI

        val selCLwsp =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_LWSP &&
                rd =/= ZERO_REGISTER

        val selCLdsp =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_LDSP &&
                rd =/= ZERO_REGISTER

        val selCJr =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_JR_MV_ADD &&
                c(12) === 0.U &&
                rs2 === ZERO_REGISTER &&
                rs1 =/= ZERO_REGISTER

        val selCMv =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_JR_MV_ADD &&
                c(12) === 0.U &&
                rs2 =/= ZERO_REGISTER

        val selCEbreak =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_JR_MV_ADD &&
                c(12) === 1.U &&
                rs2 === ZERO_REGISTER &&
                rs1 === ZERO_REGISTER

        val selCJalr =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_JR_MV_ADD &&
                c(12) === 1.U &&
                rs2 === ZERO_REGISTER &&
                rs1 =/= ZERO_REGISTER

        val selCAdd =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_JR_MV_ADD &&
                c(12) === 1.U &&
                rs2 =/= ZERO_REGISTER

        val selCSwsp =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_SWSP

        val selCSdsp =
            quadrant === COMPRESSED_QUADRANT_2 &&
                funct3 === FUNCT3_SDSP

        val decodeCases = Seq(
          selAddi4spn  -> instAddi4spn,
          selCLw       -> instCLw,
          selCLd       -> instCLd,
          selCSw       -> instCSw,
          selCSd       -> instCSd,
          selCAddi     -> instCAddi,
          selCAddiw    -> instCAddiw,
          selCLi       -> instCLi,
          selCAddi16sp -> instCAddi16sp,
          selCLui      -> instCLui,
          selCSrli     -> instCSrli,
          selCSrai     -> instCSrai,
          selCAndi     -> instCAndi,
          selCSub      -> instCSub,
          selCXor      -> instCXor,
          selCOr       -> instCOr,
          selCAnd      -> instCAnd,
          selCSubw     -> instCSubw,
          selCAddw     -> instCAddw,
          selCJ        -> instCJ,
          selCBeqz     -> instCBeqz,
          selCBnez     -> instCBnez,
          selCSlli     -> instCSlli,
          selCLwsp     -> instCLwsp,
          selCLdsp     -> instCLdsp,
          selCJr       -> instCJr,
          selCMv       -> instCMv,
          selCEbreak   -> instCEbreak,
          selCJalr     -> instCJalr,
          selCAdd      -> instCAdd,
          selCSwsp     -> instCSwsp,
          selCSdsp     -> instCSdsp
        )

        // For illegal instructions, we simply return the original instr. This will allow downstream to set xtval.
        val hit = decodeCases.map(_._1).reduce(_ || _)
        Mux(hit, Mux1H(decodeCases), rawInstruction)
    }
}
