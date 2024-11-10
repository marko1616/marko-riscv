package markorv.backend

import chisel3._
import chisel3.util._

import markorv.frontend.DecoderOutParams

class MiscUnit extends Module {
    val io = IO(new Bundle {
        // misc_opcode encoding:
        // Bit [4] AMO operate reserved.
        // Bit [3] System operate(ecall ebrack etc) reserved.
        // Bit [2] Fence operate(fence fence.i) reserved.
        // Bit [1] Cache line operate(zicbox) reserved.
        // Bit [0] CSR(zicsr) operate.
        val misc_instr = Flipped(Decoupled(new Bundle {
            val misc_opcode = UInt(5.W)
            val params = new DecoderOutParams(64)
        }))

        val write_back = Decoupled(new Bundle {
            val reg = Input(UInt(5.W))
            val data = Input(UInt(64.W))
        })
    })

}
