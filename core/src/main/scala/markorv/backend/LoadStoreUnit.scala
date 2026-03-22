package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.backend._
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
        val cacheReadReq = Decoupled(new CacheReadReq())
        val cacheReadResp = Flipped(Decoupled(new CacheReadResp()(c.dcacheConfig)))
        val cacheWriteReq = Decoupled(new CacheWriteReq()(c.dcacheConfig))
        val cacheWriteResp = Flipped(Decoupled(new CacheWriteResp()))
        val cacheCleanReq = Decoupled(new CacheCleanReq())
        val cacheCleanResp = Input(Bool())
        val cacheInvalidateReq = Decoupled(new CacheInvalidateReq())
        val cacheInvalidateResp = Input(Bool())
        val dirLoadStore = new IOInterface()(c.dirLoadStoreIoConfig,true)

        val commit = Decoupled(new LSUCommit)
        val invalidateReserved = Input(Bool())
        val outfire = Output(Bool())
    })

    val pmaChecker = Module(new PMAChecker(c.pma))
    private val dirReadChannel = io.dirLoadStore.read.get
    private val dirWriteChannel = io.dirLoadStore.write.get
    dirReadChannel.params.valid := false.B
    dirReadChannel.params.bits := new ReadParams()(c.dirLoadStoreIoConfig).zero
    dirReadChannel.resp.ready := false.B
    dirWriteChannel.params.valid := false.B
    dirWriteChannel.params.bits := new WriteParams()(c.dirLoadStoreIoConfig).zero
    dirWriteChannel.resp.ready := false.B

    object State extends ChiselEnum {
        val sIdle, sCacheNormWait, sCacheInvalWait, sAmoRead, sAmoWrite = Value
    }
    val state = RegInit(State.sIdle)
    val localLoadReservedValid = RegInit(false.B)
    val localLoadReservedAddr = RegInit(0.U(64.W))
    val cacheCleanPending = RegInit(false.B)

    val (opcode, validFunct) = LSUOpcode.safe(io.lsuInstr.bits.lsuOpcode.funct)
    val size = io.lsuInstr.bits.lsuOpcode.size(1,0)
    val sign = !io.lsuInstr.bits.lsuOpcode.size(2)
    val params = io.lsuInstr.bits.params

    val opFired = Wire(Bool())
    val loadData = Wire(UInt(64.W))
    val amoDataReg = Reg(UInt(64.W))
    val AMO_SC_FAILED = "h0000000000000001".U
    val AMO_SC_SUCCED = "h0000000000000000".U

    val addr = params.source1.asUInt
    val byteOffset = addr(c.dcacheConfig.offsetBits - 1, 0)
    val dataShiftAmount = byteOffset << 3
    val maskedAddr = addr & ((~(0.U(64.W))) << c.dcacheConfig.offsetBits)
    val alignedCheckSucc = (addr & ((1.U << size) - 1.U)) === 0.U
    pmaChecker.io.addr := addr

    def invalidateReserved(writeAddr: UInt) = {
        when(writeAddr === localLoadReservedAddr) {
            localLoadReservedValid := false.B
            localLoadReservedAddr := 0.U
        }
    }

    // Helper: sign-extend raw data based on size
    def extendData(raw: UInt): UInt = {
        MuxLookup(size, raw)(Seq(
            0.U -> Mux(sign, raw(7,0).sextu(64), raw(7,0).zextu(64)),
            1.U -> Mux(sign, raw(15,0).sextu(64), raw(15,0).zextu(64)),
            2.U -> Mux(sign, raw(31,0).sextu(64), raw(31,0).zextu(64)),
            3.U -> raw
        ))
    }

    // Default Outputs
    io.cacheReadReq.valid := false.B
    io.cacheReadReq.bits := new CacheReadReq().zero
    io.cacheReadResp.ready := false.B
    io.cacheWriteReq.valid := false.B
    io.cacheWriteReq.bits := new CacheWriteReq()(c.dcacheConfig).zero
    io.cacheWriteResp.ready := false.B
    io.cacheCleanReq.valid := false.B
    io.cacheCleanReq.bits := new CacheCleanReq().zero
    io.cacheInvalidateReq.valid := false.B
    io.cacheInvalidateReq.bits := new CacheInvalidateReq().zero

    io.commit.valid := false.B
    io.commit.bits := new LSUCommit().zero
    io.commit.bits.robIndex := params.robIndex

    io.lsuInstr.ready := false.B
    io.outfire := false.B
    opFired := false.B
    loadData := 0.U

    when(io.invalidateReserved) {
        localLoadReservedValid := false.B
    }

    when(io.cacheCleanResp) {
        cacheCleanPending := false.B
    }

    // ==============================================================================
    // State Machine
    // ==============================================================================
    when(state === State.sCacheNormWait) {
        when(LSUOpcode.isamo(opcode)) {
            when(~cacheCleanPending || io.cacheCleanResp) {
                io.cacheInvalidateReq.valid := true.B
                io.cacheInvalidateReq.bits.addr := maskedAddr
                when(io.cacheInvalidateReq.ready) {
                    state := State.sCacheInvalWait
                }
            }
        }.elsewhen(LSUOpcode.isload(opcode)) {
            io.cacheReadResp.ready := true.B
            when(io.cacheReadResp.valid) {
                opFired := true.B
                val raw = io.cacheReadResp.bits.data >> dataShiftAmount
                val extended = extendData(raw)
                loadData := extended
                state := State.sIdle
            }
        }.otherwise {
            io.cacheWriteResp.ready := true.B
            when(io.cacheWriteResp.valid) {
                opFired := true.B
                state := State.sIdle
            }
        }
    }

    when(state === State.sCacheInvalWait && io.cacheInvalidateResp) {
        when(opcode === LSUOpcode.sc) {
            state := State.sAmoWrite
        }.otherwise {
            state := State.sAmoRead
        }
    }

    when(state === State.sAmoRead) {
        dirReadChannel.params.valid := true.B
        dirReadChannel.params.bits.size := size
        dirReadChannel.params.bits.addr := addr
        dirReadChannel.resp.ready := true.B

        when(dirReadChannel.resp.valid) {
            val raw = dirReadChannel.resp.bits.data
            val extended = extendData(raw)
            amoDataReg := extended

            when(opcode === LSUOpcode.lr) {
                opFired := true.B
                localLoadReservedValid := true.B
                localLoadReservedAddr := addr
                loadData := extended
                state := State.sIdle
            }.otherwise {
                state := State.sAmoWrite
            }
        }
    }

    when(state === State.sAmoWrite) {
        val source2 = Wire(UInt(64.W))
        source2 := MuxLookup(size, params.source2)(Seq(
            0.U -> params.source2(7,0).sextu(64),
            1.U -> params.source2(15,0).sextu(64),
            2.U -> params.source2(31,0).sextu(64)
        ))

        val isSc = opcode === LSUOpcode.sc

        val aluResult = MuxLookup(opcode, 0.U)(Seq(
            LSUOpcode.sc      -> source2,
            LSUOpcode.amoswap -> source2,
            LSUOpcode.amoadd  -> (amoDataReg + source2),
            LSUOpcode.amoxor  -> (amoDataReg ^ source2),
            LSUOpcode.amoor   -> (amoDataReg | source2),
            LSUOpcode.amoand  -> (amoDataReg & source2),
            LSUOpcode.amomin  -> Mux(amoDataReg.asSInt < source2.asSInt, amoDataReg, source2),
            LSUOpcode.amomax  -> Mux(amoDataReg.asSInt > source2.asSInt, amoDataReg, source2),
            LSUOpcode.amominu -> Mux(amoDataReg < source2, amoDataReg, source2),
            LSUOpcode.amomaxu -> Mux(amoDataReg > source2, amoDataReg, source2)
        ))

        // Cache-path shifted data & mask (only meaningful for cacheable writes)
        val writeDataShifted = aluResult.asTypeOf(UInt((c.dcacheConfig.dataBytes * 8).W)) << dataShiftAmount
        val writeMask = MuxLookup(size, "h00".U)(Seq(
            0.U -> "h01".U((c.dcacheConfig.dataBytes * 8).W),
            1.U -> "h03".U((c.dcacheConfig.dataBytes * 8).W),
            2.U -> "h0f".U((c.dcacheConfig.dataBytes * 8).W),
            3.U -> "hff".U((c.dcacheConfig.dataBytes * 8).W)
        )) << byteOffset

        when(isSc) {
            // SC Logic
            when(localLoadReservedValid && localLoadReservedAddr === addr) {
                dirWriteChannel.params.valid := true.B
                dirWriteChannel.params.bits.size := size
                dirWriteChannel.params.bits.addr := addr
                dirWriteChannel.params.bits.data := aluResult
                dirWriteChannel.resp.ready := true.B

                when(dirWriteChannel.resp.valid) {
                    opFired := true.B
                    localLoadReservedValid := false.B
                    localLoadReservedAddr := 0.U
                    loadData := AMO_SC_SUCCED
                    state := State.sIdle
                }
            }.otherwise {
                // Reservation Invalid: Fail immediately, no write
                opFired := true.B
                loadData := AMO_SC_FAILED
                state := State.sIdle
            }
        }.otherwise {
            // Standard RMW AMO Logic
            invalidateReserved(addr)
            dirWriteChannel.params.valid := true.B
            dirWriteChannel.params.bits.size := size
            dirWriteChannel.params.bits.addr := addr
            dirWriteChannel.params.bits.data := aluResult
            dirWriteChannel.resp.ready := true.B

            when(dirWriteChannel.resp.valid) {
                opFired := true.B
                loadData :=amoDataReg
                state := State.sIdle
            }
        }
    }

    when(io.lsuInstr.valid && validFunct && io.commit.ready && state === State.sIdle) {
        when(LSUOpcode.isamo(opcode)) {
            // Dispatch AMO
            val pmaCheckSucc = pmaChecker.io.attr.a
            when(~pmaCheckSucc) {
                io.commit.valid := true.B
                io.commit.bits.disconType := DisconEventType.instrException
                io.commit.bits.trap := true.B
                io.commit.bits.cause := 7.U
                io.commit.bits.xtval := addr
                opFired := true.B
            }.elsewhen(~alignedCheckSucc) {
                io.commit.valid := true.B
                io.commit.bits.disconType := DisconEventType.instrException
                io.commit.bits.trap := true.B
                io.commit.bits.cause := 6.U
                io.commit.bits.xtval := addr
                opFired := true.B
            }.otherwise {
                when(pmaChecker.io.attr.c) {
                    io.cacheCleanReq.valid := true.B
                    io.cacheCleanReq.bits.addr := maskedAddr
                    cacheCleanPending := true.B
                    when(io.cacheCleanReq.ready) {
                        state := State.sCacheNormWait
                    }
                }.otherwise {
                    when(opcode === LSUOpcode.sc) {
                        state := State.sAmoWrite
                    }.otherwise {
                        state := State.sAmoRead
                    }
                }
            }
        }.otherwise {
            // Idle Load/Store
            when(LSUOpcode.isload(opcode)) {
                val pmaCheckSucc = pmaChecker.io.attr.r
                when(~pmaCheckSucc) {
                    io.commit.valid := true.B
                    io.commit.bits.disconType := DisconEventType.instrException
                    io.commit.bits.trap := true.B
                    io.commit.bits.cause := 5.U
                    io.commit.bits.xtval := addr
                    opFired := true.B
                }.elsewhen(~alignedCheckSucc) {
                    io.commit.valid := true.B
                    io.commit.bits.disconType := DisconEventType.instrException
                    io.commit.bits.trap := true.B
                    io.commit.bits.cause := 4.U
                    io.commit.bits.xtval := addr
                    opFired := true.B
                }.otherwise {
                    when(pmaChecker.io.attr.c) {
                        io.cacheReadReq.valid := true.B
                        io.cacheReadReq.bits.addr := maskedAddr
                        when(io.cacheReadReq.ready) {
                            state := State.sCacheNormWait
                        }
                    }.otherwise {
                        val addr = params.source1.asUInt
                        dirReadChannel.params.valid := true.B
                        dirReadChannel.params.bits.size := size
                        dirReadChannel.params.bits.addr := addr
                        dirReadChannel.resp.ready := true.B
                        when(dirReadChannel.resp.valid) {
                            opFired := true.B
                            val raw = dirReadChannel.resp.bits.data
                            val extended = extendData(raw)
                            loadData := extended
                        }
                    }
                }
            }.otherwise {
                // Normal Store
                invalidateReserved(addr)
                val pmaCheckSucc = pmaChecker.io.attr.w
                when(~pmaCheckSucc) {
                    io.commit.valid := true.B
                    io.commit.bits.disconType := DisconEventType.instrException
                    io.commit.bits.trap := true.B
                    io.commit.bits.cause := 7.U
                    io.commit.bits.xtval := addr
                    opFired := true.B
                }.elsewhen(~alignedCheckSucc) {
                    io.commit.valid := true.B
                    io.commit.bits.disconType := DisconEventType.instrException
                    io.commit.bits.trap := true.B
                    io.commit.bits.cause := 6.U
                    io.commit.bits.xtval := addr
                    opFired := true.B
                }.otherwise {
                    when(pmaChecker.io.attr.c) {
                        io.cacheWriteReq.valid := true.B
                        io.cacheWriteReq.bits.addr := maskedAddr
                        io.cacheWriteReq.bits.data := (params.source2.asUInt << dataShiftAmount).asTypeOf(io.cacheWriteReq.bits.data)
                        io.cacheWriteReq.bits.mask := MuxLookup(size, 0.U)(Seq(
                                0.U -> "h01".U((c.dcacheConfig.dataBytes * 8).W),
                                1.U -> "h03".U((c.dcacheConfig.dataBytes * 8).W),
                                2.U -> "h0f".U((c.dcacheConfig.dataBytes * 8).W),
                                3.U -> "hff".U((c.dcacheConfig.dataBytes * 8).W)
                            )) << byteOffset
                        when(io.cacheWriteReq.ready) {
                            state := State.sCacheNormWait
                        }
                    }.otherwise {
                        val data = params.source2.asUInt
                        val addr = params.source1.asUInt
                        dirWriteChannel.params.valid := true.B
                        dirWriteChannel.params.bits.size := size
                        dirWriteChannel.params.bits.addr := addr
                        dirWriteChannel.params.bits.data := data
                        when(dirWriteChannel.resp.valid) {
                            opFired := true.B
                        }
                    }
                }
            }
        }
    }

    // Handshaking Logic
    when(!io.lsuInstr.valid) {
        io.lsuInstr.ready := io.commit.ready && state === State.sIdle
    }

    when(opFired) {
        io.outfire := true.B
        io.lsuInstr.ready := io.commit.ready
        io.commit.valid := true.B
        io.commit.bits.data := loadData
    }
}
