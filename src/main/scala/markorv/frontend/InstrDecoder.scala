package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.frontend._

class DecoderOutParams(data_width: Int = 64) extends Bundle {
    val immediate = UInt(data_width.W)
    val source1 = UInt(data_width.W)
    val source2 = UInt(data_width.W)
    val rd = UInt(5.W)
    val pc = UInt(64.W)
}

class RegisterSourceRequests(data_width: Int = 64) extends Bundle {
    val source1 = UInt(5.W)
    val source1_read_word = Bool()
    val source2 = UInt(5.W)
    val source2_read_word = Bool()
}

class IssueTask extends Bundle {
    val operate_unit = UInt(2.W)
    val alu_opcode = UInt(5.W)
    val lsu_opcode = UInt(6.W)
    val misc_opcode = UInt(5.W)
    val branch_opcode = UInt(5.W)
    val pred_taken = Bool()
    val pred_pc = UInt(64.W)
    val recovery_pc = UInt(64.W)
    val params = new DecoderOutParams(64)
    val reg_source_requests = new RegisterSourceRequests(64)
}

class InstrDecoder(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        val instr_bundle = Flipped(Decoupled(new InstrIPBundle))
        val issue_task = Decoupled(new IssueTask)

        val invalid_drop = Output(Bool())
        val outfire = Output(Bool())
    })

    val issue_task = Wire(new IssueTask)

    val instr = Wire(UInt(32.W))
    val pc = Wire(UInt(64.W))
    val valid_instr = Wire(Bool())
    // 0 for alu 1 for lsu 2 for branch
    val operate_unit = Wire(UInt(2.W))
    val params = Wire(new DecoderOutParams(data_width))

    val reg_source_requests = WireDefault(
      0.U.asTypeOf(new RegisterSourceRequests(data_width))
    )

    val OP_LUI      = "b0110111".U
    val OP_AUIPC    = "b0010111".U
    val OP_IMM      = "b0010011".U
    val OP_IMM32    = "b0011011".U
    val OP          = "b0110011".U
    val OP_32       = "b0111011".U
    val OP_LOAD     = "b0000011".U
    val OP_STOR     = "b0100011".U
    val OP_JAL      = "b1101111".U
    val OP_JALR     = "b1100111".U
    val OP_BRANCH   = "b1100011".U
    val OP_SYSTEM   = "b1110011".U
    val OP_MISC_MEM = "b0001111".U

    val opcode  = instr(6, 0)
    val rd      = instr(11, 7)
    val rs1     = instr(19, 15)
    val rs2     = instr(24, 20)
    val funct3  = instr(14, 12)
    val funct7  = instr(31, 25)

    instr := io.instr_bundle.bits.instr
    pc := io.instr_bundle.bits.pc
    valid_instr := false.B
    operate_unit := 0.U

    io.instr_bundle.ready := false.B
    io.outfire := false.B
    io.invalid_drop := false.B

    io.issue_task.valid := false.B
    io.issue_task.bits := 0.U.asTypeOf(new IssueTask)
    issue_task := 0.U.asTypeOf(new IssueTask)

    params.immediate := 0.U(data_width.W)
    params.source1 := 0.U(data_width.W)
    params.source2 := 0.U(data_width.W)
    params.rd := 0.U
    params.pc := 0.U

    when(io.instr_bundle.valid) {
        switch(opcode) {
            is(OP_LUI) {
                // lui
                issue_task.alu_opcode := 1.U
                params.source1 := (instr(31, 12) << 12).asSInt
                    .pad(64)
                    .asUInt
                params.source2 := 0.U
                params.rd := rd

                valid_instr := true.B
                operate_unit := 0.U
            }
            is(OP_AUIPC) {
                // auipc
                issue_task.alu_opcode := 1.U
                params.source1 := (instr(31, 12) << 12).asSInt
                    .pad(64)
                    .asUInt
                params.source2 := pc
                params.rd := rd

                valid_instr := true.B
                operate_unit := 0.U
            }
            is(OP_IMM) {
                // addi slti sltiu xori ori andi slli srli srai
                when(funct3 === "b001".U) {
                    // slli
                    issue_task.alu_opcode := "b00011".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(funct3 === "b101".U && instr(30)) {
                    // srai
                    issue_task.alu_opcode := "b01011".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(funct3 === "b101".U) {
                    // srli
                    issue_task.alu_opcode := "b01010".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.otherwise {
                    issue_task.alu_opcode := Cat(
                      0.U(1.W),
                      funct3,
                      1.U(1.W)
                    )
                    params.source2 := (instr(31, 20).asSInt
                        .pad(64))
                        .asUInt
                }
                reg_source_requests.source1 := rs1
                params.rd := rd

                valid_instr := true.B
                operate_unit := 0.U
            }
            is(OP_IMM32) {
                // addiw slliw srliw sraiw
                when(funct3 === "b001".U) {
                    // slliw
                    issue_task.alu_opcode := "b10011".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(funct3 === "b101".U && instr(30)) {
                    // sraiw
                    issue_task.alu_opcode := "b11011".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(funct3 === "b101".U) {
                    // srliw
                    issue_task.alu_opcode := "b11010".U
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.otherwise {
                    issue_task.alu_opcode := Cat(
                      1.U(1.W),
                      funct3,
                      1.U(1.W)
                    )
                    params.source2 := (instr(31, 20).asSInt
                        .pad(64))
                        .asUInt
                }
                reg_source_requests.source1 := rs1
                params.rd := rd

                valid_instr := true.B
                operate_unit := 0.U
            }
            is(OP) {
                // add sub slt sltu xor or and sll srl sra
                when(funct3 === "b001".U) {
                    // sll
                    issue_task.alu_opcode := "b00011".U
                }.elsewhen(funct3 === "b101".U && instr(30)) {
                    // sra
                    issue_task.alu_opcode := "b01011".U
                }.elsewhen(funct3 === "b101".U) {
                    // srl
                    issue_task.alu_opcode := "b01010".U
                }.elsewhen(funct3 === "b000".U && instr(30)) {
                    // sub
                    issue_task.alu_opcode := "b00000".U
                }.otherwise {
                    issue_task.alu_opcode := Cat(
                      1.U(1.W),
                      funct3,
                      1.U(1.W)
                    )
                }
                reg_source_requests.source1 := rs1
                reg_source_requests.source2 := rs2
                params.rd := rd

                valid_instr := true.B
                operate_unit := 0.U
            }
            is(OP_32) {
                // addw subw sllw srlw sraw
                when(funct3 === "b001".U) {
                    // sllw
                    issue_task.alu_opcode := "b10011".U
                }.elsewhen(funct3 === "b101".U && instr(30)) {
                    // sraw
                    issue_task.alu_opcode := "b11011".U
                }.elsewhen(funct3 === "b101".U) {
                    // srlw
                    issue_task.alu_opcode := "b11010".U
                }.elsewhen(funct3 === "b000".U && instr(30)) {
                    // subw
                    issue_task.alu_opcode := "b10000".U
                }.otherwise {
                    issue_task.alu_opcode := Cat(
                      1.U(1.W),
                      funct3,
                      1.U(1.W)
                    )
                }
                reg_source_requests.source1 := rs1
                reg_source_requests.source1_read_word := true.B
                reg_source_requests.source2 := rs2
                params.rd := rd

                valid_instr := true.B
                operate_unit := 0.U
            }
            is(OP_LOAD) {
                // Load Memory
                issue_task.lsu_opcode := Cat("b000".U, funct3)
                params.immediate := instr(31, 20).asSInt
                    .pad(64)
                    .asUInt
                reg_source_requests.source1 := rs1
                params.source2 := 0.U(data_width.W)
                params.rd := rd

                valid_instr := true.B
                operate_unit := 1.U
            }
            is(OP_STOR) {
                // Store Memory
                issue_task.lsu_opcode := Cat("b010".U, funct3)
                params.immediate := Cat(
                  instr(31, 25),
                  instr(11, 7)
                ).asSInt.pad(64).asUInt
                reg_source_requests.source1 := rs1
                reg_source_requests.source2 := rs2

                valid_instr := true.B
                operate_unit := 1.U
            }
            is(OP_JAL) {
                // jal
                issue_task.branch_opcode := "b00001".U
                issue_task.pred_taken := io.instr_bundle.bits.pred_taken
                issue_task.recovery_pc := io.instr_bundle.bits.recovery_pc
                params.pc := pc
                params.rd := rd

                valid_instr := true.B
                operate_unit := 3.U
            }
            is(OP_JALR) {
                // jalr
                issue_task.branch_opcode := "b00011".U
                issue_task.pred_taken := io.instr_bundle.bits.pred_taken
                issue_task.pred_pc := io.instr_bundle.bits.pred_pc
                issue_task.recovery_pc := io.instr_bundle.bits.recovery_pc
                params.pc := pc
                params.rd := rd

                reg_source_requests.source1 := rs1
                params.immediate := instr(31, 20).asSInt
                    .pad(64)
                    .asUInt

                valid_instr := true.B
                operate_unit := 3.U
            }
            is(OP_BRANCH) {
                // branch
                issue_task.branch_opcode := Cat(0.U, funct3, 0.U)
                issue_task.pred_taken := io.instr_bundle.bits.pred_taken
                issue_task.pred_pc := io.instr_bundle.bits.pred_pc
                issue_task.recovery_pc := io.instr_bundle.bits.recovery_pc

                reg_source_requests.source1 := rs1
                reg_source_requests.source2 := rs2

                valid_instr := true.B
                operate_unit := 3.U
            }
            is(OP_SYSTEM) {
                // TODO ecall ebreak wfi sfence.vma
                when(funct3 =/= 0.U) {
                    val is_imm = funct3(2)

                    // CSR addr.
                    params.source2 := instr(31, 20)
                    when(rs1 === 0.U && ~is_imm) {
                        // Allow having write side effect.
                        issue_task.misc_opcode := "b01000".U
                    }.otherwise {
                        issue_task.misc_opcode := "b00000".U
                    }
                    when(is_imm) {
                        params.source1 := rs1.pad(64)
                    }.otherwise {
                        reg_source_requests.source1 := rs1
                    }
                    params.immediate := funct3 & "b011".U
                    params.rd := rd

                    valid_instr := true.B
                    operate_unit := 2.U
                }.elsewhen(instr === "h30200073".U) {
                    // mret
                    issue_task.misc_opcode := "b00011".U
                    valid_instr := true.B
                    operate_unit := 2.U
                }
            }
            is(OP_MISC_MEM) {
                when((instr & "hf00fffff".U) === "h0000000f".U) {
                    // fence intenionally ignored due to strict TSO model.
                    issue_task.misc_opcode := "b0100".U
                    valid_instr := true.B
                    operate_unit := 2.U
                }
            }
        }
    }

    when(
      valid_instr && io.instr_bundle.valid && io.issue_task.ready
    ) {
        issue_task.params := params
        issue_task.operate_unit := operate_unit
        issue_task.reg_source_requests := reg_source_requests
        io.issue_task.valid := true.B
        io.issue_task.bits := issue_task

        io.instr_bundle.ready := true.B
        io.outfire := true.B
    }

    when(!valid_instr) {
        io.instr_bundle.ready := io.issue_task.ready
        when(io.instr_bundle.valid) {
           io.invalid_drop := true.B
           io.outfire := true.B
        }
    }
}
