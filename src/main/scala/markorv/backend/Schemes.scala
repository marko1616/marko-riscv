package markorv.backend

import chisel3._
import chisel3.util._

class ALUOpcode extends Bundle {
    val op32 = Bool()
    val sra_sub = Bool()
    val funct3 = UInt(3.W)
}

class RegisterCommit extends Bundle {
    val reg = UInt(5.W)
    val data = UInt(64.W)
}

class BranchOpcodeBranch extends Bundle {
    val funct3 = UInt(3.W)
}

class BranchOpcodeJal extends Bundle {
    val jalr = Bool()
}

class BranchOpcode extends Bundle {
    val funct = UInt(4.W)
    def frombranch(case_op: BranchOpcodeBranch) = {
        this.funct := case_op.funct3
    }
    def fromjal(case_op: BranchOpcodeJal) = {
        this.funct := 1.U ## 0.U(2.W) ## case_op.jalr.asUInt
    }
}