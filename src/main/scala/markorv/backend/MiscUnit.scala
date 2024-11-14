package markorv.backend

import chisel3._
import chisel3.util._

import markorv.ControlStatusRegistersIO
import markorv.frontend.DecoderOutParams

class MiscUnit extends Module {
    val io = IO(new Bundle {
        // misc_opcode encoding:
        // Bit [2,0] == 4 AMO operate reserved.
        // Bit [2,0] == 3 System operate(ecall ebrack etc) reserved.
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

    when(io.misc_instr.valid) {
        when(opcode(2,0) === 0.U) {
            val csr_src1 = params.source1
            val csr_addr = params.source2
            val csr_func = params.immediate
            // If rs1 == x0 csrrc and csrrs shall not cause any side effect like illegal instruction exceptions.
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
    }
}
