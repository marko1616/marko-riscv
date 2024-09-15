package markorv

import chisel3._
import chisel3.util._

class InstrIPBundle(addr_width: Int = 64) extends Bundle {
    val instr = Output(UInt(32.W))
    val pc = Output(UInt(addr_width.W))
}

class InstrFetchUnit(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        val mem_read_data = Flipped(Decoupled((UInt(data_width.W))))
        val mem_read_addr = Output(UInt(addr_width.W))

        val instr_bundle = Decoupled(new InstrIPBundle)

        val pc_in = Input(UInt(addr_width.W))
        val set_pc = Input(Bool())
    })

    val pc = RegInit(0.U(addr_width.W))
    val next_pc = Wire(UInt(addr_width.W))

    val instr_buffer = RegInit(0.U(data_width.W))
    val buffer_valid = RegInit(false.B)
    val buffer_at = RegInit(0.U(log2Ceil(data_width / 32).W))

    io.mem_read_data.ready := false.B
    io.instr_bundle.valid := false.B
    io.mem_read_addr := 0.U(addr_width.W)
    io.instr_bundle.bits.instr := 0.U(32.W)

    when(!buffer_valid) {
        // read from memory
        io.mem_read_data.ready := true.B
        io.mem_read_addr := pc
        when(io.mem_read_data.valid) {
            instr_buffer := io.mem_read_data.bits
            buffer_valid := true.B
            buffer_at := 0.U
        }
        next_pc := pc
    }.otherwise {
        io.instr_bundle.valid := true.B

        val shifted_buffer = instr_buffer >> (buffer_at * 32.U)
        val selected_bits = shifted_buffer(31, 0)
        io.instr_bundle.bits.instr := selected_bits

        when(io.instr_bundle.ready) {
            buffer_at := buffer_at + 1.U
            buffer_valid := (buffer_at =/= log2Ceil(data_width / 32).U)
            next_pc := pc + 4.U
        }.otherwise {
            next_pc := pc
        }
    }

    when(io.set_pc) {
        pc := io.pc_in
        buffer_at := 0.U
        buffer_valid := false.B 
    }.otherwise {
        pc := next_pc
    }
    io.instr_bundle.bits.pc := pc
}