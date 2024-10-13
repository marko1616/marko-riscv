package markorv

import chisel3._
import chisel3.util._

class InstrIssueUnit extends Module {
    val io = IO(new Bundle {
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

        val reg_read1 = Output(UInt(5.W))
        val reg_read2 = Output(UInt(5.W))
        val reg_data1 = Input(UInt(64.W))
        val reg_data2 = Input(UInt(64.W))

        val acquire_reg = Output(UInt(5.W))
        val acquired = Input(Bool())
        val occupied_regs = Input(UInt(32.W))

        val outfire = Output(Bool())
        val debug_peek = Output(UInt(64.W))
    })
}