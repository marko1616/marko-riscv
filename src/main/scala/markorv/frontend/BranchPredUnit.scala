package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.frontend._

class BranchPredUnit extends Module {
    val io = IO(new Bundle {
        val bpu_instr = Input(new Bundle {
            val instr = UInt(32.W)
            val pc = UInt(64.W)
        })

        val bpu_result = Output(new Bundle {
            val is_branch = Bool()
            val pred_taken = Bool()
            val pred_pc = UInt(64.W)
            val recovery_pc = UInt(64.W)
        })

        val reg_read = Output(UInt(5.W))
        val reg_data = Input(UInt(64.W))
    })

    io.reg_read := 0.U

    io.bpu_result.is_branch := false.B
    io.bpu_result.pred_taken := false.B
    io.bpu_result.pred_pc := io.bpu_instr.pc + 4.U
    io.bpu_result.recovery_pc := io.bpu_instr.pc + 4.U

    switch(io.bpu_instr.instr(6, 0)) {
        is("b1101111".U) {
            // jal
            io.bpu_result.is_branch := true.B
            io.bpu_result.pred_taken := true.B
            io.bpu_result.pred_pc := io.bpu_instr.pc + (Cat(
              io.bpu_instr.instr(31),
              io.bpu_instr.instr(19, 12),
              io.bpu_instr.instr(20),
              io.bpu_instr.instr(30, 21),
              0.U(1.W)
            ).asSInt.pad(64)).asUInt
            io.bpu_result.recovery_pc := io.bpu_instr.pc + 4.U
        }
        is("b1100111".U) {
            // jalr
            io.reg_read := io.bpu_instr.instr(19, 15)

            io.bpu_result.is_branch := true.B
            io.bpu_result.pred_taken := true.B
            io.bpu_result.pred_pc := (io.reg_data + io.bpu_instr
                .instr(19, 15)
                .asSInt
                .pad(64)
                .asUInt) & ~(1.U(64.W))
            io.bpu_result.recovery_pc := io.bpu_instr.pc + 4.U
        }
        is("b1100011".U) {
            // branch
            io.bpu_result.is_branch := true.B
            io.bpu_result.pred_pc := 0.U

            // static prediction for backward branches
            val imm = Wire(SInt(64.W))
            imm := Cat(
              io.bpu_instr.instr(31),
              io.bpu_instr.instr(7),
              io.bpu_instr.instr(30, 25),
              io.bpu_instr.instr(11, 8),
              0.U(1.W)
            ).asSInt.pad(64)
            when(imm < 0.S) {
                io.bpu_result.pred_taken := true.B
                io.bpu_result.pred_pc := io.bpu_instr.pc + imm.asUInt
                io.bpu_result.recovery_pc := io.bpu_instr.pc + 4.U
            }.otherwise {
                io.bpu_result.pred_taken := false.B
                io.bpu_result.pred_pc := io.bpu_instr.pc + 4.U
                io.bpu_result.recovery_pc := io.bpu_instr.pc + imm.asUInt
            }
        }
    }
}
