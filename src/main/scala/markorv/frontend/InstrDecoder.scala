package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend._
import markorv.backend._

class DecoderOutParams(data_width: Int = 64) extends Bundle {
    val immediate = UInt(data_width.W)
    val source1 = UInt(data_width.W)
    val source2 = UInt(data_width.W)
    val rd = UInt(5.W)
    val pc = UInt(64.W)
}

class RegisterSourceRequests(data_width: Int = 64) extends Bundle {
    val source1 = UInt(5.W)
    val source2 = UInt(5.W)
}

class IssueTask extends Bundle {
    val operate_unit = UInt(3.W)
    val alu_opcode = new ALUOpcode()
    val lsu_opcode = UInt(6.W)
    val misc_opcode = UInt(5.W)
    val branch_opcode = new BranchOpcode
    val mu_opcode = new MUOpcode
    val pred_taken = Bool()
    val pred_pc = UInt(64.W)
    val recover_pc = UInt(64.W)
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
    val operate_unit = Wire(UInt(3.W))
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

    // A Extension
    val OP_AMO      = "b0101111".U

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
    io.issue_task.bits := new IssueTask().zero
    issue_task := new IssueTask().zero

    params.immediate := 0.U(data_width.W)
    params.source1 := 0.U(data_width.W)
    params.source2 := 0.U(data_width.W)
    params.rd := 0.U
    params.pc := pc

    when(io.instr_bundle.valid) {
        switch(opcode) {
            is(OP_LUI) {
                // lui
                issue_task.alu_opcode.funct3 := "b000".U
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
                issue_task.alu_opcode.funct3 := "b000".U
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
                issue_task.alu_opcode.funct3 := funct3
                when(funct3 === "b001".U) {
                    // slli
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(funct3 === "b101".U && instr(30)) {
                    // srai
                    issue_task.alu_opcode.sra_sub := true.B
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(funct3 === "b101".U) {
                    // srli
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.otherwise {
                    params.source2 := (instr(31, 20).asSInt.pad(64)).asUInt
                }
                reg_source_requests.source1 := rs1
                params.rd := rd

                valid_instr := true.B
                operate_unit := 0.U
            }
            is(OP_IMM32) {
                // addiw slliw srliw sraiw
                issue_task.alu_opcode.op32 := true.B
                issue_task.alu_opcode.funct3 := funct3
                when(funct3 === "b001".U) {
                    // slliw
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(funct3 === "b101".U && instr(30)) {
                    // sraiw
                    issue_task.alu_opcode.sra_sub := true.B
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.elsewhen(funct3 === "b101".U) {
                    // srliw
                    params.source2 := (instr(25, 20).asSInt
                        .pad(64))
                        .asUInt
                }.otherwise {
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
                when(funct7 === "b0000001".U) {
                    // M Extension
                    reg_source_requests.source1 := rs1
                    reg_source_requests.source2 := rs2
                    issue_task.mu_opcode.funct3 := funct3
                    params.rd := rd
                    valid_instr := true.B
                    operate_unit := 3.U
                }.otherwise {
                    // I Extension
                    // add sub slt sltu xor or and sll srl sra
                    issue_task.alu_opcode.funct3 := funct3
                    when(funct3 === "b101".U && instr(30)) {
                        // sra
                        issue_task.alu_opcode.sra_sub := true.B
                    }.elsewhen(funct3 === "b000".U && instr(30)) {
                        // sub
                        issue_task.alu_opcode.sra_sub := true.B
                    }
                    reg_source_requests.source1 := rs1
                    reg_source_requests.source2 := rs2
                    params.rd := rd

                    valid_instr := true.B
                    operate_unit := 0.U
                }
            }
            is(OP_32) {
                when(funct7 === "b0000001".U) {
                    // M Extension
                    reg_source_requests.source1 := rs1
                    reg_source_requests.source2 := rs2
                    issue_task.mu_opcode.op32 := true.B
                    issue_task.mu_opcode.funct3 := funct3
                    params.rd := rd
                    valid_instr := true.B
                    operate_unit := 3.U
                }.otherwise {
                    // addw subw sllw srlw sraw
                    issue_task.alu_opcode.op32 := true.B
                    issue_task.alu_opcode.funct3 := funct3
                    when(funct3 === "b101".U && instr(30)) {
                        // sra
                        issue_task.alu_opcode.sra_sub := true.B
                    }.elsewhen(funct3 === "b000".U && instr(30)) {
                        // sub
                        issue_task.alu_opcode.sra_sub := true.B
                    }
                    reg_source_requests.source1 := rs1
                    reg_source_requests.source2 := rs2
                    params.rd := rd

                    valid_instr := true.B
                    operate_unit := 0.U
                }
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
                val branch_op = WireInit(new BranchOpcodeJal().zero)
                issue_task.branch_opcode.fromjal(branch_op)
                issue_task.pred_taken := io.instr_bundle.bits.pred_taken
                issue_task.recover_pc := io.instr_bundle.bits.recover_pc
                params.rd := rd

                valid_instr := true.B
                operate_unit := 4.U
            }
            is(OP_JALR) {
                // jalr
                val branch_op = WireInit(new BranchOpcodeJal().zero)
                branch_op.jalr := true.B
                issue_task.branch_opcode.fromjal(branch_op)
                issue_task.pred_taken := io.instr_bundle.bits.pred_taken
                issue_task.pred_pc := io.instr_bundle.bits.pred_pc
                issue_task.recover_pc := io.instr_bundle.bits.recover_pc
                params.rd := rd

                reg_source_requests.source1 := rs1
                params.source2 := instr(31, 20).asSInt.pad(64).asUInt

                valid_instr := true.B
                operate_unit := 4.U
            }
            is(OP_BRANCH) {
                // branch
                val branch_op = WireInit(new BranchOpcodeBranch().zero)
                branch_op.funct3 := funct3
                issue_task.branch_opcode.frombranch(branch_op)
                issue_task.pred_taken := io.instr_bundle.bits.pred_taken
                issue_task.pred_pc := io.instr_bundle.bits.pred_pc
                issue_task.recover_pc := io.instr_bundle.bits.recover_pc

                reg_source_requests.source1 := rs1
                reg_source_requests.source2 := rs2

                valid_instr := true.B
                operate_unit := 4.U
            }
            is(OP_SYSTEM) {
                // TODO sfence.vma
                when(funct3 =/= 0.U) {
                    val is_imm = funct3(2)

                    // CSR addr.
                    params.source2 := instr(31, 20)

                    /*
                        Register operand
                        Instruction   rd=x0  rs1=x0  Reads CSR  Writes CSR
                        CSRRW         Yes    -       No         Yes
                        CSRRW         No     -       Yes        Yes
                        CSRRS/CSRRC   -      Yes     Yes        No
                        CSRRS/CSRRC   -      No      Yes        Yes

                        Immediate operand
                        Instruction   rd=x0  uimm=0  Reads CSR  Writes CSR
                        CSRRWI        Yes    -       No         Yes
                        CSRRWI        No     -       Yes        Yes
                        CSRRSI/CSRRCI -      Yes     Yes        No
                        CSRRSI/CSRRCI -      No      Yes        Yes
                    */
                    when(funct3(1,0) === "b01".U) {
                        // CSRRW, CSRRWI
                        when(rd === 0.U) {
                            issue_task.misc_opcode := "b10000".U
                        }.otherwise {
                            issue_task.misc_opcode := "b11000".U
                        }
                    }.otherwise {
                        // CSRRS, CSRRC, CSRRSI, CSRRCI
                        when(rs1 === 0.U) {
                            issue_task.misc_opcode := "b01000".U
                        }.otherwise {
                            issue_task.misc_opcode := "b11000".U
                        }
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
                }.elsewhen(instr === "h10500073".U) {
                    // wfi
                    issue_task.misc_opcode := "b00011".U
                    valid_instr := true.B
                    operate_unit := 2.U
                }.elsewhen(instr === "h00000073".U) {
                    // ecall
                    issue_task.misc_opcode := "b01011".U
                    valid_instr := true.B
                    operate_unit := 2.U
                }.elsewhen(instr === "h00100073".U) {
                    // ebreak
                    issue_task.misc_opcode := "b10011".U
                    valid_instr := true.B
                    operate_unit := 2.U
                }.elsewhen(instr === "h30200073".U) {
                    // mret
                    issue_task.misc_opcode := "b11011".U
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
            is(OP_AMO) {
                // aq rl intenionally ignored due to strict TSO model.
                reg_source_requests.source1 := rs1
                reg_source_requests.source2 := rs2
                params.rd := rd

                when(instr(31)) {
                    // amo compare
                    issue_task.lsu_opcode := Cat("b100".U,instr(30,29),funct3(0))
                }.otherwise {
                    when(instr(28,27) === 0.U) {
                        // amo clac
                        issue_task.lsu_opcode := Cat("b101".U,instr(30,29),funct3(0))
                    }.elsewhen(instr(28,27) === 1.U) {
                        // amo swap
                        issue_task.lsu_opcode := Cat("b11000".U,funct3(0))
                    }.otherwise {
                        // lr sc
                        issue_task.lsu_opcode := Cat("b111".U,instr(28,27),funct3(0))
                    }
                }

                valid_instr := true.B
                operate_unit := 1.U
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
