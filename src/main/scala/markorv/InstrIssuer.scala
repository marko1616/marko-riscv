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

        val acquire_reg = Output(UInt(5.W))
        val acquired = Input(Bool())

        val reg_read1 = Output(UInt(5.W))
        val reg_read2 = Output(UInt(5.W))
        val reg_data1 = Input(UInt(64.W))
        val reg_data2 = Input(UInt(64.W))

        val occupied_regs = Input(UInt(32.W))
        val outfire = Output(Bool())
    })

    val params = Wire(new DecoderOutParams(64))
    params := io.issue_task.bits.params

    val occupied_reg = Wire(Bool())
    val exec_unit_ready = Wire(Bool())
    occupied_reg := false.B
    exec_unit_ready := io.lsu_out.ready && io.alu_out.ready && io.branch_out.ready

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

    io.acquire_reg := 0.U
    io.issue_task.ready := false.B
    io.outfire := false.B

    val reg_data1 = Wire(UInt(64.W))
    val reg_data2 = Wire(UInt(64.W))
    io.reg_read1 := io.issue_task.bits.reg_source_requests.source1
    io.reg_read2 := io.issue_task.bits.reg_source_requests.source2
    reg_data1 := io.reg_data1
    when(io.issue_task.bits.reg_source_requests.source1_read_word) {
        reg_data1 := io.reg_data1(31, 0)
    }
    reg_data2 := io.reg_data2
    when(io.issue_task.bits.reg_source_requests.source2_read_word) {
        reg_data2 := io.reg_data2(31, 0)
    }
    when(io.issue_task.bits.reg_source_requests.source1 =/= 0.U) {
        params.source1 := reg_data1
    }
    when(io.issue_task.bits.reg_source_requests.source2 =/= 0.U) {
        params.source2 := reg_data2
    }
    occupied_reg := io.occupied_regs(io.issue_task.bits.reg_source_requests.source1) || io.occupied_regs(io.issue_task.bits.reg_source_requests.source2)

    when (io.issue_task.valid && exec_unit_ready && ~occupied_reg) {
        // Only try to acquire register when all other are prepared.
        io.acquire_reg := params.rd
        when(io.issue_task.bits.operate_unit === 0.U && io.acquired) {
            io.outfire := true.B
            io.alu_out.valid := true.B
            io.alu_out.bits.alu_opcode := io.issue_task.bits.alu_opcode
            io.alu_out.bits.params := params
        }.elsewhen(io.issue_task.bits.operate_unit === 1.U && io.acquired) {
            io.outfire := true.B
            io.lsu_out.valid := true.B
            io.lsu_out.bits.lsu_opcode := io.issue_task.bits.lsu_opcode
            io.lsu_out.bits.params := params
        }.elsewhen(io.issue_task.bits.operate_unit === 2.U && io.acquired) {
            io.outfire := true.B
            io.branch_out.valid := true.B
            io.branch_out.bits.branch_opcode := io.issue_task.bits.branch_opcode
            io.branch_out.bits.pred_taken := io.issue_task.bits.pred_taken
            io.branch_out.bits.pred_pc := io.issue_task.bits.pred_pc
            io.branch_out.bits.recovery_pc := io.issue_task.bits.recovery_pc
            io.branch_out.bits.params := params
        }
    }

    // Ready when dispatch is available.
    io.issue_task.ready := exec_unit_ready && ~occupied_reg && && io.acquired
}