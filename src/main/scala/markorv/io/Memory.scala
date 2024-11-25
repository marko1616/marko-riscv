package markorv.io

import chisel3._
import chisel3.util._

import markorv.bus.AxiLiteMasterIO

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

class AXIPort(data_width: Int, addr_width: Int) extends Module {
    val io = IO(new Bundle {
        val req = new MemoryIO(data_width, addr_width)
        val outer = new AxiLiteMasterIO(data_width, addr_width)
        val idle = Output(Bool())
    })
    object State extends ChiselEnum {
        val stat_idle, stat_wait_rdata, stat_wait_wresp = Value
    }
    val state = RegInit(State.stat_idle)
    val ready = state === State.stat_idle
    val wait_read = state === State.stat_wait_rdata
    val wait_wresp = state === State.stat_wait_wresp
    io.idle := ready

    val OKAY = "b00".U
    val EXOKAY = "b01".U
    val SLVERR = "b10".U
    val DECERR = "b11".U

    io.outer.awvalid := false.B
    io.outer.awaddr := 0.U
    io.outer.awprot := 0.U

    io.outer.wvalid := false.B
    io.outer.wdata := 0.U
    io.outer.wstrb := 0.U

    io.outer.bready := false.B

    io.outer.arvalid := false.B
    io.outer.araddr := 0.U
    io.outer.arprot := 0.U

    io.outer.rready := false.B

    io.req.read_addr.ready := ready
    io.req.read_data.valid := false.B
    io.req.read_data.bits := 0.U

    io.req.write_req.ready := ready
    io.req.write_outfire := false.B

    when(ready) {
        when(io.req.read_addr.valid) {
            io.outer.arvalid := true.B
            io.outer.araddr := io.req.read_addr.bits
            io.outer.arprot := 0.U
            when(io.outer.arready) {
                state := State.stat_wait_rdata
            }
        }.elsewhen(io.req.write_req.valid) {
            val size = io.req.write_req.bits.size
            io.outer.awvalid := true.B
            io.outer.awaddr := io.req.write_req.bits.addr
            io.outer.awprot := 0.U

            io.outer.wvalid := true.B
            io.outer.wdata := io.req.write_req.bits.data
            io.outer.wstrb := MuxCase(
              "b11111111".U,
              Seq(
                (size === 0.U) -> "b00000001".U,
                (size === 1.U) -> "b00000011".U,
                (size === 2.U) -> "b00001111".U
                // size === 3.U is the default case
              )
            )
            when(io.outer.wready) {
                state := State.stat_wait_wresp
            }
        }
    }

    io.outer.rready := wait_read && io.req.read_data.ready
    when(wait_read && io.outer.rvalid) {
        when(io.outer.rresp === OKAY | io.outer.rresp === EXOKAY) {
            io.req.read_data.valid := true.B
            io.req.read_data.bits := io.outer.rdata
        }.otherwise {
            // TODO access error.
            // If don't do this a out range branch prediction will dead lock hold CPU.
            io.req.read_data.valid := true.B
            io.req.read_data.bits := 0.U
        }
        when(io.req.read_data.ready) {
            state := State.stat_idle
        }
    }

    io.outer.bready := wait_wresp
    when(wait_wresp && io.outer.bvalid) {
        when(io.outer.bresp === OKAY | io.outer.bresp === EXOKAY) {
            io.req.write_outfire := true.B
        }.otherwise {
            // TODO access error.
            // If don't do this a out range branch prediction will dead lock hold CPU.
            io.req.write_outfire := true.B
        }
        state := State.stat_idle
    }
}

class AXICtrl(data_width: Int, addr_width: Int) extends Module {
    val io = IO(new Bundle {
        val ports = Vec(3, new MemoryIO(data_width, addr_width))
        val outer = new AxiLiteMasterIO(data_width, addr_width)
    })

    val axi_port = Module(new AXIPort(data_width, addr_width))
    val ready = axi_port.io.idle

    io.outer <> axi_port.io.outer

    for (i <- 0 until io.ports.length) {
        io.ports(i).read_addr.ready := ready
        io.ports(i).read_data.valid := false.B
        io.ports(i).read_data.bits := 0.U
        io.ports(i).write_req.ready := ready
        io.ports(i).write_outfire := false.B
    }

    val port_reqs = VecInit(io.ports.map(p => p.write_req.valid || p.read_addr.valid))
    val req_port_reg = RegInit(0.U(log2Ceil(io.ports.length).W))
    val req_port = WireInit(0.U(log2Ceil(io.ports.length).W))

    when(ready) {
        for (i <- 0 until io.ports.length) {
            when(port_reqs(i)) {
                req_port := i.U
            }
        }
    }

    when(ready) {
        req_port_reg := req_port
        axi_port.io.req <> io.ports(req_port)
    }.otherwise {
        axi_port.io.req <> io.ports(req_port_reg)
    }
}
