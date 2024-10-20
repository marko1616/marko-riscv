package markorv

import chisel3._
import chisel3.util._

class WriteBack extends Module {
    val io = IO(new Bundle {
        val write_back1 = Flipped(Decoupled(new Bundle {
            val reg = UInt(5.W)
            val data = UInt(64.W)
        }))

        val write_back2 = Flipped(Decoupled(new Bundle {
            val reg = UInt(5.W)
            val data = UInt(64.W)
        }))

        val write_back3 = Flipped(Decoupled(new Bundle {
            val reg = UInt(5.W)
            val data = UInt(64.W)
        }))

        val reg_write = Output(UInt(5.W))
        val write_data = Output(UInt(64.W))

        val outfire1 = Output(Bool())
        val outfire2 = Output(Bool())
        val outfire3 = Output(Bool())
    })

    io.write_back1.ready := true.B
    io.write_back2.ready := true.B
    io.write_back3.ready := true.B
    io.outfire1 := true.B
    io.outfire2 := true.B
    io.outfire3 := true.B

    io.reg_write := 0.U
    io.write_data := 0.U

    // Isn't possible to write back multiple times in one cycle.
    when(io.write_back1.valid) {
        io.reg_write := io.write_back1.bits.reg
        io.write_data := io.write_back1.bits.data
    }

    when(io.write_back2.valid) {
        io.reg_write := io.write_back2.bits.reg
        io.write_data := io.write_back2.bits.data
    }

    when(io.write_back3.valid) {
        io.reg_write := io.write_back3.bits.reg
        io.write_data := io.write_back3.bits.data
    }
}
