package markorv

import chisel3._
import chisel3.util._

class WriteBack extends Module {
    val io = IO(new Bundle {
        val write_backs = Vec(4, Flipped(Decoupled(new Bundle {
              val reg = UInt(5.W)
              val data = UInt(64.W)
          }))
        )

        val reg_write = Output(UInt(5.W))
        val write_data = Output(UInt(64.W))

        val outfires = Vec(4, Output(Bool()))
    })

    for (i <- 0 until io.write_backs.length) {
        io.write_backs(i).ready := true.B
        io.outfires(i) := true.B
    }

    io.reg_write := 0.U
    io.write_data := 0.U

    // Impossible to write back multiple times in one cycle.
    for (i <- 0 until io.write_backs.length) {
        when(io.write_backs(i).valid) {
            io.reg_write := io.write_backs(i).bits.reg
            io.write_data := io.write_backs(i).bits.data
        }
    }
}
