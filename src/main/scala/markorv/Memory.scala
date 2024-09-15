package markorv

import chisel3._
import chisel3.util._

class MemoryIO(data_width: Int, addr_width: Int) extends Bundle {
  val addr = Input(UInt(addr_width.W))
  val data_out = Decoupled(UInt(data_width.W))
  val write_enable = Input(Bool())
  val write_data = Input(UInt(data_width.W))
  val write_width = Input(UInt(2.W))
  val write_outfire = Output(Bool())
}

class Memory(data_width: Int = 64, addr_width: Int = 64, size: Int = 128) extends Module {
    val io = IO(new Bundle {
        val port1 = new MemoryIO(data_width, addr_width)
        val port2 = new MemoryIO(data_width, addr_width)
        val peek = Output(UInt(data_width.W))
    })

    val mem = Mem(size, UInt(8.W))

    // Little endian
    val init_values = Seq(
        "h00800083".U(32.W),
        "h06100FA3".U(32.W),
        "hAAAAAAFB".U(32.W),
        "hAAAAAAAA".U(32.W),
        "h00000001".U(32.W),
        "h00000002".U(32.W),
        "h00000003".U(32.W),
        "h00000004".U(32.W)
    )

    for (i <- 0 until init_values.length) {
        for (j <- 0 until 4) {
            mem(i * 4 + j) := (init_values(i) >> (j * 8))(7, 0)
        }
    }

    val arbiter = Module(new Arbiter(Bool(), 2))
    arbiter.io.in(0).valid := io.port1.write_enable || io.port1.data_out.ready
    arbiter.io.in(1).valid := io.port2.write_enable || io.port2.data_out.ready
    arbiter.io.in(0).bits := true.B
    arbiter.io.in(1).bits := true.B

    val arbiterOut = Wire(Bool())
    arbiterOut := arbiter.io.out.ready
    arbiter.io.out.ready := true.B

    io.port1.data_out.bits := 0.U
    io.port1.data_out.valid := false.B
    io.port1.write_outfire := false.B
    io.port2.data_out.bits := 0.U
    io.port2.data_out.valid := false.B
    io.port2.write_outfire := false.B

    io.peek := mem(127)

    when(arbiter.io.chosen === 0.U) {
        when(io.port1.write_enable) {
            switch(io.port1.write_width) {
                is(0.U) {
                    mem(io.port1.addr) := io.port1.write_data(7, 0)
                }
                is(1.U) {
                    for (i <- 0 until 2) {
                        mem(io.port1.addr + i.U) := io.port1.write_data((1 - i) * 8 + 7, (1 - i) * 8)
                    }
                }
                is(2.U) {
                    for (i <- 0 until 4) {
                        mem(io.port1.addr + i.U) := io.port1.write_data((3 - i) * 8 + 7, (3 - i) * 8)
                    }
                }
                is(3.U) {
                    for (i <- 0 until 8) {
                        mem(io.port1.addr + i.U) := io.port1.write_data((7 - i) * 8 + 7, (7 - i) * 8)
                    }
                }
            }
            io.port1.write_outfire := true.B
        }.elsewhen(io.port1.data_out.ready) {
            io.port1.data_out.bits := Cat((0 until data_width / 8).reverse.map(i => mem(io.port1.addr + i.U)))
            io.port1.data_out.valid := true.B
        }
    }

    when(arbiter.io.chosen === 1.U) {
        when(io.port2.write_enable) {
            switch(io.port2.write_width) {
                is(0.U) {
                    mem(io.port2.addr) := io.port2.write_data(7, 0)
                }
                is(1.U) {
                    for (i <- 0 until 2) {
                        mem(io.port2.addr + i.U) := io.port2.write_data((1 - i) * 8 + 7, (1 - i) * 8)
                    }
                }
                is(2.U) {
                    for (i <- 0 until 4) {
                        mem(io.port2.addr + i.U) := io.port2.write_data((3 - i) * 8 + 7, (3 - i) * 8)
                    }
                }
                is(3.U) {
                    for (i <- 0 until 8) {
                        mem(io.port2.addr + i.U) := io.port2.write_data((7 - i) * 8 + 7, (7 - i) * 8)
                    }
                }
            }
            io.port2.write_outfire := true.B
        }.elsewhen(io.port2.data_out.ready) {
            io.port2.data_out.bits := Cat((0 until data_width / 8).reverse.map(i => mem(io.port2.addr + i.U)))
            io.port2.data_out.valid := true.B
        }
    }
}