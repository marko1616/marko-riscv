package markorv.bus

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._

class ReadReq extends Bundle {
    val size = UInt(2.W)
    val addr = UInt(64.W)
    val sign = Bool()
    val direct = Bool()   
}

class WriteReq extends Bundle {
    val size = UInt(2.W)
    val addr = UInt(64.W)
    val data = UInt(64.W)
    val direct = Bool()
}

class LoadStoreController extends Module {
    val io = IO(new Bundle {
        val read_req = Flipped(Decoupled(new ReadReq))
        val read_data = Decoupled(UInt(64.W))

        val write_req = Flipped(Decoupled(new WriteReq))
        val write_outfire = Output(Bool())
        val io_bus = Flipped(new IOInterface(64, 64))
    })
    io.read_req.ready := false.B
    io.read_data.valid := false.B
    io.read_data.bits := 0.U
    io.write_req.ready := false.B
    io.write_outfire := false.B

    io.io_bus.read_addr.valid := false.B
    io.io_bus.read_addr.bits := 0.U
    io.io_bus.read_data.ready := false.B
    io.io_bus.write_req.valid := false.B
    io.io_bus.write_req.bits.size := 0.U
    io.io_bus.write_req.bits.addr := 0.U
    io.io_bus.write_req.bits.data := 0.U

    io.io_bus.read_addr.valid := io.read_req.valid
    io.io_bus.read_addr.bits  := io.read_req.bits.addr
    io.read_req.ready := io.io_bus.read_addr.ready

    io.io_bus.read_data.ready := io.read_data.ready
    when(io.io_bus.read_data.valid) {
        val raw_data = io.io_bus.read_data.bits
        val sign = io.read_req.bits.sign
        val size = io.read_req.bits.size
        io.read_data.valid := true.B
        io.read_data.bits := MuxLookup(size, raw_data)(Seq(
            0.U -> Mux(sign, raw_data(7, 0).sextu(64), raw_data(7, 0).zextu(64)),
            1.U -> Mux(sign, raw_data(15, 0).sextu(64), raw_data(15, 0).zextu(64)),
            2.U -> Mux(sign, raw_data(31, 0).sextu(64), raw_data(31, 0).zextu(64))
        ))
    }

    io.io_bus.write_req.valid := io.write_req.valid
    io.io_bus.write_req.bits.size := io.write_req.bits.size
    io.io_bus.write_req.bits.addr := io.write_req.bits.addr
    io.io_bus.write_req.bits.data := io.write_req.bits.data
    io.write_req.ready := io.io_bus.write_req.ready
    io.write_outfire := io.io_bus.write_outfire
}
