package markorv

import chisel3._
import chisel3.util._

class IFU(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        val mem_read_data = Flipped(Decoupled((UInt(data_width.W))))
        val mem_read_addr = Output(UInt(addr_width.W))
        val instruction = Decoupled(UInt(32.W))
        val pc_out = Output(UInt(addr_width.W))
        val pc_in = Input(UInt(addr_width.W))
        val set_pc = Input(Bool())
    })

    val pc = RegInit(0.U(addr_width.W))
    val next_pc = Wire(UInt(addr_width.W))

    val instruction_buffer = RegInit(0.U(data_width.W))
    val buffer_valid = RegInit(false.B)
    val buffer_at = RegInit(0.U(log2Ceil(data_width / 32).W))

    io.mem_read_data.ready := false.B
    io.instruction.valid := false.B
    io.instruction.bits := 0.U(32.W)

    when(!buffer_valid) {
        // read from memory
        io.mem_read_data.ready := true.B
        io.mem_read_addr := pc
        when(io.mem_read_data.valid) {
            instruction_buffer := io.mem_read_data.bits
            buffer_valid := true.B
            buffer_at := 0.U
        }
        next_pc := pc
    }.otherwise {
        when(io.instruction.ready) {    
            io.instruction.valid := true.B
            io.instruction.bits := instruction_buffer(instruction_buffer >> (buffer_at * 32.U))(31, 0)

            next_pc := pc + 4.U
        }.otherwise {
            next_pc := pc
        }
    }

    when(io.set_pc) {
        pc := io.pc_in
    }.otherwise {
        pc := next_pc
    }
    io.pc_out := pc
}