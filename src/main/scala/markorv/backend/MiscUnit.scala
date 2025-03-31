package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.trap._
import markorv.frontend.DecoderOutParams
import markorv.ControlStatusRegistersIO

object CSROp extends ChiselEnum {
    val csrrw = Value("h1".U)
    val csrrs = Value("h2".U)
    val csrrc = Value("h3".U)
}

object SystemOp extends ChiselEnum {
    val ecall = Value("h1".U)
    val ebreak = Value("h2".U)
    val wfi = Value("h3".U)
    val mret = Value("h4".U)
}

object MemOp extends ChiselEnum {
    val fence = Value("h1".U)
}

class MiscUnit extends Module {
    val io = IO(new Bundle {
        val misc_instr = Flipped(Decoupled(new Bundle {
            val misc_opcode = new MiscOpcode
            val params = new DecoderOutParams(64)
        }))
        val register_commit = Decoupled(new RegisterCommit)

        val csrio = Flipped(new ControlStatusRegistersIO)
        val outfire = Output(Bool())

        val get_privilege = Output(UInt(2.W))
        val set_privilege = Flipped(Decoupled(UInt(2.W)))

        val exception = Decoupled(new ExceptionInfo)
        val trap_ret = Output(Bool())
    })
    // M mode when reset.
    val privilege_reg = RegInit(3.U(2.W))
    val opcode = io.misc_instr.bits.misc_opcode
    val params = io.misc_instr.bits.params
    val (csr_op, valid_csr_op) = CSROp.safe(opcode.misc_csr_funct(1,0))
    val (sys_op, valid_sys_op) = SystemOp.safe(opcode.misc_sys_funct)
    val (mem_op, valid_mem_op) = MemOp.safe(opcode.misc_mem_funct)
    val valid_op = io.misc_instr.valid && (valid_csr_op | valid_sys_op | valid_mem_op)

    io.csrio.read_en := false.B
    io.csrio.write_en := false.B
    io.csrio.read_addr := 0.U
    io.csrio.write_addr := 0.U
    io.csrio.write_data := 0.U

    io.outfire := false.B
    io.misc_instr.ready := true.B
    io.register_commit.valid := false.B
    io.register_commit.bits := new RegisterCommit().zero

    io.exception.valid := false.B
    io.exception.bits := new ExceptionInfo().zero

    io.get_privilege := privilege_reg
    io.set_privilege.ready := true.B
    io.trap_ret := false.B

    when(valid_op) {
        when(valid_csr_op) {
            val csr_src1 = params.source1
            val csr_addr = params.source2
            io.csrio.read_en := opcode.misc_csr_funct(3)
            io.csrio.write_en := opcode.misc_csr_funct(2)

            // Reserved for illegal instruction exceptions.
            val is_csr_read_only = csr_addr(11, 10) === 3.U
            val has_privilege = privilege_reg > csr_addr(9, 8)

            io.csrio.read_addr := csr_addr
            val csr_data = io.csrio.read_data

            io.csrio.write_addr := csr_addr
            io.csrio.write_data := MuxLookup(csr_op,0.U)(Seq(
                CSROp.csrrw -> (csr_src1),
                CSROp.csrrs -> (csr_data | csr_src1),
                CSROp.csrrc -> (csr_data & ~csr_src1),
            ))
            io.register_commit.valid := true.B
            io.register_commit.bits.reg := params.rd
            io.register_commit.bits.data := csr_data
            io.outfire := true.B
        }
        when(valid_sys_op) {
            when(sys_op === SystemOp.wfi) {
                // wfi == nop
                io.outfire := true.B
            }
            when(sys_op === SystemOp.ecall) {
                // ecall
                io.exception.valid := true.B
                io.exception.bits.cause := 11.U
                io.exception.bits.ret_addr := params.pc + 4.U
                io.outfire := true.B
            }
            when(sys_op === SystemOp.ebreak) {
                // ebreak
                io.exception.valid := true.B
                io.exception.bits.cause := 3.U
                io.exception.bits.ret_addr := params.pc + 4.U
                io.outfire := true.B
            }
            when(sys_op === SystemOp.mret) {
                // mret
                io.trap_ret := true.B
                io.outfire := true.B
            }
        }
    }

    when(io.set_privilege.valid) {
        privilege_reg := io.set_privilege.bits
    }
}
