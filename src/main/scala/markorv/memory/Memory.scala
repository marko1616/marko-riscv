package markorv.memory

import chisel3._
import chisel3.util._

class MemoryIO(data_width: Int, addr_width: Int) extends Bundle {
    val read_addr = Flipped(Decoupled(UInt(addr_width.W)))
    val data_out = Decoupled(UInt(data_width.W))
    val mem_write_req = Flipped(Decoupled(new Bundle {
        val size = UInt(2.W)
        val addr = UInt(addr_width.W)
        val data = UInt(data_width.W)
    }))
    val write_outfire = Output(Bool())
}

class Memory(data_width: Int = 64, addr_width: Int = 64, size: Int = 128)
    extends Module {
    val io = IO(new Bundle {
        val port1 = new MemoryIO(data_width, addr_width)
        val port2 = new MemoryIO(data_width, addr_width)
        val peek = Output(UInt(data_width.W))
    })

    // Use SyncReadMem instead of Mem
    val mem = SyncReadMem(size, UInt(8.W))

    // Initialize the memory with initial values
    val init_values = Seq(
        "h0540006f".U(32.W), //0x0
        "hfd810113".U(32.W), //0x4
        "h02113023".U(32.W), //0x8
        "h00913c23".U(32.W), //0xc
        "h00f13823".U(32.W), //0x10
        "h00050493".U(32.W), //0x14
        "h00100793".U(32.W), //0x18
        "h0097e663".U(32.W), //0x1c
        "h00048513".U(32.W), //0x20
        "h01c0006f".U(32.W), //0x24
        "hfff48513".U(32.W), //0x28
        "hfd9ff0ef".U(32.W), //0x2c
        "h00050793".U(32.W), //0x30
        "hffe48513".U(32.W), //0x34
        "hfcdff0ef".U(32.W), //0x38
        "h00a78533".U(32.W), //0x3c
        "h01013783".U(32.W), //0x40
        "h01813483".U(32.W), //0x44
        "h02013083".U(32.W), //0x48
        "h02810113".U(32.W), //0x4c
        "h00008067".U(32.W), //0x50
        "h00001137".U(32.W), //0x54
        "h8001011b".U(32.W), //0x58
        "h00400513".U(32.W), //0x5c
        "hfa5ff0ef".U(32.W), //0x60
        "h3ea03c23".U(32.W), //0x64
    )

    // Little endian initialization
    for (i <- 0 until init_values.length) {
        for (j <- 0 until 4) {
            mem.write((i * 4 + j).U, (init_values(i) >> (j * 8))(7, 0))
        }
    }

    // Arbiter to manage access between two ports
    val arbiter = Module(new Arbiter(Bool(), 2))
    arbiter.io.in(0).valid := io.port1.mem_write_req.valid || io.port1.data_out.ready
    arbiter.io.in(1).valid := io.port2.mem_write_req.valid || io.port2.data_out.ready
    arbiter.io.in(0).bits := true.B
    arbiter.io.in(1).bits := true.B

    val arbiterOut = Wire(Bool())
    arbiterOut := arbiter.io.out.ready
    arbiter.io.out.ready := true.B

    val mem_available = RegInit(false.B)

    val last_read_addr1 = RegInit(0.U(addr_width.W))
    val read_data1 = Wire(Vec(data_width / 8, UInt(8.W)))
    for (i <- 0 until data_width / 8) {
        read_data1(i) := mem.read(io.port1.read_addr.bits + i.U)
    }
    val last_read_addr2 = RegInit(0.U(addr_width.W))
    val read_data2 = Wire(Vec(data_width / 8, UInt(8.W)))
    for (i <- 0 until data_width / 8) {
        read_data2(i) := mem.read(io.port2.read_addr.bits + i.U)
    }

    last_read_addr1 := io.port1.read_addr.bits
    last_read_addr2 := io.port2.read_addr.bits

    io.port1.read_addr.ready := mem_available
    io.port1.data_out.bits := 0.U
    io.port1.data_out.valid := false.B
    io.port1.write_outfire := false.B
    io.port2.read_addr.ready := mem_available
    io.port2.data_out.bits := 0.U
    io.port2.data_out.valid := false.B
    io.port2.write_outfire := false.B

    io.port1.mem_write_req.ready := true.B
    io.port2.mem_write_req.ready := true.B

    // Peek operation to view specific memory content
    io.peek := Cat(
      mem.read(1023.U),
      mem.read(1022.U),
      mem.read(1021.U),
      mem.read(1020.U),
      mem.read(1019.U),
      mem.read(1018.U),
      mem.read(1017.U),
      mem.read(1016.U)
    )

    val write_req1 = io.port1.mem_write_req.bits
    val write_req2 = io.port2.mem_write_req.bits

    // Port 1 operations
    when(arbiter.io.chosen === 0.U) {
        when(io.port1.mem_write_req.valid) {
            switch(write_req1.size) {
                is(0.U) {
                    mem.write(write_req1.addr, write_req1.data(7, 0))
                }
                is(1.U) {
                    for (i <- 0 until 2) {
                        mem.write(
                          write_req1.addr + i.U,
                          write_req1.data((i * 8) + 7, i * 8)
                        )
                    }
                }
                is(2.U) {
                    for (i <- 0 until 4) {
                        mem.write(
                          write_req1.addr + i.U,
                          write_req1.data((i * 8) + 7, i * 8)
                        )
                    }
                }
                is(3.U) {
                    for (i <- 0 until 8) {
                        mem.write(
                          write_req1.addr + i.U,
                          write_req1.data((i * 8) + 7, i * 8)
                        )
                    }
                }
            }
            io.port1.write_outfire := true.B
        }.elsewhen(io.port1.data_out.ready && mem_available) {
            // Read from SyncReadMem, data available in the next cycle
            when(io.port1.read_addr.bits === last_read_addr1) {
                io.port1.data_out.valid := true.B
                io.port1.data_out.bits := Cat(read_data1.reverse)
            }
        }.elsewhen(io.port1.data_out.ready) {
            mem_available := true.B
        }
    }

    // Port 2 operations
    when(arbiter.io.chosen === 1.U) {
        when(io.port2.mem_write_req.valid) {
            switch(write_req2.size) {
                is(0.U) {
                    mem.write(write_req2.addr, write_req2.data(7, 0))
                }
                is(1.U) {
                    for (i <- 0 until 2) {
                        mem.write(
                          write_req2.addr + i.U,
                          write_req2.data((i * 8) + 7, i * 8)
                        )
                    }
                }
                is(2.U) {
                    for (i <- 0 until 4) {
                        mem.write(
                          write_req2.addr + i.U,
                          write_req2.data((i * 8) + 7, i * 8)
                        )
                    }
                }
                is(3.U) {
                    for (i <- 0 until 8) {
                        mem.write(
                          write_req2.addr + i.U,
                          write_req2.data((i * 8) + 7, i * 8)
                        )
                    }
                }
            }
            io.port2.write_outfire := true.B
        }.elsewhen(io.port2.data_out.ready && mem_available) {
            // Read from SyncReadMem, data available in the next cyclve
            when(io.port2.read_addr.bits === last_read_addr2) {
                io.port2.data_out.valid := true.B
                io.port2.data_out.bits := Cat(read_data2.reverse)
            }
        }.elsewhen(io.port2.data_out.ready) {
            mem_available := true.B
        }
    }
}
