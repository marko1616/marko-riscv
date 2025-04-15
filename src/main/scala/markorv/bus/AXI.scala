package markorv.bus

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._

class AXIHandler(val axi_config: AxiConfig, val io_config: IOConfig, val id: Int) extends Module {
    val io = IO(new Bundle {
        val req = new IOInterface()(io_config, false)
        val axi = new AxiInterface(axi_config)
    })

    val burst_len = io_config.burst_len(axi_config.data_width)
    class ReadState extends Bundle {
        val work = Bool()
        val bptr = if(burst_len != 0) Some(UInt(log2Ceil(burst_len+1).W)) else None
    }
    class WriteState extends Bundle {
        val work = Bool()
        val resp = Bool()
        val bptr = if(burst_len != 0) Some(UInt(log2Ceil(burst_len+1).W)) else None
    }

    if(io_config.read) {
        val channel = io.req.read.get
        channel.params.ready := false.B
        channel.resp.valid := false.B
        channel.resp.bits := new ReadResp(io_config.data_width).zero
    }
    if(io_config.write) {
        val channel = io.req.write.get
        channel.params.ready := false.B
        channel.resp.valid := false.B
        channel.resp.bits := AxiResp.okay
    }

    io.axi.aw.valid := false.B
    io.axi.aw.bits := new AxiWriteAddressBundle(axi_config).zero
    io.axi.w.valid := false.B
    io.axi.w.bits := new AxiWriteDataBundle(axi_config).zero
    io.axi.ar.valid := false.B
    io.axi.ar.bits := new AxiReadAddressBundle(axi_config).zero
    io.axi.b.ready := false.B
    io.axi.r.ready := false.B

    if(io_config.read) {
        val channel = io.req.read.get
        val rstate = RegInit(new ReadState().zero)
        val rtemp = if(burst_len != 0) Some(RegInit(0.U(io_config.data_width.W))) else None
        val ready = ~rstate.work && io.axi.ar.ready

        channel.params.ready := ready
        when(~rstate.work && channel.params.valid) {
            // Valid can't be related to ready
            io.axi.ar.valid := true.B
            io.axi.ar.bits.addr := channel.params.bits.addr
            io.axi.ar.bits.size := channel.params.bits.size
            io.axi.ar.bits.burst := "b01".U
            io.axi.ar.bits.cache := "b0000".U
            io.axi.ar.bits.id := id.U
            io.axi.ar.bits.len := burst_len.U
            io.axi.ar.bits.lock := (if(io_config.atomicity) channel.params.bits.lock.get else 0.U)
            io.axi.ar.bits.qos := 0.U
            io.axi.ar.bits.region := 0.U
            io.axi.ar.bits.prot := 0.U
        }

        when(ready && channel.params.valid) {
            // Req handshake succeed
            rstate.work := true.B
            if(burst_len != 0) {
                rtemp.get := 0.U
                rstate.bptr.get := 0.U
            }
        }
        when(rstate.work) {
            io.axi.r.ready := true.B
            when(io.axi.r.valid) {
                when(io.axi.r.bits.last) {
                    // Should always be ready here
                    channel.resp.valid := true.B
                    channel.resp.bits.resp := AxiResp(io.axi.r.bits.resp)
                    rstate.work := false.B
                    if(burst_len != 0) {
                        channel.resp.bits.data := (io.axi.r.bits.data << (rstate.bptr.get * axi_config.data_width.U)) | rtemp.get
                    } else {
                        channel.resp.bits.data := io.axi.r.bits.data
                    }
                }.otherwise {
                    // burst_len should not be 0 here
                    if(burst_len != 0) {
                        rtemp.get := (io.axi.r.bits.data << (rstate.bptr.get * axi_config.data_width.U)) | rtemp.get
                        rstate.bptr.get := rstate.bptr.get + 1.U
                    }
                }
            }
        }
    }

    if(io_config.write) {
        val channel = io.req.write.get
        val wstate = RegInit(new WriteState().zero)
        val wtemp = RegInit(0.U(io_config.data_width.W))
        val ready = ~wstate.work && io.axi.aw.ready

        channel.params.ready := ready
        when(~wstate.work && channel.params.valid) {
            // Valid can't be related to ready
            io.axi.aw.valid := true.B
            io.axi.aw.bits.addr := channel.params.bits.addr
            io.axi.aw.bits.size := channel.params.bits.size
            io.axi.aw.bits.burst := "b01".U
            io.axi.aw.bits.cache := "b0000".U
            io.axi.aw.bits.id := id.U
            io.axi.aw.bits.len := burst_len.U
            io.axi.aw.bits.lock := (if(io_config.atomicity) channel.params.bits.lock.get else 0.U)
            io.axi.aw.bits.qos := 0.U
            io.axi.aw.bits.region := 0.U
            io.axi.aw.bits.prot := 0.U
        }

        when(ready && channel.params.valid) {
            // Req handshake succeed
            wstate.work := true.B
            wtemp := channel.params.bits.data
            if(burst_len != 0) {
                wstate.bptr.get := 0.U
            }
        }

        when(wstate.work && ~wstate.resp) {
            io.axi.w.valid := true.B
            if(burst_len != 0) {
                val last = wstate.bptr.get === burst_len.U
                io.axi.w.bits.last := last
                io.axi.w.bits.data := wtemp >> (wstate.bptr.get * io_config.data_width.U)
                io.axi.w.bits.strb := ~(0.U((axi_config.data_width/8).W))

                when(io.axi.w.ready) {
                    when(last) {
                        wstate.resp := true.B
                    }
                    wstate.bptr.get := wstate.bptr.get + 1.U
                }
            } else {
                io.axi.w.bits.last := true.B
                io.axi.w.bits.data := wtemp
                io.axi.w.bits.strb := ~(0.U((axi_config.data_width/8).W))

                when(io.axi.w.ready) {
                    wstate.resp := true.B
                }
            }
        }

        when(wstate.work && wstate.resp) {
            io.axi.b.ready := true.B

            when(io.axi.b.valid) {
                // Should always be ready here
                channel.resp.valid := true.B
                channel.resp.bits := AxiResp(io.axi.b.bits.resp)
                wstate.work := false.B
                wstate.resp := false.B
            }
        }
    }
}

class AxiRouter(val axi_config: AxiConfig, val num_channel: Int) extends Module {
    val io = IO(new Bundle {
        val axi_channel = Vec(num_channel, Flipped(new AxiInterface(axi_config)))
        val axi_bus     = new AxiInterface(axi_config)
    })

    // Read channels
    // ========================
    // Read request arbiter
    val ar_arb = Module(new RRArbiter(new AxiReadAddressBundle(axi_config), num_channel))
    ar_arb.io.in.zip(io.axi_channel.map(_.ar)).foreach { case (arb_in, ch_ar) =>
        arb_in <> ch_ar
    }
    io.axi_bus.ar <> ar_arb.io.out

    // Read data router
    io.axi_bus.r.ready := 0.B
    for (i <- 0 until num_channel) {
        io.axi_channel(i).r.bits  := io.axi_bus.r.bits
        io.axi_channel(i).r.valid := io.axi_bus.r.valid && (io.axi_bus.r.bits.id === i.U)
        when (io.axi_bus.r.bits.id === i.U) {
            io.axi_bus.r.ready := io.axi_channel(i).r.ready
        }
    }
    // Caution: Although it's logically correct to OR all `b.ready` signals as a single response for `axi_bus.b.ready`,
    // this design assumes that the correct target channel (identified by `b.bits.id`) will always assert `ready` immediately.
    // However, this may cause simulation mismatch or real hardware issues:
    //
    // 1. In simulation (zero-delay model), receiving `valid` and `id` first, and *then* routing `ready` based on `id` can work.
    //    But this breaks the AXI handshake protocol timing, where both `valid` and `ready` must be stable in the same cycle.
    //
    // 2. In real hardware, routing `ready` based on `id` after seeing `valid` introduces at least one cycle of delay.
    //    This delay may violate the protocol or reduce throughput if the slave expects immediate `ready` after asserting `valid`.
    io.axi_bus.r.ready := io.axi_channel.map(_.r.ready).reduce(_ || _)

    // Write channels
    // ========================
    // Write address arbiter (AW Channel)
    val aw_arb = Module(new RRArbiter(new AxiWriteAddressBundle(axi_config), num_channel))
    aw_arb.io.in.zip(io.axi_channel.map(_.aw)).foreach { case (arb_in, ch_aw) =>
        arb_in <> ch_aw
    }
    io.axi_bus.aw <> aw_arb.io.out

    // Write data router (W Channel)
    val w_arb = Module(new RRArbiter(new AxiWriteDataBundle(axi_config), num_channel))
    w_arb.io.in.zip(io.axi_channel.map(_.w)).foreach { case (arb_in, ch_w) =>
        arb_in <> ch_w
    }
    io.axi_bus.w <> w_arb.io.out

    // Write response router (B Channel)
    io.axi_bus.b.ready := 0.B
    for (i <- 0 until num_channel) {
        io.axi_channel(i).b.bits  := io.axi_bus.b.bits
        io.axi_channel(i).b.valid := io.axi_bus.b.valid && (io.axi_bus.b.bits.id === i.U)
    }
    // For the same reason as above
    io.axi_bus.b.ready := io.axi_channel.map(_.b.ready).reduce(_ || _)
}

class AxiCtrl(implicit val config: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val instr_fetch = new IOInterface()(config.if_io_config,false) 
        val load_store = new IOInterface()(config.ls_io_config,false)
        val axi = new AxiInterface(config.axi_config)
    })

    val instr_fetch_handler = Module(new AXIHandler(config.axi_config, config.if_io_config, 0))
    val load_store_handler = Module(new AXIHandler(config.axi_config, config.ls_io_config, 1))
    instr_fetch_handler.io.req <> io.instr_fetch
    load_store_handler.io.req <> io.load_store

    val axi_router = Module(new AxiRouter(config.axi_config, 2))

    axi_router.io.axi_channel(0) <> instr_fetch_handler.io.axi
    axi_router.io.axi_channel(1) <> load_store_handler.io.axi
    io.axi <> axi_router.io.axi_bus
}