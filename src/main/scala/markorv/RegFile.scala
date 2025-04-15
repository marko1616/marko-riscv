package markorv

import chisel3._
import chisel3.util._

class RegFile(data_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        val read_addrs = Vec(3, Input(UInt(5.W)))
        val read_datas = Vec(3, Output(UInt(data_width.W)))

        val write_addr = Input(UInt(5.W))
        val write_data = Input(UInt(data_width.W))

        val acquire_reg = Input(UInt(5.W))
        val acquired = Output(Bool())

        val get_occupied = Output(UInt(32.W))
        val flush = Input(Bool())
    })
    // While read and write a same register simultaneously will output write data.
    val reg_acquire_flags = RegInit(0.U(32.W))
    val reg_acquire_flags_next = Wire(UInt(32.W))

    val regs = RegInit(VecInit(Seq.fill(32)(0.U(data_width.W))))

    io.acquired := false.B

    when(io.write_addr =/= 0.U) {
        regs(io.write_addr) := io.write_data
        reg_acquire_flags_next := reg_acquire_flags & ~(1.U << io.write_addr)
    }.otherwise {
        reg_acquire_flags_next := reg_acquire_flags
    }

    for (i <- 0 until io.read_addrs.length) {
        io.read_datas(i) := Mux(
          io.write_addr === io.read_addrs(i),
          io.write_data,
          Mux(io.read_addrs(i) === 0.U, 0.U, regs(io.read_addrs(i)))
        )
    }
    io.get_occupied := reg_acquire_flags_next

    // Acquire regiser lastly to avoid allow release and acquire simultaneously.
    when(io.acquire_reg =/= 0.U && ~reg_acquire_flags(io.acquire_reg)) {
        reg_acquire_flags := reg_acquire_flags_next | (1.U << io.acquire_reg)
        io.acquired := true.B
    }.elsewhen(io.acquire_reg === 0.U) {
        reg_acquire_flags := reg_acquire_flags_next
        io.acquired := true.B
    }.otherwise {
        reg_acquire_flags := reg_acquire_flags_next
    }

    when(io.flush) {
        reg_acquire_flags := 0.U
    }
}
