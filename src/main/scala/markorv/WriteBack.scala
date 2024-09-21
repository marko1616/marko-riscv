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

        val reg_write1 = Output(UInt(5.W))
        val write_data1 = Output(UInt(64.W))

        val reg_write2 = Output(UInt(5.W))
        val write_data2 = Output(UInt(64.W))

        val reg_write3 = Output(UInt(5.W))
        val write_data3 = Output(UInt(64.W))

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

    io.reg_write1 := 0.U
    io.write_data1 := 0.U
    io.reg_write2 := 0.U
    io.write_data2 := 0.U
    io.reg_write3 := 0.U
    io.write_data3 := 0.U

    when(io.write_back1.valid) {
        io.reg_write1 := io.write_back1.bits.reg
        io.write_data1 := io.write_back1.bits.data
    }

    when(io.write_back2.valid) {
        io.reg_write2 := io.write_back2.bits.reg
        io.write_data2 := io.write_back2.bits.data
    }
    
    when(io.write_back3.valid) {
        io.reg_write3 := io.write_back3.bits.reg
        io.write_data3 := io.write_back3.bits.data
    }
}
