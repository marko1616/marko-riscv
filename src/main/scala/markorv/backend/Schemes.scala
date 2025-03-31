package markorv.backend

import chisel3._
import chisel3.util._

class RegisterCommit extends Bundle {
    val reg = UInt(5.W)
    val data = UInt(64.W)
}

class ALUOpcode extends Bundle {
    val op32 = Bool()
    val sra_sub = Bool()
    val funct3 = UInt(3.W)
}

class BranchOpcodeBranch extends Bundle {
    val funct3 = UInt(3.W)
}

class BranchOpcodeJal extends Bundle {
    val jalr = Bool()
}

class BranchOpcode extends Bundle {
    val funct = UInt(4.W)
    def from_branch(case_op: BranchOpcodeBranch) = {
        this.funct := case_op.funct3
    }
    def from_jal(case_op: BranchOpcodeJal) = {
        this.funct := 1.U ## 0.U(2.W) ## case_op.jalr.asUInt
    }
}

class LoadStoreOpcodeNorm extends Bundle {
    val funct1 = UInt(1.W)
    val size = UInt(3.W)
    def from_load(funct3: UInt) = {
        this.size := funct3
    }
    def from_store(funct3: UInt) = {
        this.funct1 := 1.U
        this.size := funct3
    }
}

class LoadStoreOpcodeAmo extends Bundle {
   // No need for `aq` or `rl` since all load/store operations use sequential consistency
    val funct5 = UInt(5.W)
    val size = UInt(3.W)
    def from_instr(funct3: UInt, funct7: UInt) = {
        this.funct5 := funct7(6,2)
        this.size := funct3
    }
}

class LoadStoreOpcode extends Bundle {
    val funct = UInt(6.W)
    val size = UInt(3.W)
    def from_amo(case_op: LoadStoreOpcodeAmo) = {
        this.funct := case_op.funct5 ## 1.U(1.W)
        this.size := case_op.size
    }
    def fromnorm(case_op: LoadStoreOpcodeNorm) = {
        this.funct := case_op.funct1 ## 0.U(1.W)
        this.size := case_op.size
    }
}

object SystemOpMap extends ChiselEnum {
    val ecall = Value("h000000".U)
    val ebreak = Value("h002000".U)
    val wfi = Value("h20a000".U)
    val mret = Value("h604000".U)
}

class MiscOpcode extends Bundle {
    val misc_csr_funct = UInt(4.W) 
    val misc_sys_funct = UInt(3.W)
    val misc_mem_funct = UInt(1.W)
    def from_sys(imm12: UInt, funct3: UInt, rs1: UInt, rd: UInt): Bool = {
        val valid = WireInit(false.B)
        val csr_type = funct3(1,0)
        when(csr_type =/= 0.U) {
            val is_csrrw = csr_type === 1.U
            val read_en = Mux(is_csrrw, rd =/= 0.U, true.B)
            val write_en = Mux(is_csrrw, true.B, rs1 =/= 0.U)
            this.misc_csr_funct := read_en ## write_en ## funct3(1,0)
            valid := true.B
        }.otherwise {
            val (sys_funct, sys_valid) = SystemOpMap.safe((imm12 ## rs1 ## funct3 ## rd)(22,0))
            valid := sys_valid
            this.misc_sys_funct := Mux(sys_valid, MuxLookup(sys_funct, 0.U)(Seq(
                SystemOpMap.ecall -> 1.U,
                SystemOpMap.ebreak -> 2.U,
                SystemOpMap.wfi -> 3.U,
                SystemOpMap.mret -> 4.U
            )),0.U)
        }
        return valid
    }
    def from_miscmem(imm12: UInt, funct3: UInt, rs1: UInt, rd: UInt): Bool = {
        val valid = WireInit(false.B)
        val instr = imm12 ## rs1 ## funct3 ## rd
        when((instr & "h1e01fff".U) === 0.U) {
            valid := true.B
            this.misc_mem_funct := 1.U
        }
        return valid
    }
}