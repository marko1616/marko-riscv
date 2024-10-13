package markorv

import chisel3._
import chisel3.util._

import markorv.IssueTask

class InstrIssueUnit extends Module {
    val io = IO(new Bundle {
        val issue_task = Flipped(Decoupled(new IssueTask))

        val lsu_out = Decoupled(new Bundle {
            val lsu_opcode = UInt(5.W)
            val params = new DecoderOutParams(64)
        })

        val alu_out = Decoupled(new Bundle {
            val alu_opcode = UInt(5.W)
            val params = new DecoderOutParams(64)
        })

        val branch_out = Decoupled(new Bundle {
            val branch_opcode = UInt(5.W)
            val pred_taken = Bool()
            val pred_pc = UInt(64.W)
            val recovery_pc = UInt(64.W)
            val params = new DecoderOutParams(64)
        })
    })

    io.issue_task.ready := io.lsu_out.ready && io.alu_out.ready && io.branch_out.ready

    io.lsu_out.valid := false.B
    io.alu_out.valid := false.B
    io.branch_out.valid := false.B

    io.lsu_out.bits.lsu_opcode := 0.U
    io.lsu_out.bits.params := 0.U.asTypeOf(new DecoderOutParams(64))
    io.alu_out.bits.alu_opcode := 0.U
    io.alu_out.bits.params := 0.U.asTypeOf(new DecoderOutParams(64))
    io.branch_out.bits.branch_opcode := 0.U
    io.branch_out.bits.pred_taken := false.B
    io.branch_out.bits.pred_pc := 0.U
    io.branch_out.bits.recovery_pc := 0.U
    io.branch_out.bits.params := 0.U.asTypeOf(new DecoderOutParams(64))

    when (io.issue_task.valid) {
        when(io.issue_task.bits.operate_unit === 0.U) {
            io.alu_out.valid := true.B
            io.alu_out.bits.alu_opcode := io.issue_task.bits.alu_opcode
            io.alu_out.bits.params := io.issue_task.bits.params
        }.elsewhen(io.issue_task.bits.operate_unit === 1.U) {
            io.lsu_out.valid := true.B
            io.lsu_out.bits.lsu_opcode := io.issue_task.bits.lsu_opcode
            io.lsu_out.bits.params := io.issue_task.bits.params
        }.elsewhen(io.issue_task.bits.operate_unit === 2.U) {
            io.branch_out.valid := true.B
            io.branch_out.bits.branch_opcode := io.issue_task.bits.branch_opcode
            io.branch_out.bits.pred_taken := io.issue_task.bits.pred_taken
            io.branch_out.bits.pred_pc := io.issue_task.bits.pred_pc
            io.branch_out.bits.recovery_pc := io.issue_task.bits.recovery_pc
            io.branch_out.bits.params := io.issue_task.bits.params
        }
    }
}