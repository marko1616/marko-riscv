package markorv.bus

import chisel3._
import chisel3.util._

class IOInterface(data_width: Int, addr_width: Int) extends Bundle {
    val read_addr = Flipped(Decoupled(UInt(addr_width.W)))
    val read_data = Decoupled(UInt(data_width.W))
    val write_req = Flipped(Decoupled(new Bundle {
        val size = UInt(2.W)
        val addr = UInt(addr_width.W)
        val data = UInt(data_width.W)
    }))
    val write_outfire = Output(Bool())
}

class AxiLiteMasterIO(data_width: Int, addr_width: Int) extends Bundle {
    // Write req
    val awvalid = Output(Bool())
    val awready = Input(Bool())
    val awaddr = Output(UInt(addr_width.W))
    val awprot = Output(UInt(3.W))

    // Write data
    val wvalid = Output(Bool())
    val wready = Input(Bool())
    val wdata = Output(UInt(data_width.W))
    val wstrb = Output(UInt((data_width / 8).W))

    // Write resp
    val bvalid = Input(Bool())
    val bready = Output(Bool())
    val bresp = Input(UInt(2.W))

    // Read req
    val arvalid = Output(Bool())
    val arready = Input(Bool())
    val araddr = Output(UInt(addr_width.W))
    val arprot = Output(UInt(3.W))

    // Read data
    val rvalid = Input(Bool())
    val rready = Output(Bool())
    val rdata = Input(UInt(data_width.W))
    val rresp = Input(UInt(2.W))
}