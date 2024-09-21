package markorv

import chisel3._
import chisel3.util._

class RegFile(data_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        val read_addr1 = Input(UInt(5.W))
        val read_addr2 = Input(UInt(5.W))
        val read_data1 = Output(UInt(data_width.W))
        val read_data2 = Output(UInt(data_width.W))

        val write_addr1 = Input(UInt(5.W))
        val write_data1 = Input(UInt(data_width.W))

        val write_addr2 = Input(UInt(5.W))
        val write_data2 = Input(UInt(data_width.W))

        val write_addr3 = Input(UInt(5.W))
        val write_data3 = Input(UInt(data_width.W))

        val acquire_reg = Input(UInt(5.W))
        val acquired = Output(Bool())

        val peek_occupied = Output(UInt(32.W))
        val flush = Input(Bool())
    })
    val reg_acquire_flags = RegInit(0.U(32.W))
    val reg_acquire_flags_next1 = Wire(UInt(32.W))
    val reg_acquire_flags_next2 = Wire(UInt(32.W))
    val reg_acquire_flags_next3 = Wire(UInt(32.W))

    val regs = RegInit(VecInit(Seq.fill(32)(0.U(data_width.W))))

    io.read_data1 := Mux(io.read_addr1 === 0.U, 0.U, regs(io.read_addr1))
    io.read_data2 := Mux(io.read_addr2 === 0.U, 0.U, regs(io.read_addr2))

    io.acquired := false.B
    io.peek_occupied := reg_acquire_flags

    when(io.write_addr1 =/= 0.U) {
        regs(io.write_addr1) := io.write_data1
        reg_acquire_flags_next1 := reg_acquire_flags & ~(1.U << io.write_addr1)
    }.otherwise {
        reg_acquire_flags_next1 := reg_acquire_flags
    }

    when(io.write_addr2 =/= 0.U) {
        regs(io.write_addr2) := io.write_data2
        reg_acquire_flags_next2 := reg_acquire_flags_next1 & ~(1.U << io.write_addr2)
    }.otherwise {
        reg_acquire_flags_next2 := reg_acquire_flags_next1
    }
    
    when(io.write_addr3 =/= 0.U) {
        regs(io.write_addr3) := io.write_data3
        reg_acquire_flags_next3 := reg_acquire_flags_next2 & ~(1.U << io.write_addr3)
    }.otherwise {
        reg_acquire_flags_next3 := reg_acquire_flags_next2
    }

    when(io.acquire_reg =/= 0.U && ~reg_acquire_flags(io.acquire_reg)) {
        reg_acquire_flags := reg_acquire_flags_next3 | (1.U << io.acquire_reg)
        io.acquired := true.B
    }.elsewhen(io.acquire_reg === 0.U) {
        reg_acquire_flags := reg_acquire_flags_next3
        io.acquired := true.B
    }.otherwise {
        reg_acquire_flags := reg_acquire_flags_next3
    }

    when(io.flush) {
        reg_acquire_flags := 0.U
    }
}
