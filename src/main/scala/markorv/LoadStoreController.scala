package markorv

import chisel3._
import chisel3.util._
import markorv.bus._

class LoadStoreController extends Module {
    val io = IO(new Bundle {
        val read_req = Flipped(Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(64.W)
            val sign = Bool()
            val direct = Bool()
        }))
        val read_data = Decoupled(UInt(64.W))

        val write_req = Flipped(Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(64.W)
            val data = UInt(64.W)
            val direct = Bool()
        }))
        val write_outfire = Output(Bool())

        val axi_bus = Flipped(new IOInterface(64, 64))
    })
    val rd_size = RegInit(0.U(2.W))
    val rd_sign = RegInit(false.B)
    val rd_offset = RegInit(0.U(3.W))

    io.read_req.ready := false.B
    io.read_data.valid := false.B
    io.read_data.bits := 0.U
    io.write_req.ready := false.B
    io.write_outfire := false.B

    io.axi_bus.read_addr.valid := false.B
    io.axi_bus.read_addr.bits := 0.U
    io.axi_bus.read_data.ready := false.B
    io.axi_bus.write_req.valid := false.B
    io.axi_bus.write_req.bits.size := 0.U
    io.axi_bus.write_req.bits.addr := 0.U
    io.axi_bus.write_req.bits.data := 0.U

    io.axi_bus.read_addr.valid := io.read_req.valid
    io.axi_bus.read_addr.bits  := io.read_req.bits.addr
    io.read_req.ready := io.axi_bus.read_addr.ready

    io.axi_bus.read_data.ready := io.read_data.ready
    when(io.axi_bus.read_data.valid) {
        val raw_data = io.axi_bus.read_data.bits
        val is_signed = io.read_req.bits.sign
        val size = io.read_req.bits.size
        io.read_data.valid := true.B
        io.read_data.bits := MuxCase(
            raw_data,
            Seq(
                (size === 0.U) -> Mux(
                is_signed,
                (raw_data(7, 0).asSInt
                    .pad(64))
                    .asUInt, // Sign extend byte
                raw_data(7, 0).pad(64) // Zero extend byte
                ),
                (size === 1.U) -> Mux(
                is_signed,
                (raw_data(15, 0).asSInt
                    .pad(64))
                    .asUInt, // Sign extend halfword
                raw_data(15, 0).pad(64) // Zero extend halfword
                ),
                (size === 2.U) -> Mux(
                is_signed,
                (raw_data(31, 0).asSInt
                    .pad(64))
                    .asUInt, // Sign extend word
                raw_data(31, 0).pad(64) // Zero extend word
                )
                // size === 3.U is the default case (raw_data)
            )
        )
    }

    io.axi_bus.write_req.valid := io.write_req.valid
    io.axi_bus.write_req.bits.size := io.write_req.bits.size
    io.axi_bus.write_req.bits.addr := io.write_req.bits.addr
    io.axi_bus.write_req.bits.data := io.write_req.bits.data
    io.write_req.ready := io.axi_bus.write_req.ready
    io.write_outfire := io.axi_bus.write_outfire
}
