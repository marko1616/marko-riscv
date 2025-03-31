package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend._
import markorv.backend._

class DecoderOutParams(data_width: Int = 64) extends Bundle {
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
    val lsu_opcode = new LoadStoreOpcode
    val misc_opcode = new MiscOpcode
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

    val issue_task = WireInit(new IssueTask().zero)
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
                val norm_opcode = WireInit(new LoadStoreOpcodeNorm().zero)
                norm_opcode.from_load(funct3)
                issue_task.lsu_opcode.fromnorm(norm_opcode)
                params.source1 := instr(31, 20).asSInt
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
                val norm_opcode = WireInit(new LoadStoreOpcodeNorm().zero)
                norm_opcode.from_store(funct3)
                issue_task.lsu_opcode.fromnorm(norm_opcode)
                params.source1 := Cat(
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
                issue_task.branch_opcode.from_jal(branch_op)
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
                issue_task.branch_opcode.from_jal(branch_op)
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
                issue_task.branch_opcode.from_branch(branch_op)
                issue_task.pred_taken := io.instr_bundle.bits.pred_taken
                issue_task.pred_pc := io.instr_bundle.bits.pred_pc
                issue_task.recover_pc := io.instr_bundle.bits.recover_pc

                reg_source_requests.source1 := rs1
                reg_source_requests.source2 := rs2

                valid_instr := true.B
                operate_unit := 4.U
            }
            is(OP_SYSTEM) {
                val imm12 = instr(31, 20)
                issue_task.misc_opcode.from_sys(imm12, funct3, rs1, rd)
                when(funct3 =/= 0.U) {
                    val is_imm = funct3(2)
                    params.source2 := imm12
                    when(is_imm) {
                        params.source1 := rs1.pad(64)
                    }.otherwise {
                        reg_source_requests.source1 := rs1
                    }
                    params.rd := rd
                }
                valid_instr := true.B
                operate_unit := 2.U
            }
            is(OP_MISC_MEM) {
                val imm12 = instr(31, 20)
                issue_task.misc_opcode.from_miscmem(imm12, funct3, rs1, rd)
                valid_instr := true.B
                operate_unit := 2.U
            }
            is(OP_AMO) {
                val amo_opcode = WireInit(new LoadStoreOpcodeAmo().zero)
                amo_opcode.from_instr(funct3, funct7)
                issue_task.lsu_opcode.from_amo(amo_opcode)
                reg_source_requests.source1 := rs1
                reg_source_requests.source2 := rs2
                params.rd := rd
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
