package markorv.backend

import chisel3._
import chisel3.util._

import markorv.frontend.DecoderOutParams
import markorv.backend._

class ArithmeticLogicUnit extends Module {
    val io = IO(new Bundle {
        val alu_instr = Flipped(Decoupled(new Bundle {
            val alu_opcode = UInt(5.W)
            val params = new DecoderOutParams(64)
        }))

        val write_back = Decoupled(new Bundle {
            val reg = Input(UInt(5.W))
            val data = Input(UInt(64.W))
        })

        val outfire = Output(Bool())
    })

    val write_back_orig = Wire(UInt(64.W))

    io.outfire := false.B
    io.alu_instr.ready := io.write_back.ready
    io.write_back.valid := false.B
    io.write_back.bits.reg := 0.U
    io.write_back.bits.data := 0.U

    write_back_orig := 0.U

    // ALU operation codes[3,0]
    val ALU_ADD = "b0001".U
    val ALU_SLL = "b0011".U
    val ALU_SLT = "b0101".U
    val ALU_SLTU = "b0111".U
    val ALU_XOR = "b1001".U
    val ALU_SRL = "b1011".U
    val ALU_OR = "b1101".U
    val ALU_AND = "b1111".U

    val ALU_SRA = "b1010".U
    val ALU_SUB = "b0000".U

    // Define a helper function to set write_back values
    def perform_op(result: UInt): Unit = {
        io.write_back.valid := true.B
        io.write_back.bits.reg := io.alu_instr.bits.params.rd
        write_back_orig := result
    }

    when(io.alu_instr.valid) {
        switch(io.alu_instr.bits.alu_opcode(3, 0)) {
            is(ALU_ADD) {
                perform_op(
                  io.alu_instr.bits.params.source1 + io.alu_instr.bits.params.source2
                )
            }
            is(ALU_SUB) {
                perform_op(
                  io.alu_instr.bits.params.source1 - io.alu_instr.bits.params.source2
                )
            }
            is(ALU_SLT) {
                perform_op(
                  (io.alu_instr.bits.params.source1.asSInt < io.alu_instr.bits.params.source2.asSInt).asUInt
                )
            }
            is(ALU_SLTU) {
                perform_op(
                  (io.alu_instr.bits.params.source1 < io.alu_instr.bits.params.source2).asUInt
                )
            }
            is(ALU_XOR) {
                perform_op(
                  io.alu_instr.bits.params.source1 ^ io.alu_instr.bits.params.source2
                )
            }
            is(ALU_OR) {
                perform_op(
                  io.alu_instr.bits.params.source1 | io.alu_instr.bits.params.source2
                )
            }
            is(ALU_AND) {
                perform_op(
                  io.alu_instr.bits.params.source1 & io.alu_instr.bits.params.source2
                )
            }
            is(ALU_SLL) {
                when(io.alu_instr.bits.alu_opcode(4)) {
                    perform_op(
                      io.alu_instr.bits.params.source1 << io.alu_instr.bits.params
                          .source2(4, 0)
                    )
                }.otherwise {
                    perform_op(
                      io.alu_instr.bits.params.source1 << io.alu_instr.bits.params
                          .source2(5, 0)
                    )
                }
            }
            is(ALU_SRL) {
                when(io.alu_instr.bits.alu_opcode(4)) {
                    perform_op(
                      io.alu_instr.bits.params.source1 >> io.alu_instr.bits.params
                          .source2(4, 0)
                    )
                }.otherwise {
                    perform_op(
                      io.alu_instr.bits.params.source1 >> io.alu_instr.bits.params
                          .source2(5, 0)
                    )
                }
            }
            is(ALU_SRA) {
                when(io.alu_instr.bits.alu_opcode(4)) {
                    perform_op(
                      (io.alu_instr.bits.params.source1.asSInt >> io.alu_instr.bits.params
                          .source2(4, 0)).asUInt
                    )
                }.otherwise {
                    perform_op(
                      io.alu_instr.bits.params.source1 >> io.alu_instr.bits.params
                          .source2(5, 0)
                    )
                }
            }
        }
        io.outfire := true.B
    }

    // Cut and padding to 64Bit for `word` wide command.
    when(io.alu_instr.bits.alu_opcode(4)) {
        io.write_back.bits.data := (write_back_orig(31, 0).asSInt
            .pad(64))
            .asUInt
    }.otherwise {
        io.write_back.bits.data := write_back_orig
    }
}
