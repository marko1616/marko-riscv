package markorv.backend

import chisel3._
import chisel3.util._

import markorv.frontend.DecoderOutParams
import markorv.backend._

class BranchUnit extends Module {
    val io = IO(new Bundle {
        val branch_instr = Flipped(Decoupled(new Bundle {
            val branch_opcode = UInt(5.W)
            val pred_taken = Bool()
            val pred_pc = UInt(64.W)
            val recovery_pc = UInt(64.W)
            val params = new DecoderOutParams
        }))

        val write_back = Decoupled(new Bundle {
            val reg = Input(UInt(5.W))
            val data = Input(UInt(64.W))
        })

        val flush = Output(Bool())
        val rev_pc = Output(UInt(64.W))
    })

    io.branch_instr.ready := true.B
    io.flush := false.B
    io.rev_pc := 0.U

    io.write_back.valid := false.B
    io.write_back.bits.reg := 0.U
    io.write_back.bits.data := 0.U

    when(io.branch_instr.valid) {
        switch(io.branch_instr.bits.branch_opcode) {
            is("b00001".U) {
                // jal
                io.write_back.valid := true.B
                io.write_back.bits.reg := io.branch_instr.bits.params.rd
                io.write_back.bits.data := io.branch_instr.bits.params.pc + 4.U
            }
            is("b00011".U) {
                // jalr
                val jump_addr = Wire(UInt(64.W))
                jump_addr := (io.branch_instr.bits.params.source1 + io.branch_instr.bits.params.immediate) & ~(1
                    .U(64.W))
                io.write_back.valid := true.B
                io.write_back.bits.reg := io.branch_instr.bits.params.rd
                io.write_back.bits.data := io.branch_instr.bits.params.pc + 4.U

                when(io.branch_instr.bits.pred_pc =/= jump_addr) {
                    io.flush := true.B
                    io.rev_pc := jump_addr
                }
            }
            is("b00000".U) {
                // beq
                when(
                  io.branch_instr.bits.params.source1 === io.branch_instr.bits.params.source2
                ) {
                    io.flush := ~io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }.otherwise {
                    io.flush := io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }
            }
            is("b00010".U) {
                // bne
                when(
                  io.branch_instr.bits.params.source1 =/= io.branch_instr.bits.params.source2
                ) {
                    io.flush := ~io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }.otherwise {
                    io.flush := io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }
            }
            is("b01000".U) {
                // blt
                when(
                  io.branch_instr.bits.params.source1.asSInt < io.branch_instr.bits.params.source2.asSInt
                ) {
                    io.flush := ~io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }.otherwise {
                    io.flush := io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }
            }
            is("b01010".U) {
                // bge
                when(
                  io.branch_instr.bits.params.source1.asSInt >= io.branch_instr.bits.params.source2.asSInt
                ) {
                    io.flush := ~io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }.otherwise {
                    io.flush := io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }
            }
            is("b01100".U) {
                // bltu
                when(
                  io.branch_instr.bits.params.source1 < io.branch_instr.bits.params.source2
                ) {
                    io.flush := ~io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }.otherwise {
                    io.flush := io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }
            }
            is("b01110".U) {
                // bgeu
                when(
                  io.branch_instr.bits.params.source1 >= io.branch_instr.bits.params.source2
                ) {
                    io.flush := ~io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }.otherwise {
                    io.flush := io.branch_instr.bits.pred_taken
                    io.rev_pc := io.branch_instr.bits.recovery_pc
                }
            }
        }
    }
}
