package markorv.bus

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.utils.ConfigUtils._
import markorv.cache.CacheType
import markorv.bus.PMAChecker
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
        channel.resp.valid := false.B
        channel.resp.bits := new ReadResp(ioConfig.dataWidth).zero
    }
    if(ioConfig.write) {
        val channel = io.req.write.get
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
        val rburst = if(burstLen != 0) Some(RegInit(false.B)) else None
        val rstate = RegInit(new ReadState().zero)
        val rtemp = if(burstLen != 0) Some(RegInit(0.U(ioConfig.dataWidth.W))) else None
        val ready = ~rstate.work && io.axi.ar.ready

        when(~rstate.work && channel.params.valid) {
            // Valid can't be related to ready
            val doBurst = maxAxSize < channel.params.bits.size
            io.axi.ar.valid := true.B
            io.axi.ar.bits.addr := channel.params.bits.addr
            if (burstLen != 0) {
                io.axi.ar.bits.size := Mux(doBurst, maxAxSize, channel.params.bits.size)
                rburst.get := doBurst
            } else {
                io.axi.ar.bits.size := channel.params.bits.size
            }
            io.axi.ar.bits.burst := "b01".U
            io.axi.ar.bits.cache := "b0011".U
            io.axi.ar.bits.id := id.U
            io.axi.ar.bits.len := (if(burstLen != 0) Mux(doBurst, burstLen.U, 0.U) else 0.U)
            io.axi.ar.bits.lock := (if(ioConfig.atomicity) channel.params.bits.lock.get else 0.U)
            io.axi.ar.bits.qos := 0.U
            io.axi.ar.bits.region := 0.U
            io.axi.ar.bits.prot := 0.U
        }

        when(ready && channel.params.valid) {
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
                    channel.resp.valid := true.B
                    channel.resp.bits.resp := AxiResp(io.axi.r.bits.resp)
                    rstate.work := false.B
                    if (burstLen != 0) {
                        channel.resp.bits.data := (io.axi.r.bits.data << (rstate.bptr.get * axiConfig.dataWidth.U)) | rtemp.get
                    } else {
                        channel.resp.bits.data := io.axi.r.bits.data
                    }
                }.otherwise {
                    // burstLen should not be 0 here
                    if (burstLen != 0) {
                        rtemp.get := (io.axi.r.bits.data << (rstate.bptr.get * axiConfig.dataWidth.U)) | rtemp.get
                        rstate.bptr.get := rstate.bptr.get + 1.U
                    }
                }
            }
        }
    }

    if(ioConfig.write) {
        val channel = io.req.write.get
        val wburst = if(burstLen != 0) Some(RegInit(false.B)) else None
        val wstate = RegInit(new WriteState().zero)
        val wtemp = RegInit(0.U(ioConfig.dataWidth.W))
        val ready = ~wstate.work && io.axi.aw.ready

        when(~wstate.work && channel.params.valid) {
            // Valid can't be related to ready
            val doBurst = maxAxSize < channel.params.bits.size
            io.axi.aw.valid := true.B
            io.axi.aw.bits.addr := channel.params.bits.addr
            if (burstLen != 0) {
                io.axi.aw.bits.size := Mux(doBurst, maxAxSize, channel.params.bits.size)
                wburst.get := true.B
            } else {
                io.axi.aw.bits.size := channel.params.bits.size
            }
            io.axi.aw.bits.burst := "b01".U
            io.axi.aw.bits.cache := "b0011".U
            io.axi.aw.bits.id := id.U
            io.axi.aw.bits.len := (if(burstLen != 0) Mux(doBurst, burstLen.U, 0.U) else 0.U)
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
                val last = Mux(wburst.get, wstate.bptr.get === burstLen.U, true.B)
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

    val writeChanOwner = RegInit(0.U.asTypeOf(Valid(UInt(log2Ceil(numChannel).W))))
    val writeChanOwnerOH = UIntToOH(writeChanOwner.bits, numChannel)

    // Read channels
    // ========================
    // Read request arbiter
    val arArb = Module(new RRArbiter(new AxiReadAddressBundle(axiConfig), numChannel))
    arArb.io.in.zip(io.axiChannel.map(_.ar)).foreach { case (arbIn, chAr) =>
        arbIn <> chAr
    }
    io.axiBus.ar <> arArb.io.out

    // Read data router
    for (i <- 0 until numChannel) {
        io.axiChannel(i).r.bits  := io.axiBus.r.bits
        io.axiChannel(i).r.valid := io.axiBus.r.valid && (io.axiBus.r.bits.id === i.U)
    }
    // Caution: Although it's logically correct to OR all `r.ready` signals as a single response for `axiBus.r.ready`,
    // this design assumes that the correct target channel (identified by `r.bits.id`) will always assert `ready` immediately.
    io.axiBus.r.ready := io.axiChannel.map(_.r.ready).reduce(_ || _)

    // Write channels
    // ========================
    // Write address arbiter (AW Channel)
    val awArb = Module(new RRArbiter(new AxiWriteAddressBundle(axiConfig), numChannel))
    awArb.io.in.zip(io.axiChannel.map(_.aw)).zipWithIndex.foreach { case ((arbIn, chAw), i) =>
        arbIn <> chAw
        when(chAw.fire) {
            writeChanOwner.valid := true.B
            writeChanOwner.bits := i.U
        }
    }
    io.axiBus.aw.valid := awArb.io.out.valid && ~writeChanOwner.valid
    awArb.io.out.ready := io.axiBus.aw.ready && ~writeChanOwner.valid
    awArb.io.out.bits <> io.axiBus.aw.bits

    // Write data router (W Channel)
    io.axiBus.w.valid := writeChanOwner.valid && Mux1H(
        writeChanOwnerOH,
        io.axiChannel.map(_.w.valid)
    )

    io.axiBus.w.bits := Mux1H(
        writeChanOwnerOH,
        io.axiChannel.map(_.w.bits)
    )

    io.axiChannel.map(_.w).zipWithIndex.foreach { case (chW, i) =>
        chW.ready := writeChanOwner.valid && writeChanOwnerOH(i) && io.axiBus.w.ready
    }

    // Write response router (B Channel)
    io.axiBus.b.ready := false.B
    io.axiChannel.map(_.b).zipWithIndex.foreach { case (chB, i) =>
        chB.bits <> io.axiBus.b.bits
        chB.valid := io.axiBus.b.valid && (io.axiBus.b.bits.id === i.U)
        when(chB.ready && (writeChanOwner.bits === i.U)) {
            io.axiBus.b.ready := true.B
        }
        when(io.axiBus.b.fire) {
            writeChanOwner.valid := false.B
        }
    }
}

class AxiCtrl(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val instrFetch = new IOInterface()(getCacheIoConfig(c.icacheConfig, CacheType.Icache),false)
        val dcacheLoadStore = new IOInterface()(getCacheIoConfig(c.dcacheConfig, CacheType.Dcache),false)
        val dirLoadStore = new IOInterface()(c.lsuIoConfig,false)
        val axi = new AxiInterface(c.axiConfig)
    })

    val instrFetchHandler = Module(new AXIHandler(c.axiConfig, getCacheIoConfig(c.icacheConfig, CacheType.Icache), 0))
    val dcacheLoadStoreHandler = Module(new AXIHandler(c.axiConfig, getCacheIoConfig(c.dcacheConfig, CacheType.Dcache), 1))
    val dirLoadStoreHandler = Module(new AXIHandler(c.axiConfig, c.lsuIoConfig, 2))
    instrFetchHandler.io.req <> io.instrFetch
    dcacheLoadStoreHandler.io.req <> io.dcacheLoadStore
    dirLoadStoreHandler.io.req <> io.dirLoadStore

    val axiRouter = Module(new AxiRouter(c.axiConfig, 3))

    axiRouter.io.axiChannel(0) <> instrFetchHandler.io.axi
    axiRouter.io.axiChannel(1) <> dcacheLoadStoreHandler.io.axi
    axiRouter.io.axiChannel(2) <> dirLoadStoreHandler.io.axi
    io.axi <> axiRouter.io.axiBus
}