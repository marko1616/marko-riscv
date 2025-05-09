package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend._

object SystemOpMap extends ChiselEnum {
    val ecall = Value("h000000".U)
    val ebreak = Value("h002000".U)
    val wfi = Value("h20a000".U)
    val mret = Value("h604000".U)
}

class CommitBundle extends Bundle {
    val reg = UInt(5.W)
    val data = UInt(64.W)
}

class MUOpcode extends Bundle {
    val op32 = Bool()
    val funct3 = UInt(3.W)

    def fromReg(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(true.B)

        this.op32 := false.B
        this.funct3 := instr.funct3

        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }

    def fromReg32(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(true.B)

        this.op32 := true.B
        this.funct3 := instr.funct3

        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }
}

class ALUOpcode extends Bundle {
    val op32 = Bool()
    val sraSub = Bool()
    val funct3 = UInt(3.W)

    def fromLui(rawInstr: Instruction, _regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt): Bool = {
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

    def fromAuipc(rawInstr: Instruction, _regReq: RegisterRequests, params: DecoderOutParams, pc: UInt): Bool = {
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

    def fromImm(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(true.B)

        this.op32 := false.B
        this.funct3 := instr.funct3
        this.sraSub := instr.funct3 === "b101".U && instr.imm12(10)

        // slli/srli/srai use shamt, others use full imm12
        params.source2 := Mux(
            instr.funct3 === "b001".U || instr.funct3 === "b101".U,
            instr.imm12(5,0).sextu(64), // shamt6
            instr.imm12.sextu(64)
        )
        regReq.source1 := instr.rs1
        params.rd := instr.rd
        valid
    }

    def fromImm32(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(true.B)

        this.op32 := true.B
        this.funct3 := instr.funct3
        this.sraSub := instr.funct3 === "b101".U && instr.imm12(10)

        params.source2 := Mux(
            instr.funct3 === "b001".U || instr.funct3 === "b101".U,
            instr.imm12(4,0).sextu(64), // shamt5
            instr.imm12.sextu(64)
        )
        regReq.source1 := instr.rs1
        params.rd := instr.rd
        valid
    }

    def fromReg(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(true.B)

        this.op32 := false.B
        this.funct3 := instr.funct3
        this.sraSub := (instr.funct3 === "b101".U || instr.funct3 === "b000".U) && instr.funct7(5)

        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }

    def fromReg32(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(true.B)

        this.op32 := true.B
        this.funct3 := instr.funct3
        this.sraSub := (instr.funct3 === "b101".U || instr.funct3 === "b000".U) && instr.funct7(5)

        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }
}

class BranchOpcode extends Bundle {
    val funct = UInt(4.W)

    def fromBranch(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt) = {
        val instr = rawInstr.asTypeOf(new BTypeInstruction)
        val valid = WireInit(true.B)

        this.funct := instr.funct3
        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        valid
    }

    def fromJal(rawInstr: Instruction, _regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt) = {
        val instr = rawInstr.asTypeOf(new JTypeInstruction)
        val valid = WireInit(true.B)

        this.funct := "b1000".U
        params.rd := instr.rd
        valid
    }

    def fromJalr(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt) = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(true.B)

        this.funct := "b1001".U
        regReq.source1 := instr.rs1
        params.source2 := instr.imm12.sextu(64)
        params.rd := instr.rd
        valid
    }
}

class LoadStoreOpcode extends Bundle {
    val funct = UInt(6.W)
    val size = UInt(3.W)

    def fromAmo(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt): Bool = {
        val instr = rawInstr.asTypeOf(new RTypeInstruction)
        val valid = WireInit(true.B)

        // Always with .aq.rl order
        this.funct := instr.funct7(6,2) ## 1.U(1.W)
        this.size := instr.funct3
        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        params.rd := instr.rd
        valid
    }

    def fromLoad(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt) = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        val valid = WireInit(true.B)

        this.funct := 0.U(1.W) ## 0.U(1.W)
        this.size := instr.funct3
        // Issuer will sum up register request values from this instruction
        regReq.source1 := instr.rs1
        params.source1 := instr.imm12.sextu(64)
        params.rd := instr.rd
        valid
    }

    def fromStore(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt) = {
        val instr = rawInstr.asTypeOf(new STypeInstruction)
        val valid = WireInit(true.B)

        this.funct := 1.U(1.W) ## 0.U(1.W)
        this.size := instr.funct3
        regReq.source1 := instr.rs1
        regReq.source2 := instr.rs2
        // Issuer will sum up register request values from this instruction
        params.source1 := instr.imm12.sextu(64)
        valid
    }
}

class MiscOpcode extends Bundle {
    val miscCsrFunct = UInt(4.W)
    val miscSysFunct = UInt(3.W)
    val miscMemFunct = UInt(1.W)

    def fromSys(rawInstr: Instruction, regReq: RegisterRequests, params: DecoderOutParams, _pc: UInt): Bool = {
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

    def fromMiscMem(rawInstr: Instruction, _regReq: RegisterRequests, params: DecoderOutParams, pc: UInt): Bool = {
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