package markorv.io

import chisel3._
import chisel3.util._

class MemoryIO(data_width: Int, addr_width: Int) extends Bundle {
    val read_addr = Flipped(Decoupled(UInt(addr_width.W)))
    val read_data = Decoupled(UInt(data_width.W))
    val write_req = Flipped(Decoupled(new Bundle {
        val size = UInt(2.W)
        val addr = UInt(addr_width.W)
        val data = UInt(data_width.W)
    }))
    val write_outfire = Output(Bool())
}

class MemoryCtrl(data_width: Int, addr_width: Int) extends Module {
    val io = IO(new Bundle {
        val port1 = new MemoryIO(data_width, addr_width)
        val port2 = new MemoryIO(data_width, addr_width)

        val outer = Flipped(new MemoryIO(data_width, addr_width))
    })

    val port1_req = io.port1.write_req.valid || io.port1.read_addr.valid
    val port2_req = io.port2.write_req.valid || io.port2.read_addr.valid

    io.outer.read_data.ready := false.B
    io.outer.read_addr.valid := false.B
    io.outer.read_addr.bits := 0.U

    io.outer.write_req.valid := false.B
    io.outer.write_req.bits.size := 0.U
    io.outer.write_req.bits.addr := 0.U
    io.outer.write_req.bits.data := 0.U

    io.port1.read_addr.ready := true.B
    io.port1.read_data.valid := false.B
    io.port1.read_data.bits := 0.U

    io.port1.write_req.ready := true.B
    io.port1.write_outfire := false.B

    io.port2.read_addr.ready := true.B
    io.port2.read_data.valid := false.B
    io.port2.read_data.bits := 0.U

    io.port2.write_req.ready := true.B
    io.port2.write_outfire := false.B

    when(port1_req && !port2_req) {
        io.outer.read_addr <> io.port1.read_addr
        io.outer.read_data <> io.port1.read_data

        io.outer.write_req <> io.port1.write_req
        io.outer.write_outfire <> io.port1.write_outfire
    }.elsewhen(!port1_req && port2_req) {
        io.outer.read_addr <> io.port2.read_addr
        io.outer.read_data <> io.port2.read_data

        io.outer.write_req <> io.port2.write_req
        io.outer.write_outfire <> io.port2.write_outfire
    }.elsewhen(port1_req && port2_req) {
        io.outer.read_addr <> io.port1.read_addr
        io.outer.read_data <> io.port1.read_data

        io.outer.write_req <> io.port1.write_req
        io.outer.write_outfire <> io.port1.write_outfire
    }
}
