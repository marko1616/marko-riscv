package markorv

import chisel3._
import chisel3.util._

class WriteBack extends Module {
    val io = IO(new Bundle {
        val write_back1 = Flipped(Decoupled(new Bundle {
            val reg = UInt(5.W)
            val data = UInt(64.W)
        }))

        val reg_write = Output(UInt(5.W))
        val write_data = Output(UInt(64.W))
        val outfire1 = Output(Bool())
    })

    io.write_back1.ready := true.B
    io.outfire1 := true.B

    io.reg_write := 0.U
    io.write_data := 0.U

    when(io.write_back1.valid) {
        io.reg_write := io.write_back1.bits.reg
        io.write_data := io.write_back1.bits.data
    }
}
