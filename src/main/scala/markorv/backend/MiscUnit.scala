package markorv.backend

import chisel3._
import chisel3.util._

import markorv.trap._
import markorv.frontend.DecoderOutParams
import markorv.ControlStatusRegistersIO

class MiscUnit extends Module {
    val io = IO(new Bundle {
        // misc_opcode encoding:
        // Bit [2,0] == 4 MISC-MEM operate.
        // Bit [2,0] == 3 System operate(mret ecall ebrack wfi).
        // Bit [4,3] == 0 wfi
        // Bit [4,3] == 1 ecall
        // Bit [4,3] == 2 ebreak
        // Bit [4,3] == 3 mret
        // Bit [2,0] == 2 Fence operate(fence fence.i) reserved.
        // Bit [2,0] == 1 Cache line operate(zicbox) reserved.
        // Bit [2,0] == 0 CSR(zicsr) operate. Bit[3] = rs1 == x0 ? 1 : 0.
        val misc_instr = Flipped(Decoupled(new Bundle {
            val misc_opcode = UInt(5.W)
            val params = new DecoderOutParams(64)
        }))

        val write_back = Decoupled(new Bundle {
            val reg = Input(UInt(5.W))
            val data = Input(UInt(64.W))
        })

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

    // Immediate shall handle by decoder.
    val CSRRW = "b01".U
    val CSRRS = "b10".U
    val CSRRC = "b11".U
    io.csrio.write_en := false.B
    io.csrio.read_addr := 0.U
    io.csrio.write_addr := 0.U
    io.csrio.write_data := 0.U

    io.outfire := false.B
    io.misc_instr.ready := true.B
    io.write_back.valid := false.B
    io.write_back.bits.reg := 0.U
    io.write_back.bits.data := 0.U

    io.exception.valid := false.B
    io.exception.bits.cause := 0.U
    io.exception.bits.ret_addr := 0.U

    io.get_privilege := privilege_reg
    io.set_privilege.ready := true.B
    io.trap_ret := false.B

    when(io.misc_instr.valid) {
        when(opcode(2,0) === 0.U) {
            val csr_src1 = params.source1
            val csr_addr = params.source2
            val csr_func = params.immediate
            /*
            * If rs1 == x0, the `csrrc` and `csrrs` instructions behave as read-only operations.
            * They will not cause any side effects, such as illegal instruction exceptions,
            * when attempting to write to a read-only CSR (Control and Status Register).
            */
            val csr_ro = opcode(3) && csr_func =/= CSRRW

            // Reserved for illegal instruction exceptions.
            val is_csr_read_only = csr_addr(11, 10) === 3.U
            val has_privilege = privilege_reg > csr_addr(9, 8)

            io.csrio.read_addr := csr_addr
            val csr_data = io.csrio.read_data

            io.csrio.write_en := ~csr_ro
            io.csrio.write_addr := csr_addr
            switch(csr_func(1, 0)) {
                is(CSRRW) {
                    io.csrio.write_data := csr_src1
                }
                is(CSRRS) {
                    io.csrio.write_data := csr_data | csr_src1
                }
                is(CSRRC) {
                    io.csrio.write_data := csr_data & ~csr_src1
                }
            }

            io.write_back.valid := true.B
            io.write_back.bits.reg := params.rd
            io.write_back.bits.data := csr_data
            io.outfire := true.B
        }
        when(opcode(2,0) === 3.U) {
            when(opcode(4,3) === 0.U) {
                // wfi == nop
                io.outfire := true.B
            }
            when(opcode(4,3) === 1.U) {
                // ecall
                io.exception.valid := true.B
                io.exception.bits.cause := 11.U
                io.exception.bits.ret_addr := params.pc + 4.U
                io.outfire := true.B
            }
            when(opcode(4,3) === 2.U) {
                // ebreak
                io.exception.valid := true.B
                io.exception.bits.cause := 3.U
                io.exception.bits.ret_addr := params.pc + 4.U
                io.outfire := true.B
            }
            when(opcode(4,3) === 3.U) {
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
