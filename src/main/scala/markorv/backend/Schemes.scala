package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.frontend._
import markorv.manage.CommitBundle
import markorv.manage.DisconEventEnum

object EXUEnum extends ChiselEnum {
    val alu, bru, lsu, mdu, misc = Value
}

object MultiplyDivisionUnitFunct3Op64 extends ChiselEnum {
    val mul    = Value("b000".U)
    val mulh   = Value("b001".U)
    val mulhsu = Value("b010".U)
    val mulhu  = Value("b011".U)
    val div    = Value("b100".U)
    val divu   = Value("b101".U)
    val rem    = Value("b110".U)
    val remu   = Value("b111".U)
}

object MultiplyDivisionUnitFunct3Op32 extends ChiselEnum {
    val mulw   = Value("b000".U)
    val divw   = Value("b100".U)
    val divuw  = Value("b101".U)
    val remw   = Value("b110".U)
    val remuw  = Value("b111".U)
}

object ALUFunct3Norm extends ChiselEnum {
    val add  = Value("b000".U)
    val sll  = Value("b001".U)
    val slt  = Value("b010".U)
    val sltu = Value("b011".U)
    val xor  = Value("b100".U)
    val srl  = Value("b101".U)
    val or   = Value("b110".U)
    val and  = Value("b111".U)
}

object BranchFunct extends ChiselEnum {
    val beq  = Value("b0000".U)
    val bne  = Value("b0001".U)
    val blt  = Value("b0100".U)
    val bge  = Value("b0101".U)
    val bltu = Value("b0110".U)
    val bgeu = Value("b0111".U)

    val jal  = Value("b1000".U)
    val jalr = Value("b1001".U)
}

object ALUFunct3SubSra extends ChiselEnum {
    val sub  = Value("b000".U)
    val sra  = Value("b101".U)
}

object LSUOpcode extends ChiselEnum {
    val load = Value("b000000".U)
    val amoadd = Value("b000001".U)
    val store = Value("b000010".U)
    val amoswap = Value("b000011".U)
    val lr = Value("b000101".U)
    val sc = Value("b000111".U)
    val amoxor = Value("b001001".U)
    val amoor = Value("b010001".U)
    val amoand = Value("b011001".U)
    val amomin = Value("b100001".U)
    val amomax = Value("b101001".U)
    val amominu = Value("b110001".U)
    val amomaxu = Value("b111001".U)
    def isamo(op: LSUOpcode.Type): Bool = op.asUInt(0) === 1.U
    def isload(op: LSUOpcode.Type): Bool = op === LSUOpcode.load
    def isstore(op: LSUOpcode.Type): Bool = op === LSUOpcode.store
}

object SystemOpMap extends ChiselEnum {
    val ecall = Value("h000000".U)
    val ebreak = Value("h002000".U)
    val wfi = Value("h20a000".U)
    val mret = Value("h604000".U)
}

class ALUOpcode extends Bundle {
    val op32 = Bool()
    val sraSub = Bool()
    val funct3 = UInt(3.W)

    def fromLui(rawInstr: Instruction, _regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new UTypeInstruction)
        val valid = WireInit(true.B)

        this.op32 := false.B
        this.sraSub := false.B
        this.funct3 := "b000".U

        params.source1 := (instr.imm20 << 12).sextu(64)
        params.source2 := 0.U
        params.rd := instr.rd
        valid
    }

    def fromAuipc(rawInstr: Instruction, _regReq: RegisterRequests, params: DecodedParams, pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new UTypeInstruction)
        val valid = WireInit(true.B)

        this.op32 := false.B
        this.sraSub := false.B
        this.funct3 := "b000".U

        params.source1 := (instr.imm20 << 12).sextu(64)
        params.source2 := pc
        params.rd := instr.rd
        valid
    }

    def fromImm(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(false.B)

        this.op32 := false.B
        this.funct3 := instr.funct3
        this.sraSub := instr.funct3 === "b101".U && instr.imm12(10)

        val (_, isValid) = ALUFunct3Norm.safe(instr.funct3)
        valid := isValid

        params.source2 := Mux(
            instr.funct3 === "b001".U || instr.funct3 === "b101".U,
            instr.imm12(5,0).sextu(64), // shamt6
            instr.imm12.sextu(64)
        )
        regReq.source1 := instr.rs1
        params.rd := instr.rd
        valid
    }

    def fromImm32(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(false.B)

        this.op32 := true.B
        this.funct3 := instr.funct3
        this.sraSub := instr.funct3 === "b101".U && instr.imm12(10)

        val (_, isValid) = ALUFunct3Norm.safe(instr.funct3)
        valid := isValid

        params.source2 := Mux(
            instr.funct3 === "b001".U || instr.funct3 === "b101".U,
            instr.imm12(4,0).sextu(64), // shamt5
            instr.imm12.sextu(64)
        )
        regReq.source1 := instr.rs1
        params.rd := instr.rd
        valid
    }

    def fromReg(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(false.B)

        this.op32 := false.B
        this.funct3 := instr.funct3
        this.sraSub := (instr.funct3 === "b101".U || instr.funct3 === "b000".U) && instr.funct7(5)

        val (_, isValidNorm) = ALUFunct3Norm.safe(instr.funct3)
        val (_, isValidSubSra) = ALUFunct3SubSra.safe(instr.funct3)
        valid := Mux(this.sraSub, isValidSubSra, isValidNorm)

        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }

    def fromReg32(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(false.B)

        this.op32 := true.B
        this.funct3 := instr.funct3
        this.sraSub := (instr.funct3 === "b101".U || instr.funct3 === "b000".U) && instr.funct7(5)

        val (_, isValidNorm) = ALUFunct3Norm.safe(instr.funct3)
        val (_, isValidSubSra) = ALUFunct3SubSra.safe(instr.funct3)
        valid := Mux(this.sraSub, isValidSubSra, isValidNorm)

        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }

    def getFunct3Norm(): ALUFunct3Norm.Type = {
        suppressEnumCastWarning {
            this.funct3.asTypeOf(new ALUFunct3Norm.Type)
        }
    }

    def getFunct3SubSra(): ALUFunct3SubSra.Type = {
        suppressEnumCastWarning {
            this.funct3.asTypeOf(new ALUFunct3SubSra.Type)
        }
    }
}

class ALUCommit(implicit override val c: CoreConfig) extends CommitBundle

class BranchOpcode extends Bundle {
    val funct = UInt(4.W)
    val offset = UInt(12.W)

    def fromBranch(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new BTypeInstruction)
        val valid = WireInit(false.B)

        val funct4 = Cat(0.U(1.W), instr.funct3)
        this.funct := funct4
        this.offset := instr.imm(12,1)
        val (_, isValid) = BranchFunct.safe(funct4)
        valid := isValid

        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        valid
    }

    def fromJal(rawInstr: Instruction, _regReq: RegisterRequests, params: DecodedParams, _pc: UInt) = {
        val instr = rawInstr.asTypeOf(new JTypeInstruction)
        val valid = WireInit(true.B)

        this.funct := "b1000".U
        params.rd := instr.rd
        valid
    }

    def fromJalr(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt) = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(true.B)

        this.funct := "b1001".U
        regReq.source1 := instr.rs1
        params.source2 := instr.imm12.sextu(64)
        params.rd := instr.rd
        valid
    }

    def getFunct(): BranchFunct.Type = {
        suppressEnumCastWarning {
            this.funct.asTypeOf(new BranchFunct.Type)
        }
    }
}

class BRUCommit(implicit override val c: CoreConfig) extends CommitBundle {
    val disconType = new DisconEventEnum.Type
    val recover = Bool()
    val recoverPc = UInt(64.W)
}

class LoadStoreOpcode extends Bundle {
    val funct = UInt(6.W)
    val size = UInt(3.W)

    def fromAmo(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(false.B)

        // Always with .aq.rl order
        this.funct := instr.funct7(6,2) ## 1.U(1.W)
        this.size := instr.funct3
        // width only in dword(64bits) or word(32bits)
        val (_, functValid) = LSUOpcode.safe(this.funct)
        val sizeValid = instr.funct3 === "b11".U || instr.funct3 === "b10".U
        valid := functValid && sizeValid
        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }

    def fromLoad(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt) = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(false.B)

        this.funct := 0.U(1.W) ## 0.U(1.W)
        this.size := instr.funct3
        valid := this.funct =/= "b111".U // There is no ldu
        // Issuer will sum up register request values from this instruction
        regReq.source1 := instr.rs1
        params.source1 := instr.imm12.sextu(64)
        params.rd := instr.rd
        valid
    }

    def fromStore(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt) = {
        val instr = rawInstr.asTypeOf(new STypeInstruction)
        val valid = WireInit(false.B)

        this.funct := 1.U(1.W) ## 0.U(1.W)
        this.size := instr.funct3
        valid := this.funct =/= "b111".U // There is no sdu
        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        // Issuer will sum up register request values from this instruction
        params.source1 := instr.imm12.sextu(64)
        valid
    }
}

class LSUCommit(implicit override val c: CoreConfig) extends CommitBundle {
    val disconType = new DisconEventEnum.Type
    val trap = Bool()
    val cause = UInt(16.W)
    val xtval = UInt(64.W)
}

class MDUOpcode extends Bundle {
    val op32 = Bool()
    val funct3 = UInt(3.W)

    def fromReg(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(false.B)

        this.op32 := false.B
        this.funct3 := instr.funct3

        val (_, op64Valid) = MultiplyDivisionUnitFunct3Op64.safe(instr.funct3)
        valid := op64Valid

        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }

    def fromReg32(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(false.B)

        this.op32 := true.B
        this.funct3 := instr.funct3

        val (_, op32Valid) = MultiplyDivisionUnitFunct3Op32.safe(instr.funct3)
        valid := op32Valid

        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }

    def getFunct3Op64(): MultiplyDivisionUnitFunct3Op64.Type = {
        suppressEnumCastWarning {
            this.funct3.asTypeOf(new MultiplyDivisionUnitFunct3Op64.Type)
        }
    }

    def getFunct3Op32(): MultiplyDivisionUnitFunct3Op32.Type = {
        suppressEnumCastWarning {
            this.funct3.asTypeOf(new MultiplyDivisionUnitFunct3Op32.Type)
        }
    }
}

class MDUCommit(implicit override val c: CoreConfig) extends CommitBundle

class MISCOpcode extends Bundle {
    val miscCsrFunct = UInt(4.W)
    val miscSysFunct = UInt(3.W)
    val miscMemFunct = UInt(2.W)

    def fromSys(rawInstr: Instruction, regReq: RegisterRequests, params: DecodedParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(false.B)

        val csrType = instr.funct3(1,0)
        when(csrType =/= 0.U) {
            val isImm = instr.funct3(2)
            val isCsrrw = csrType === 1.U
            val readEn = Mux(isCsrrw, instr.rd =/= 0.U, true.B)
            val writeEn = Mux(isCsrrw, true.B, instr.rs1 =/= 0.U)
            this.miscCsrFunct := readEn ## writeEn ## csrType
            params.source2 := instr.imm12
            when(isImm) {
                params.source1 := instr.rs1.zextu(64)
            } otherwise {
                regReq.source1 := instr.rs1
            }
            params.rd := instr.rd
            valid := true.B
        }.otherwise {
            val (sysFunct, sysValid) = SystemOpMap.safe((instr.imm12 ## instr.rs1 ## instr.funct3 ## instr.rd)(22,0))
            valid := sysValid
            this.miscSysFunct := Mux(sysValid, MuxLookup(sysFunct, 0.U)(Seq(
                SystemOpMap.ecall -> 1.U,
                SystemOpMap.ebreak -> 2.U,
                SystemOpMap.wfi -> 3.U,
                SystemOpMap.mret -> 4.U
            )),0.U)
        }
        valid
    }

    def fromMISCMem(rawInstr: Instruction, _regReq: RegisterRequests, params: DecodedParams, pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(false.B)

        when(instr.imm12 ## instr.rs1 ## instr.funct3 ## instr.rd === "h20".U) {
            // fence.i
            valid := true.B
            params.source1 := pc + 4.U
            this.miscMemFunct := 2.U
        }
        valid
    }
}

class MISCCommit(implicit override val c: CoreConfig) extends CommitBundle {
    val disconType = new DisconEventEnum.Type
    val trap = Bool()
    val cause = UInt(16.W)
    val xtval = UInt(64.W)
    val xret  = Bool()

    // For fence.i
    val recover = Bool()
    val recoverPc = UInt(64.W)
}