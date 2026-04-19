package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.backend  ._
import markorv.bus._
import markorv.cache._
import markorv.frontend.DecodedParams
import markorv.manage.RegisterCommit
import markorv.manage.EXUParams
import markorv.manage.DisconEventType

class LoadStoreUnit(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val lsuInstr = Flipped(Decoupled(new Bundle {
            val lsuOpcode = new LoadStoreOpcode
            val params = new EXUParams
        }))
        val cacheReadReq = Decoupled(new DCacheReadReq())
        val cacheReadResp = Flipped(Valid(new DCacheReadResp()(c.dcacheConfig)))
        val cacheWriteReq = Decoupled(new DCacheWriteReq()(c.dcacheConfig))
        val cacheWriteResp = Flipped(Valid(new DCacheWriteResp()))
        val cacheCleanReq = Decoupled(new DCacheCleanReq())
        val cacheCleanResp = Flipped(Valid(new DCacheCleanResp))
        val cacheInvalidateReq = Decoupled(new DCacheInvalidateReq())
        val cacheInvalidateResp = Flipped(Valid(new DCacheInvalidateResp))
        val cacheAmoFlushReq = Decoupled(new DCacheAmoFlushReq())
        val cacheAmoFlushResp = Flipped(Valid(new DCacheAmoFlushResp))
        val paddr = Input(UInt(64.W))
        val dirLoadStore = new IOInterface()(c.lsuIoConfig, true)

        val commit = Decoupled(new LSUCommit)
        val invalidateReserved = Input(Bool())
        val outfire = Output(Bool())
    })

    private val dirReadChannel = io.dirLoadStore.read.get
    private val dirWriteChannel = io.dirLoadStore.write.get

    object State extends ChiselEnum {
        val sIdle, sCacheReadWait, sCacheWriteWait, sBypassRead, sBypassWrite, sAmoFlushWait, sAmoRead, sAmoWrite = Value
    }

    val state = RegInit(State.sIdle)
    val localLoadReservedValid = RegInit(false.B)
    val localLoadReservedPa = RegInit(0.U(64.W))
    val transPaAddrReg = RegInit(0.U(64.W))
    val amoDataReg = Reg(UInt(64.W))

    // Decode
    val (opcode, validFunct) = LSUOpcode.safe(io.lsuInstr.bits.lsuOpcode.funct)
    val size = io.lsuInstr.bits.lsuOpcode.size(1, 0)
    val sign = !io.lsuInstr.bits.lsuOpcode.size(2)
    val params = io.lsuInstr.bits.params

    val isAmo = LSUOpcode.isamo(opcode)
    val isLoad = LSUOpcode.isload(opcode)
    val isStore = !isAmo && !isLoad
    val isSc = opcode === LSUOpcode.sc
    val isLr = opcode === LSUOpcode.lr

    // Keep full VA so returned PA keeps the byte offset.
    val vaAddr = params.source1.asUInt
    val alignedCheckSucc = (vaAddr & ((1.U << size) - 1.U)) === 0.U
    val alignExcCause = Mux(isLoad, 4.U, 6.U)

    val AMO_SC_FAILED = "h0000000000000001".U
    val AMO_SC_SUCCEEDED = "h0000000000000000".U

    def extendData(raw: UInt): UInt = {
        MuxLookup(size, raw)(Seq(
            0.U -> Mux(sign, raw(7, 0).sextu(64), raw(7, 0).zextu(64)),
            1.U -> Mux(sign, raw(15, 0).sextu(64), raw(15, 0).zextu(64)),
            2.U -> Mux(sign, raw(31, 0).sextu(64), raw(31, 0).zextu(64)),
            3.U -> raw
        ))
    }

    def isLoadAccessFault(code: DCacheCode.Type): Bool = {
        code === DCacheCode.pmaMmuWalkErr || code === DCacheCode.pmaLoadErr
    }

    def isStoreAccessFault(code: DCacheCode.Type): Bool = {
        code === DCacheCode.pmaMmuWalkErr || code === DCacheCode.pmaLoadErr || code === DCacheCode.pmaStorErr
    }

    val cacheWriteData = Wire(UInt(64.W))
    cacheWriteData := params.source2.asUInt

    val cacheWriteMaskBase = MuxLookup(size, 0.U(8.W))(Seq(
        0.U -> "h01".U(8.W),
        1.U -> "h03".U(8.W),
        2.U -> "h0f".U(8.W),
        3.U -> "hff".U(8.W)
    ))
    val cacheWriteMask = Wire(UInt(8.W))
    cacheWriteMask := cacheWriteMaskBase

    val source2Extended = MuxLookup(size, params.source2.asUInt)(Seq(
        0.U -> params.source2(7, 0).sextu(64),
        1.U -> params.source2(15, 0).sextu(64),
        2.U -> params.source2(31, 0).sextu(64)
    ))

    val amoAluResult = MuxLookup(opcode, 0.U)(Seq(
        LSUOpcode.sc      -> source2Extended,
        LSUOpcode.amoswap -> source2Extended,
        LSUOpcode.amoadd  -> (amoDataReg + source2Extended),
        LSUOpcode.amoxor  -> (amoDataReg ^ source2Extended),
        LSUOpcode.amoor   -> (amoDataReg | source2Extended),
        LSUOpcode.amoand  -> (amoDataReg & source2Extended),
        LSUOpcode.amomin  -> Mux(amoDataReg.asSInt < source2Extended.asSInt, amoDataReg, source2Extended),
        LSUOpcode.amomax  -> Mux(amoDataReg.asSInt > source2Extended.asSInt, amoDataReg, source2Extended),
        LSUOpcode.amominu -> Mux(amoDataReg < source2Extended, amoDataReg, source2Extended),
        LSUOpcode.amomaxu -> Mux(amoDataReg > source2Extended, amoDataReg, source2Extended)
    ))

    // Action wires
    val opFired = WireDefault(false.B)
    val loadData = WireDefault(0.U(64.W))

    // Reservation update
    val setReservedValid = WireDefault(false.B)
    val newReservedValidVal = WireDefault(false.B)
    val newReservedPaVal = WireDefault(0.U(64.W))

    // Reservation invalidation
    val doInvalidateReserved = WireDefault(false.B)
    val invalidateReservedPa = WireDefault(0.U(64.W))

    // AMO data capture
    val setAmoData = WireDefault(false.B)
    val newAmoDataVal = WireDefault(0.U(64.W))

    def raiseException(cause: UInt): Unit = {
        opFired := true.B
        io.commit.valid := true.B
        io.commit.bits.discon := true.B
        io.commit.bits.disconType := DisconEventType.instrException
        io.commit.bits.eventPc := params.pc
        io.commit.bits.xtval := vaAddr
        io.commit.bits.cause := cause
    }

    def finishOp(data: UInt): Unit = {
        opFired := true.B
        loadData := data
    }

    dirReadChannel.params.valid := false.B
    dirReadChannel.params.bits := new ReadParams()(c.lsuIoConfig).zero
    dirWriteChannel.params.valid := false.B
    dirWriteChannel.params.bits := new WriteParams()(c.lsuIoConfig).zero

    io.cacheReadReq.valid := false.B
    io.cacheReadReq.bits := new DCacheReadReq().zero
    io.cacheWriteReq.valid := false.B
    io.cacheWriteReq.bits := new DCacheWriteReq()(c.dcacheConfig).zero
    io.cacheCleanReq.valid := false.B
    io.cacheCleanReq.bits := new DCacheCleanReq().zero
    io.cacheInvalidateReq.valid := false.B
    io.cacheInvalidateReq.bits := new DCacheInvalidateReq().zero
    io.cacheAmoFlushReq.valid := false.B
    io.cacheAmoFlushReq.bits := new DCacheAmoFlushReq().zero

    io.commit.valid := false.B
    io.commit.bits := new LSUCommit().zero
    io.commit.bits.robIndex := params.robIndex

    io.lsuInstr.ready := false.B
    io.outfire := false.B

    switch(state) {
        is(State.sIdle) {
            when(io.lsuInstr.valid && validFunct && io.commit.ready) {
                when(!alignedCheckSucc) {
                    raiseException(alignExcCause)
                }.elsewhen(isAmo) {
                    io.cacheAmoFlushReq.valid := true.B
                    io.cacheAmoFlushReq.bits.vaddr := vaAddr
                    io.cacheAmoFlushReq.bits.readLike := isLr
                    when(io.cacheAmoFlushReq.ready) {
                        state := State.sAmoFlushWait
                    }
                }.elsewhen(isLoad) {
                    io.cacheReadReq.valid := true.B
                    io.cacheReadReq.bits.vaddr := vaAddr
                    when(io.cacheReadReq.ready) {
                        state := State.sCacheReadWait
                    }
                }.otherwise {
                    io.cacheWriteReq.valid := true.B
                    io.cacheWriteReq.bits.vaddr := vaAddr
                    io.cacheWriteReq.bits.data := cacheWriteData
                    io.cacheWriteReq.bits.mask := cacheWriteMask
                    when(io.cacheWriteReq.ready) {
                        state := State.sCacheWriteWait
                    }
                }
            }
        }

        is(State.sCacheReadWait) {
            when(io.cacheReadResp.valid) {
                val code = io.cacheReadResp.bits.code
                when(code.isOk()) {
                    finishOp(extendData(io.cacheReadResp.bits.data))
                    state := State.sIdle
                }.elsewhen(code === DCacheCode.pmaCacheErr) {
                    transPaAddrReg := io.paddr
                    state := State.sBypassRead
                }.elsewhen(code === DCacheCode.pageLoadErr) {
                    raiseException(13.U)
                    state := State.sIdle
                }.elsewhen(isLoadAccessFault(code)) {
                    raiseException(5.U)
                    state := State.sIdle
                }.otherwise {
                    raiseException(5.U)
                    state := State.sIdle
                }
            }
        }

        is(State.sCacheWriteWait) {
            when(io.cacheWriteResp.valid) {
                val code = io.cacheWriteResp.bits.code
                when(code.isOk()) {
                    finishOp(0.U)
                    doInvalidateReserved := true.B
                    invalidateReservedPa := io.paddr
                    state := State.sIdle
                }.elsewhen(code === DCacheCode.pmaCacheErr) {
                    transPaAddrReg := io.paddr
                    state := State.sBypassWrite
                }.elsewhen(code === DCacheCode.pageStorErr) {
                    raiseException(15.U)
                    state := State.sIdle
                }.elsewhen(code === DCacheCode.pageLoadErr) {
                    raiseException(13.U)
                    state := State.sIdle
                }.elsewhen(isStoreAccessFault(code)) {
                    raiseException(7.U)
                    state := State.sIdle
                }.otherwise {
                    raiseException(7.U)
                    state := State.sIdle
                }
            }
        }

        is(State.sBypassRead) {
            dirReadChannel.params.valid := true.B
            dirReadChannel.params.bits.size := size
            dirReadChannel.params.bits.addr := transPaAddrReg

            when(dirReadChannel.resp.valid) {
                finishOp(extendData(dirReadChannel.resp.bits.data))
                state := State.sIdle
            }
        }

        is(State.sBypassWrite) {
            dirWriteChannel.params.valid := true.B
            dirWriteChannel.params.bits.size := size
            dirWriteChannel.params.bits.addr := transPaAddrReg
            dirWriteChannel.params.bits.data := params.source2.asUInt

            when(dirWriteChannel.resp.valid) {
                finishOp(0.U)
                doInvalidateReserved := true.B
                invalidateReservedPa := transPaAddrReg
                state := State.sIdle
            }
        }

        is(State.sAmoFlushWait) {
            when(io.cacheAmoFlushResp.valid) {
                val code = io.cacheAmoFlushResp.bits.code

                when(code.isOk()) {
                    transPaAddrReg := io.paddr
                    when(isSc) {
                        when(localLoadReservedValid && localLoadReservedPa === io.paddr) {
                            state := State.sAmoWrite
                        }.otherwise {
                            finishOp(AMO_SC_FAILED)
                            state := State.sIdle
                        }
                    }.otherwise {
                        state := State.sAmoRead
                    }
                }.elsewhen(code === DCacheCode.pageStorErr) {
                    raiseException(15.U)
                    state := State.sIdle
                }.elsewhen(code === DCacheCode.pageLoadErr) {
                    raiseException(13.U)
                    state := State.sIdle
                }.elsewhen(isStoreAccessFault(code)) {
                    raiseException(7.U)
                    state := State.sIdle
                }.otherwise {
                    raiseException(7.U)
                    state := State.sIdle
                }
            }
        }

        is(State.sAmoRead) {
            dirReadChannel.params.valid := true.B
            dirReadChannel.params.bits.size := size
            dirReadChannel.params.bits.addr := transPaAddrReg

            when(dirReadChannel.resp.valid) {
                val readData = extendData(dirReadChannel.resp.bits.data)
                setAmoData := true.B
                newAmoDataVal := readData

                when(isLr) {
                    finishOp(readData)
                    setReservedValid := true.B
                    newReservedValidVal := true.B
                    newReservedPaVal := transPaAddrReg
                    state := State.sIdle
                }.otherwise {
                    state := State.sAmoWrite
                }
            }
        }

        is(State.sAmoWrite) {
            dirWriteChannel.params.valid := true.B
            dirWriteChannel.params.bits.size := size
            dirWriteChannel.params.bits.addr := transPaAddrReg
            dirWriteChannel.params.bits.data := amoAluResult

            when(dirWriteChannel.resp.valid) {
                when(isSc) {
                    finishOp(AMO_SC_SUCCEEDED)
                    setReservedValid := true.B
                    newReservedValidVal := false.B
                    newReservedPaVal := 0.U
                }.otherwise {
                    finishOp(amoDataReg)
                    doInvalidateReserved := true.B
                    invalidateReservedPa := transPaAddrReg
                }
                state := State.sIdle
            }
        }
    }

    when(!io.lsuInstr.valid) {
        io.lsuInstr.ready := io.commit.ready && state === State.sIdle
    }

    when(opFired) {
        io.outfire := true.B
        io.lsuInstr.ready := io.commit.ready
        io.commit.valid := true.B
        io.commit.bits.data := loadData
    }

    when(io.invalidateReserved) {
        localLoadReservedValid := false.B
        localLoadReservedPa := 0.U
    }
    when(doInvalidateReserved && invalidateReservedPa === localLoadReservedPa) {
        localLoadReservedValid := false.B
        localLoadReservedPa := 0.U
    }
    when(setReservedValid) {
        localLoadReservedValid := newReservedValidVal
        localLoadReservedPa := newReservedPaVal
    }

    when(setAmoData) {
        amoDataReg := newAmoDataVal
    }
}