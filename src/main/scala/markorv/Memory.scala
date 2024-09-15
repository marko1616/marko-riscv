package markorv

import chisel3._
import chisel3.util._

class Memory(data_width: Int = 64, addr_width: Int = 64, size: Int = 1024) extends Module {
    val io = IO(new Bundle {
        val addr_in = Input(UInt(addr_width.W))
        val data_out = Decoupled(UInt(data_width.W))
    })

    val mem = Mem(size, UInt(8.W))

    io.data_out.bits := 0.U(data_width.W)
    io.data_out.valid := false.B

    when (io.data_out.ready) {
        io.data_out.bits := Cat((0 until data_width / 8).map(i => mem(io.addr_in + i.U)))
        io.data_out.valid := true.B
    }
}