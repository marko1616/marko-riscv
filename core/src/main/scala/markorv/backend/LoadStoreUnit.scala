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

/**
  * Load Store Unit (LSU) for the MarkORV Processor.
  *
  * This module handles the execution of memory instructions including Loads, Stores,
  * and Atomic Memory Operations (AMOs). It acts as the interface between the execution
  * pipeline and the L1 Data Cache.
  *
  * @warning **Single-Core Only:**
  *    This implementation is designed strictly for a **Single-Core** system. The atomicity of AMOs
  *    is guaranteed only with respect to the local instruction stream (by stalling the pipeline).
  *    It does **not** assert bus locks or cache line locks (MESI/MOESI exclusive states) visible to other cores.
  *
  * @warning **No DMA/External Consistency:**
  *    The atomic sequences (Read-Modify-Write) are **not atomic** with respect to external Bus Masters (e.g., DMA).
  *    If a DMA controller writes to the target address between the Read and Write phases of an AMO,
  *    the update may be lost or data corruption may occur.
  *
  * @warning **Reservation Monitor Scope:**
  *    The LR/SC reservation monitor is **local only**. It invalidates reservations based on local stores
  *    but does **not** snoop the external bus. Therefore, a write by an external agent (DMA) to a
  *    reserved address will NOT invalidate the reservation, potentially leading to a successful SC
  *    that should have failed.
  *
  * @warning **PMA:**
  *    This module assume a address with atomic attribute will also have read and write attribute
  */
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
        val statNormal, statAmoRead, statAmoWrite = Value
    }
    val state = RegInit(State.statNormal)
    val localLoadReservedValid = RegInit(false.B)
    val localLoadReservedAddr = RegInit(0.U(64.W))

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

    // Default Outputs
    io.cacheReadReq.valid := false.B
    io.cacheReadReq.bits := new CacheReadReq().zero
    io.cacheReadResp.ready := false.B
    io.cacheWriteReq.valid := false.B
    io.cacheWriteReq.bits := new CacheWriteReq()(c.dcacheConfig).zero
    io.cacheWriteResp.ready := false.B

    io.commit.valid := false.B
    io.commit.bits := new LSUCommit().zero
    io.commit.bits.robIndex := params.robIndex

    io.lsuInstr.ready := false.B
    io.outfire := false.B
    opFired := false.B
    loadData := 0.U

    // ==============================================================================
    // State Machine
    // ==============================================================================

    // 1. AMO Read Phase (LR, Swap, Add, etc.)
    when(state === State.statAmoRead) {
        io.cacheReadReq.valid := true.B
        io.cacheReadReq.bits.addr := maskedAddr
        io.cacheReadResp.ready := true.B

        when(io.cacheReadResp.valid) {
            val raw = io.cacheReadResp.bits.data >> dataShiftAmount
            val extended = MuxLookup(size, raw)(Seq(
                0.U -> Mux(sign, raw(7,0).sextu(64), raw(7,0).zextu(64)),
                1.U -> Mux(sign, raw(15,0).sextu(64), raw(15,0).zextu(64)),
                2.U -> Mux(sign, raw(31,0).sextu(64), raw(31,0).zextu(64)),
                3.U -> raw 
            ))
            amoDataReg := extended

            when(opcode === LSUOpcode.lr) {
                opFired := true.B
                localLoadReservedValid := true.B
                localLoadReservedAddr := addr
                io.commit.valid := true.B
                io.commit.bits.data := extended
                state := State.statNormal
            }.otherwise {
                state := State.statAmoWrite
            }
        }
    }

    // 2. AMO Write Phase (SC, Swap, Add, etc.)
    when(state === State.statAmoWrite) {
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

        val writeDataShifted = aluResult.asTypeOf(UInt((c.dcacheConfig.dataBytes * 8).W)) << dataShiftAmount
        val writeMask = MuxLookup(size, "h00".U)(Seq(
            0.U -> "h01".U((c.dcacheConfig.dataBytes * 8).W),
            1.U -> "h03".U((c.dcacheConfig.dataBytes * 8).W),
            2.U -> "h0f".U((c.dcacheConfig.dataBytes * 8).W),
            3.U -> "hff".U((c.dcacheConfig.dataBytes * 8).W)
        )) << byteOffset

        if (true) { // Scope for logic
            when(isSc) {
                // SC Logic
                when(localLoadReservedValid && localLoadReservedAddr === addr) {
                    // Reservation Valid: Perform Write
                    io.cacheWriteReq.valid := true.B
                    io.cacheWriteReq.bits.addr := addr
                    io.cacheWriteReq.bits.data := writeDataShifted
                    io.cacheWriteReq.bits.mask := writeMask
                    io.cacheWriteResp.ready := true.B

                    when(io.cacheWriteResp.valid) {
                        opFired := true.B
                        localLoadReservedValid := false.B
                        localLoadReservedAddr := 0.U
                        io.commit.valid := true.B
                        io.commit.bits.data := AMO_SC_SUCCED
                        state := State.statNormal
                    }
                }.otherwise {
                    // Reservation Invalid: Fail immediately, no write
                    opFired := true.B
                    io.commit.valid := true.B
                    io.commit.bits.data := AMO_SC_FAILED
                    state := State.statNormal
                }
            }.otherwise {
                // Standard RMW AMO Logic
                invalidateReserved(addr) 
                
                io.cacheWriteReq.valid := true.B
                io.cacheWriteReq.bits.addr := addr
                io.cacheWriteReq.bits.data := writeDataShifted
                io.cacheWriteReq.bits.mask := writeMask
                io.cacheWriteResp.ready := true.B

                when(io.cacheWriteResp.valid) {
                    opFired := true.B
                    io.commit.valid := true.B
                    io.commit.bits.data := amoDataReg
                    state := State.statNormal
                }
            }
        }
    }

    // 3. Normal State & Dispatch
    when(io.lsuInstr.valid && validFunct && io.commit.ready && state === State.statNormal) {
        when(LSUOpcode.isamo(opcode)) {
            // Dispatch AMO
            val pmaCheckSucc = pmaChecker.io.attr.a
            when(~pmaCheckSucc) {
                io.commit.valid := true.B
                io.commit.bits.disconType := DisconEventType.instrException
                io.commit.bits.trap := true.B
                io.commit.bits.cause := 7.U // Store/AMO access fault
                io.commit.bits.xtval := addr
                opFired := true.B
            }.elsewhen(~alignedCheckSucc) {
                io.commit.valid := true.B
                io.commit.bits.disconType := DisconEventType.instrException
                io.commit.bits.trap := true.B
                io.commit.bits.cause := 6.U // Store/AMO address misaligned
                io.commit.bits.xtval := addr
                opFired := true.B
            }.otherwise {
                when(opcode === LSUOpcode.sc) {
                    state := State.statAmoWrite
                }.otherwise {
                    state := State.statAmoRead
                }
            }
        }.otherwise {
            // Normal Load/Store
            when(LSUOpcode.isload(opcode)) {
                val pmaCheckSucc = pmaChecker.io.attr.r
                when(~pmaCheckSucc) {
                    io.commit.valid := true.B
                    io.commit.bits.disconType := DisconEventType.instrException
                    io.commit.bits.trap := true.B
                    io.commit.bits.cause := 5.U // Load access fault
                    io.commit.bits.xtval := addr
                    opFired := true.B
                }.elsewhen(~alignedCheckSucc) {
                    printf(cf"MA LD HIT\n")
                    io.commit.valid := true.B
                    io.commit.bits.disconType := DisconEventType.instrException
                    io.commit.bits.trap := true.B
                    io.commit.bits.cause := 4.U // Load address misaligned
                    io.commit.bits.xtval := addr
                    opFired := true.B
                }.otherwise {
                    when(pmaChecker.io.attr.c) { 
                        io.cacheReadReq.valid := true.B
                        io.cacheReadReq.bits.addr := maskedAddr
                        io.cacheReadResp.ready := true.B
                        when(io.cacheReadResp.valid) {
                            opFired := true.B
                            val raw = io.cacheReadResp.bits.data >> dataShiftAmount
                            val extended = MuxLookup(size, raw)(Seq(
                                0.U -> Mux(sign, raw(7,0).sextu(64), raw(7,0).zextu(64)),
                                1.U -> Mux(sign, raw(15,0).sextu(64), raw(15,0).zextu(64)),
                                2.U -> Mux(sign, raw(31,0).sextu(64), raw(31,0).zextu(64)),
                                3.U -> raw 
                            ))
                            loadData := extended
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
                            val extended = MuxLookup(size, raw)(Seq(
                                0.U -> Mux(sign, raw(7,0).sextu(64), raw(7,0).zextu(64)),
                                1.U -> Mux(sign, raw(15,0).sextu(64), raw(15,0).zextu(64)),
                                2.U -> Mux(sign, raw(31,0).sextu(64), raw(31,0).zextu(64)),
                                3.U -> raw // full 64-bit load
                            ))
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
                    io.commit.bits.cause := 7.U // Store/AMO access fault
                    io.commit.bits.xtval := addr
                    opFired := true.B
                }.elsewhen(~alignedCheckSucc) {
                    io.commit.valid := true.B
                    io.commit.bits.disconType := DisconEventType.instrException
                    io.commit.bits.trap := true.B
                    io.commit.bits.cause := 6.U // Store/AMO address misaligned
                    io.commit.bits.xtval := addr
                    opFired := true.B
                }.otherwise {
                    when(pmaChecker.io.attr.c) { 
                        val data = params.source2.asUInt.asTypeOf(UInt((c.dcacheConfig.dataBytes * 8).W)) << dataShiftAmount
                        val mask = MuxLookup(size, "h00".U)(Seq(
                                0.U -> "h01".U((c.dcacheConfig.dataBytes * 8).W),
                                1.U -> "h03".U((c.dcacheConfig.dataBytes * 8).W),
                                2.U -> "h0f".U((c.dcacheConfig.dataBytes * 8).W),
                                3.U -> "hff".U((c.dcacheConfig.dataBytes * 8).W)
                            )) << byteOffset
                        
                        io.cacheWriteReq.valid := true.B
                        io.cacheWriteReq.bits.addr := addr
                        io.cacheWriteReq.bits.data := data
                        io.cacheWriteReq.bits.mask := mask
                        io.cacheWriteResp.ready := true.B
                        
                        when(io.cacheWriteResp.valid) {
                            opFired := true.B
                        }
                    }.otherwise {
                        val data = params.source2.asUInt
                        val addr = params.source1.asUInt
                        invalidateReserved(addr)
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
        io.lsuInstr.ready := io.commit.ready && state === State.statNormal
    }

    when(opFired) {
        io.outfire := true.B
        io.lsuInstr.ready := io.commit.ready
        when(state === State.statNormal) {
            io.commit.valid := true.B
            io.commit.bits.data := loadData
        }
    }
}
