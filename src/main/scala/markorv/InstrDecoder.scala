package markorv

import chisel3._
import chisel3.util._

import markorv.InstrIPBundle

class DecoderOutParams(data_width: Int = 64) extends Bundle {
    val immediate = UInt(data_width.W)
    val source1 = UInt(data_width.W)
    val source2 = UInt(data_width.W)
    val rd = UInt(5.W)
    val pc = UInt(64.W)
}

class InstrDecoder(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        val instr_bundle = Flipped(Decoupled(new InstrIPBundle))

        val lsu_out = Decoupled(new Bundle {
            // {Load:0/Store:1}[4]{Mem:0/Imm:1}[3]{UInt:0/UInt:1}[2]{Size}[1,0]
            val lsu_opcode = UInt(5.W)
            val params = new DecoderOutParams(data_width)
        })

        val alu_out = Decoupled(new Bundle {
            val alu_opcode = UInt(5.W)
            val params = new DecoderOutParams(data_width)
        })

        val branch_out = Decoupled(new Bundle {
            val branch_opcode = UInt(5.W)
            val pred_taken = Bool()
            val pred_pc = UInt(64.W)
            val recovery_pc = UInt(64.W)
            val params = new DecoderOutParams(data_width)
        })

        val reg_read1 = Output(UInt(5.W))
        val reg_read2 = Output(UInt(5.W))
        val reg_data1 = Input(UInt(data_width.W))
        val reg_data2 = Input(UInt(data_width.W))

        val acquire_reg = Output(UInt(5.W))
        val acquired = Input(Bool())
        val occupied_regs = Input(UInt(32.W))

        val outfire = Output(Bool())
    })

    val instr = Wire(UInt(32.W))
    val pc = Wire(UInt(64.W))
    val opcode = Wire(UInt(7.W))
    val acquire_reg = Wire(UInt(5.W))
    val next_stage_ready = Wire(Bool())
    val valid_instr = Wire(Bool())
    val occupied_reg = Wire(Bool())
    // 0 for alu 1 for lsu 2 for branch
    val instr_for = Wire(UInt(2.W))
    val params = Wire(new DecoderOutParams(data_width))

    instr := io.instr_bundle.bits.instr
    pc := io.instr_bundle.bits.pc
    opcode := instr(6, 0)
    acquire_reg := 0.U
    next_stage_ready := io.lsu_out.ready && io.alu_out.ready && io.branch_out.ready
    valid_instr := false.B
    occupied_reg := false.B
    instr_for := 0.U

    io.instr_bundle.ready := false.B
    io.outfire := false.B
    io.reg_read1 := instr(19, 15)
    io.reg_read2 := instr(24, 20)
    io.acquire_reg := 0.U(5.W)

    // init outputs
    io.alu_out.valid := false.B
    io.alu_out.bits.alu_opcode := 0.U

    io.lsu_out.valid := false.B
    io.lsu_out.bits.lsu_opcode := 0.U

    io.branch_out.valid := false.B
    io.branch_out.bits.branch_opcode := 0.U
    io.branch_out.bits.pred_taken := false.B
    io.branch_out.bits.pred_pc := 0.U
    io.branch_out.bits.recovery_pc := 0.U

    params.immediate := 0.U(data_width.W)
    params.source1 := 0.U(data_width.W)
    params.source2 := 0.U(data_width.W)
    params.rd := 0.U
    params.pc := 0.U

    when(io.instr_bundle.valid) {
        switch(opcode) {
            is("b0110111".U) {
                // lui
                io.alu_out.bits.alu_opcode := 1.U
                params.source1 := (instr(31, 12) << 12).asSInt
                    .pad(64)
                    .asUInt
                params.source2 := 0.U
                params.rd := instr(11, 7)

                acquire_reg := instr(11, 7)

                valid_instr := true.B
                instr_for := 0.U
            }
            is("b0010111".U) {
                // auipc
                io.alu_out.bits.alu_opcode := 1.U
                params.source1 := (instr(31, 12) << 12).asSInt
                    .pad(64)
                    .asUInt
                params.source2 := pc
                params.rd := instr(11, 7)

                acquire_reg := instr(11, 7)

                valid_instr := true.B
                instr_for := 0.U
            }
            is("b0010011".U) {
                // addi slti sltiu xori ori andi slli srli srai
                occupied_reg := io.occupied_regs(instr(19, 15))

                when(instr(14, 12) === "b001".U) {
                    // slli
                    io.alu_out.bits.alu_opcode := "b00011".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(instr(14, 12) === "b101".U && instr(30)) {
                    // srai
                    io.alu_out.bits.alu_opcode := "b01011".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(instr(14, 12) === "b101".U) {
                    // srli
                    io.alu_out.bits.alu_opcode := "b01010".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.otherwise {
                    io.alu_out.bits.alu_opcode := Cat(
                      0.U(1.W),
                      instr(14, 12),
                      1.U(1.W)
                    )
                    params.source2 := (instr(31, 20).asSInt
                        .pad(64))
                        .asUInt
                }
                params.source1 := io.reg_data1
                params.rd := instr(11, 7)

                acquire_reg := instr(11, 7)

                valid_instr := true.B
                instr_for := 0.U
            }
            is("b0011011".U) {
                // addiw slliw srliw sraiw
                occupied_reg := io.occupied_regs(instr(19, 15))

                when(instr(14, 12) === "b001".U) {
                    // slliw
                    io.alu_out.bits.alu_opcode := "b10011".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(instr(14, 12) === "b101".U && instr(30)) {
                    // sraiw
                    io.alu_out.bits.alu_opcode := "b11011".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(instr(14, 12) === "b101".U) {
                    // srliw
                    io.alu_out.bits.alu_opcode := "b11010".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.otherwise {
                    io.alu_out.bits.alu_opcode := Cat(
                      1.U(1.W),
                      instr(14, 12),
                      1.U(1.W)
                    )
                    params.source2 := (instr(31, 20).asSInt
                        .pad(64))
                        .asUInt
                }
                params.source1 := io.reg_data1
                params.rd := instr(11, 7)

                acquire_reg := instr(11, 7)

                valid_instr := true.B
                instr_for := 0.U
            }
            is("b0110011".U) {
                // add sub slt sltu xor or and sll srl sra
                occupied_reg := io.occupied_regs(instr(19, 15)) | io
                    .occupied_regs(
                      instr(24, 20)
                    )

                when(instr(14, 12) === "b001".U) {
                    // sll
                    io.alu_out.bits.alu_opcode := "b00011".U
                }.elsewhen(instr(14, 12) === "b101".U && instr(30)) {
                    // sra
                    io.alu_out.bits.alu_opcode := "b01011".U
                }.elsewhen(instr(14, 12) === "b101".U) {
                    // srl
                    io.alu_out.bits.alu_opcode := "b01010".U
                }.elsewhen(instr(14, 12) === "b000".U && instr(30)) {
                    // sub
                    io.alu_out.bits.alu_opcode := "b00000".U
                }.otherwise {
                    io.alu_out.bits.alu_opcode := Cat(
                      1.U(1.W),
                      instr(14, 12),
                      1.U(1.W)
                    )
                }
                params.source1 := io.reg_data1
                params.source2 := io.reg_data2
                params.rd := instr(11, 7)

                acquire_reg := instr(11, 7)

                valid_instr := true.B
                instr_for := 0.U
            }
            is("b0111011".U) {
                // addw subw sllw srlw sraw
                occupied_reg := io.occupied_regs(instr(19, 15)) | io
                    .occupied_regs(
                      instr(24, 20)
                    )

                when(instr(14, 12) === "b001".U) {
                    // sllw
                    io.alu_out.bits.alu_opcode := "b10011".U
                }.elsewhen(instr(14, 12) === "b101".U && instr(30)) {
                    // sraw
                    io.alu_out.bits.alu_opcode := "b11011".U
                }.elsewhen(instr(14, 12) === "b101".U) {
                    // srlw
                    io.alu_out.bits.alu_opcode := "b11010".U
                }.elsewhen(instr(14, 12) === "b000".U && instr(30)) {
                    // subw
                    io.alu_out.bits.alu_opcode := "b10000".U
                }.otherwise {
                    io.alu_out.bits.alu_opcode := Cat(
                      1.U(1.W),
                      instr(14, 12),
                      1.U(1.W)
                    )
                }
                params.source1 := io.reg_data1(31, 0)
                params.source2 := io.reg_data2
                params.rd := instr(11, 7)

                acquire_reg := instr(11, 7)

                valid_instr := true.B
                instr_for := 0.U
            }
            is("b0000011".U) {
                // Load Memory
                occupied_reg := io.occupied_regs(instr(19, 15))

                io.lsu_out.bits.lsu_opcode := Cat(0.U(2.W), instr(14, 12))
                params.immediate := instr(31, 20).asSInt
                    .pad(64)
                    .asUInt
                params.source1 := io.reg_data1
                params.source2 := 0.U(data_width.W)
                params.rd := instr(11, 7)

                acquire_reg := instr(11, 7)

                valid_instr := true.B
                instr_for := 1.U
            }
            is("b0100011".U) {
                // Store Memory
                occupied_reg := io.occupied_regs(instr(19, 15)) | io
                    .occupied_regs(
                      instr(24, 20)
                    )

                io.lsu_out.bits.lsu_opcode := Cat("b10".U, instr(14, 12))
                params.immediate := Cat(
                  instr(31, 25),
                  instr(11, 7)
                ).asSInt.pad(64).asUInt
                params.source1 := io.reg_data1
                params.source2 := io.reg_data2
                params.rd := 0.U(5.W)

                valid_instr := true.B
                instr_for := 1.U
            }
            is("b1101111".U) {
                // jal
                io.branch_out.bits.branch_opcode := "b00001".U
                io.branch_out.bits.pred_taken := io.instr_bundle.bits.pred_taken
                io.branch_out.bits.recovery_pc := io.instr_bundle.bits.recovery_pc
                params.pc := pc
                params.rd := instr(11, 7)

                acquire_reg := instr(11, 7)

                valid_instr := true.B
                instr_for := 2.U
            }
            is("b1100111".U) {
                // jalr
                occupied_reg := io.occupied_regs(instr(19, 15))

                io.branch_out.bits.branch_opcode := "b00011".U
                io.branch_out.bits.pred_taken := io.instr_bundle.bits.pred_taken
                io.branch_out.bits.pred_pc := io.instr_bundle.bits.pred_pc
                io.branch_out.bits.recovery_pc := io.instr_bundle.bits.recovery_pc
                params.pc := pc
                params.rd := instr(11, 7)

                params.source1 := io.reg_data1
                params.immediate := instr(31, 20).asSInt
                    .pad(64)
                    .asUInt

                acquire_reg := instr(11, 7)

                valid_instr := true.B
                instr_for := 2.U
            }
            is("b1100011".U) {
                // branch
                occupied_reg := io.occupied_regs(instr(19, 15)) | io
                    .occupied_regs(
                      instr(24, 20)
                    )

                io.branch_out.bits.branch_opcode := Cat(0.U, instr(14, 12), 0.U)
                io.branch_out.bits.pred_taken := io.instr_bundle.bits.pred_taken
                io.branch_out.bits.pred_pc := io.instr_bundle.bits.pred_pc
                io.branch_out.bits.recovery_pc := io.instr_bundle.bits.recovery_pc

                params.source1 := io.reg_data1
                params.source2 := io.reg_data2

                valid_instr := true.B
                instr_for := 2.U
            }
        }
    }

    io.alu_out.bits.params := params
    io.lsu_out.bits.params := params
    io.branch_out.bits.params := params

    when(next_stage_ready && !occupied_reg) {
        io.acquire_reg := acquire_reg
    }
    when(
      io.acquired && io.instr_bundle.valid && valid_instr && !occupied_reg && next_stage_ready
    ) {
        when(instr_for === 0.U) {
            io.alu_out.valid := true.B
        }.elsewhen(instr_for === 1.U) {
            io.lsu_out.valid := true.B
        }.elsewhen(instr_for === 2.U) {
            io.branch_out.valid := true.B
        }
        io.instr_bundle.ready := true.B
        io.outfire := true.B
    }
    when(!valid_instr) {
        io.instr_bundle.ready := true.B
    }
}
