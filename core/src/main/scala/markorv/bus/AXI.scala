package markorv.bus

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._

class AXIHandler(val axiConfig: AxiConfig, val ioConfig: IOConfig, val id: Int) extends Module {
    val io = IO(new Bundle {
        val req = new IOInterface()(ioConfig, false)
        val axi = new AxiInterface(axiConfig)
    })
    val maxAxSize = (log2Ceil(axiConfig.dataWidth) - 3).U
    val hasResp = RegInit(false.B)
    val hasFailed = RegInit(false.B)

    val burstLen = ioConfig.burstLen(axiConfig.dataWidth)
    class ReadState extends Bundle {
        val work = Bool()
        val bptr = if(burstLen != 0) Some(UInt(log2Ceil(burstLen+1).W)) else None
    }
    class WriteState extends Bundle {
        val work = Bool()
        val resp = Bool()
        val bptr = if(burstLen != 0) Some(UInt(log2Ceil(burstLen+1).W)) else None
    }

    if(ioConfig.read) {
        val channel = io.req.read.get
        channel.params.ready := false.B
        channel.resp.valid := false.B
        channel.resp.bits := new ReadResp(ioConfig.dataWidth).zero
    }
    if(ioConfig.write) {
        val channel = io.req.write.get
        channel.params.ready := false.B
        channel.resp.valid := false.B
        channel.resp.bits := AxiResp.okay
    }

    io.axi.aw.valid := false.B
    io.axi.aw.bits := new AxiWriteAddressBundle(axiConfig).zero
    io.axi.w.valid := false.B
    io.axi.w.bits := new AxiWriteDataBundle(axiConfig).zero
    io.axi.ar.valid := false.B
    io.axi.ar.bits := new AxiReadAddressBundle(axiConfig).zero
    io.axi.b.ready := false.B
    io.axi.r.ready := false.B

    if(ioConfig.read) {
        val channel = io.req.read.get
        val rstate = RegInit(new ReadState().zero)
        val rtemp = if(burstLen != 0) Some(RegInit(0.U(ioConfig.dataWidth.W))) else None
        val ready = ~rstate.work && io.axi.ar.ready

        channel.params.ready := ready
        when(~rstate.work && channel.params.valid) {
            // Valid can't be related to ready
            io.axi.ar.valid := true.B
            io.axi.ar.bits.addr := channel.params.bits.addr
            if (burstLen != 0) {
                io.axi.ar.bits.size := maxAxSize
            } else {
                io.axi.ar.bits.size := channel.params.bits.size
            }
            io.axi.ar.bits.burst := "b01".U
            io.axi.ar.bits.cache := "b0011".U
            io.axi.ar.bits.id := id.U
            io.axi.ar.bits.len := burstLen.U
            io.axi.ar.bits.lock := (if(ioConfig.atomicity) channel.params.bits.lock.get else 0.U)
            io.axi.ar.bits.qos := 0.U
            io.axi.ar.bits.region := 0.U
            io.axi.ar.bits.prot := 0.U
        }

        when(ready && channel.params.valid) {
            // Req handshake succeed
            rstate.work := true.B
            if(burstLen != 0) {
                rtemp.get := 0.U
                rstate.bptr.get := 0.U
            }
        }
        when(rstate.work) {
            io.axi.r.ready := true.B

            when(io.axi.r.valid) {
                hasResp := true.B
                when(io.axi.r.bits.resp =/= 0.U) {
                    hasFailed := true.B
                }
                when(io.axi.r.bits.last) {
                    // Should always be ready here
                    channel.resp.valid := true.B
                    channel.resp.bits.resp := AxiResp(io.axi.r.bits.resp)
                    rstate.work := false.B
                    if(burstLen != 0) {
                        channel.resp.bits.data := (io.axi.r.bits.data << (rstate.bptr.get * axiConfig.dataWidth.U)) | rtemp.get
                    } else {
                        channel.resp.bits.data := io.axi.r.bits.data
                    }
                }.otherwise {
                    // burstLen should not be 0 here
                    if(burstLen != 0) {
                        rtemp.get := (io.axi.r.bits.data << (rstate.bptr.get * axiConfig.dataWidth.U)) | rtemp.get
                        rstate.bptr.get := rstate.bptr.get + 1.U
                    }
                }
            }
        }
    }

    if(ioConfig.write) {
        val channel = io.req.write.get
        val wstate = RegInit(new WriteState().zero)
        val wtemp = RegInit(0.U(ioConfig.dataWidth.W))
        val ready = ~wstate.work && io.axi.aw.ready

        channel.params.ready := ready
        when(~wstate.work && channel.params.valid) {
            // Valid can't be related to ready
            io.axi.aw.valid := true.B
            io.axi.aw.bits.addr := channel.params.bits.addr
            if (burstLen != 0) {
                io.axi.aw.bits.size := maxAxSize
            } else {
                io.axi.aw.bits.size := channel.params.bits.size
            }
            io.axi.aw.bits.burst := "b01".U
            io.axi.aw.bits.cache := "b0011".U
            io.axi.aw.bits.id := id.U
            io.axi.aw.bits.len := burstLen.U
            io.axi.aw.bits.lock := (if(ioConfig.atomicity) channel.params.bits.lock.get else 0.U)
            io.axi.aw.bits.qos := 0.U
            io.axi.aw.bits.region := 0.U
            io.axi.aw.bits.prot := 0.U
        }

        when(ready && channel.params.valid) {
            // Req handshake succeed
            wstate.work := true.B
            wtemp := channel.params.bits.data
            if(burstLen != 0) {
                wstate.bptr.get := 0.U
            }
        }

        when(wstate.work && ~wstate.resp) {
            io.axi.w.valid := true.B
            if(burstLen != 0) {
                val last = wstate.bptr.get === burstLen.U
                io.axi.w.bits.last := last
                io.axi.w.bits.data := wtemp >> (wstate.bptr.get * axiConfig.dataWidth.U)
                io.axi.w.bits.strb := ~(0.U((axiConfig.dataWidth/8).W))

                when(io.axi.w.ready) {
                    when(last) {
                        wstate.resp := true.B
                    }
                    wstate.bptr.get := wstate.bptr.get + 1.U
                }
            } else {
                io.axi.w.bits.last := true.B
                io.axi.w.bits.data := wtemp
                io.axi.w.bits.strb := ~(0.U((axiConfig.dataWidth/8).W))

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

class AxiRouter(val axiConfig: AxiConfig, val numChannel: Int) extends Module {
    val io = IO(new Bundle {
        val axiChannel = Vec(numChannel, Flipped(new AxiInterface(axiConfig)))
        val axiBus     = new AxiInterface(axiConfig)
    })

    // Read channels
    // ========================
    // Read request arbiter
    val arArb = Module(new RRArbiter(new AxiReadAddressBundle(axiConfig), numChannel))
    arArb.io.in.zip(io.axiChannel.map(_.ar)).foreach { case (arbIn, chAr) =>
        arbIn <> chAr
    }
    io.axiBus.ar <> arArb.io.out

    // Read data router
    io.axiBus.r.ready := 0.B
    for (i <- 0 until numChannel) {
        io.axiChannel(i).r.bits  := io.axiBus.r.bits
        io.axiChannel(i).r.valid := io.axiBus.r.valid && (io.axiBus.r.bits.id === i.U)
        when (io.axiBus.r.bits.id === i.U) {
            io.axiBus.r.ready := io.axiChannel(i).r.ready
        }
    }
    // Caution: Although it's logically correct to OR all `b.ready` signals as a single response for `axiBus.b.ready`,
    // this design assumes that the correct target channel (identified by `b.bits.id`) will always assert `ready` immediately.
    // However, this may cause simulation mismatch or real hardware issues:
    //
    // 1. In simulation (zero-delay model), receiving `valid` and `id` first, and *then* routing `ready` based on `id` can work.
    //    But this breaks the AXI handshake protocol timing, where both `valid` and `ready` must be stable in the same cycle.
    //
    // 2. In real hardware, routing `ready` based on `id` after seeing `valid` introduces at least one cycle of delay.
    //    This delay may violate the protocol or reduce throughput if the slave expects immediate `ready` after asserting `valid`.
    io.axiBus.r.ready := io.axiChannel.map(_.r.ready).reduce(_ || _)

    // Write channels
    // ========================
    // Write address arbiter (AW Channel)
    val awArb = Module(new RRArbiter(new AxiWriteAddressBundle(axiConfig), numChannel))
    awArb.io.in.zip(io.axiChannel.map(_.aw)).foreach { case (arbIn, chAw) =>
        arbIn <> chAw
    }
    io.axiBus.aw <> awArb.io.out

    // Write data router (W Channel)
    val wArb = Module(new RRArbiter(new AxiWriteDataBundle(axiConfig), numChannel))
    wArb.io.in.zip(io.axiChannel.map(_.w)).foreach { case (arbIn, chW) =>
        arbIn <> chW
    }
    io.axiBus.w <> wArb.io.out

    // Write response router (B Channel)
    io.axiBus.b.ready := 0.B
    for (i <- 0 until numChannel) {
        io.axiChannel(i).b.bits  := io.axiBus.b.bits
        io.axiChannel(i).b.valid := io.axiBus.b.valid && (io.axiBus.b.bits.id === i.U)
    }
    // For the same reason as above
    io.axiBus.b.ready := io.axiChannel.map(_.b.ready).reduce(_ || _)
}

class AxiCtrl(implicit val config: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val instrFetch = new IOInterface()(config.fetchIoConfig,false)
        val loadStore = new IOInterface()(config.lsuIoConfig,false)
        val axi = new AxiInterface(config.axiConfig)
    })

    val instrFetchHandler = Module(new AXIHandler(config.axiConfig, config.fetchIoConfig, 0))
    val loadStoreHandler = Module(new AXIHandler(config.axiConfig, config.lsuIoConfig, 1))
    instrFetchHandler.io.req <> io.instrFetch
    loadStoreHandler.io.req <> io.loadStore

    val axiRouter = Module(new AxiRouter(config.axiConfig, 2))

    axiRouter.io.axiChannel(0) <> instrFetchHandler.io.axi
    axiRouter.io.axiChannel(1) <> loadStoreHandler.io.axi
    io.axi <> axiRouter.io.axiBus
}