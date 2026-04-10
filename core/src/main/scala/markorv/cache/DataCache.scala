package markorv.cache

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.utils.ConfigUtils._
import markorv.config._
import markorv.bus._
import markorv.cache._

class DataCache(implicit val c: CacheConfig) extends Module {
    // Transaction priority:
    //   read > write > clean > invalidate > invalidateAll > cleanAll
    val io = IO(new Bundle {
        val cacheInterface = new DcacheInterface
        val ioInterface = new IOInterface()(getCacheIoConfig(c, CacheType.Dcache), true)

        val invalidateAll = Input(Bool())
        val invalidateAllOutfire = Output(Bool())
        val cleanAll = Input(Bool())
        val cleanAllOutfire = Output(Bool())
    })

    // TODO: handle bus write-back error more explicitly.

    object State extends ChiselEnum {
        val sIdle, sRead, sWrite, sReplace, sVictimWriteBack, sCleanWriteBack, sInvalidateAll, sCleanAll = Value
    }

    object TransactionType extends ChiselEnum {
        val read, write, clean, invalidate = Value
    }

    private def mergeWriteData(oldData: UInt, newData: UInt, mask: UInt): UInt = {
        val mergedBytes = Wire(Vec(c.dataBytes, UInt(8.W)))
        for (i <- 0 until c.dataBytes) {
            val msb = (i + 1) * 8 - 1
            val lsb = i * 8
            mergedBytes(i) := Mux(mask(i), newData(msb, lsb), oldData(msb, lsb))
        }
        mergedBytes.asUInt
    }

    private def isLastWay(way: UInt): Bool = {
        if (c.wayNum > 1) {
            way === (c.wayNum - 1).U
        } else {
            true.B
        }
    }

    private def nextWay(way: UInt): UInt = {
        if (c.wayNum > 1) {
            way + 1.U
        } else {
            0.U(c.wayBits.W)
        }
    }

    // SRAM arrays

    val tagVArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheTagValid))
    val dataArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheData))
    val dirtyArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheDirty))

    // current transaction

    val transactionType      = RegInit(TransactionType.read)
    val transactionAddr      = RegInit(0.U(64.W))
    val transactionWriteData = RegInit(0.U((8 * c.dataBytes).W))
    val transactionWriteMask = RegInit(0.U(c.dataBytes.W))
    val transactionWriteCode = RegInit(CacheCode.CacheHitOk)
    val transactionHitWay    = RegInit(0.U(c.wayBits.W))
    val transactionReadData  = RegInit(0.U((8 * c.dataBytes).W))

    // replacement / working-set registers

    val victimWayPtr = RegInit(0.U(c.wayBits.W))

    val workSetTagVReg = RegInit(VecInit(Seq.fill(c.wayNum)(0.U.asTypeOf(new CacheTagValid))))
    val workSetDataReg = RegInit(VecInit(Seq.fill(c.wayNum)(0.U.asTypeOf(new CacheData))))
    val workSetDirtyReg = RegInit(VecInit(Seq.fill(c.wayNum)(0.U.asTypeOf(new CacheDirty))))

    // clean-writeback context

    val cleanWriteBackIsCleanAll = RegInit(false.B)
    val cleanWriteBackSet = RegInit(0.U(c.setBits.W))
    val cleanWriteBackWay = RegInit(0.U(c.wayBits.W))
    val cleanWriteBackAddr = RegInit(0.U(64.W))
    val cleanWriteBackData = RegInit(0.U((8 * c.dataBytes).W))

    // global invalidate / clean-all state

    val state = RegInit(State.sIdle)
    val invalidateAllSetIdx = RegInit(0.U(c.setBits.W))

    val cleanAllSetIdx = RegInit(0.U(c.setBits.W))
    val cleanAllWayPtr = RegInit(0.U(c.wayBits.W))
    val cleanAllSetLoaded = RegInit(false.B)

    val cleanAllSetTagVReg = RegInit(VecInit(Seq.fill(c.wayNum)(0.U.asTypeOf(new CacheTagValid))))
    val cleanAllSetDataReg = RegInit(VecInit(Seq.fill(c.wayNum)(0.U.asTypeOf(new CacheData))))
    val cleanAllSetDirtyReg = RegInit(VecInit(Seq.fill(c.wayNum)(0.U.asTypeOf(new CacheDirty))))

    // SRAM read wires

    val sramReadTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sramReadData = Wire(Vec(c.wayNum, new CacheData))
    val sramReadDirty = Wire(Vec(c.wayNum, new CacheDirty))

    val reqReadAddr = io.cacheInterface.readReq.bits.addr
    val reqReadSet  = reqReadAddr(c.setEnd, c.setStart)

    val reqWriteAddr = io.cacheInterface.writeReq.bits.addr
    val reqWriteSet  = reqWriteAddr(c.setEnd, c.setStart)

    val reqCleanAddr = io.cacheInterface.cleanReq.bits.addr
    val reqCleanSet  = reqCleanAddr(c.setEnd, c.setStart)

    val reqInvalidateAddr = io.cacheInterface.invalidateReq.bits.addr
    val reqInvalidateSet  = reqInvalidateAddr(c.setEnd, c.setStart)

    val transactionSet = transactionAddr(c.setEnd, c.setStart)
    val transactionTag = transactionAddr(c.tagEnd, c.tagStart)

    // state local actions

    // sIdle -> latch transaction / start SRAM read
    val sIdleSetTransactionValid = WireDefault(false.B)
    val sIdleSetTransactionType = WireDefault(TransactionType.read)
    val sIdleSetTransactionAddr = WireDefault(0.U(64.W))
    val sIdleSetTransactionWriteData = WireDefault(0.U((8 * c.dataBytes).W))
    val sIdleSetTransactionWriteMask = WireDefault(0.U(c.dataBytes.W))

    val sIdleReadSramValid = WireDefault(false.B)
    val sIdleReadSramSet = WireDefault(0.U(c.setBits.W))

    // sRead -> latch next transaction / start next SRAM read
    val sReadSetTransactionValid = WireDefault(false.B)
    val sReadSetTransactionType = WireDefault(TransactionType.read)
    val sReadSetTransactionAddr = WireDefault(0.U(64.W))
    val sReadSetTransactionWriteData = WireDefault(0.U((8 * c.dataBytes).W))
    val sReadSetTransactionWriteMask = WireDefault(0.U(c.dataBytes.W))

    val sReadReadSramValid = WireDefault(false.B)
    val sReadReadSramSet = WireDefault(0.U(c.setBits.W))

    // sRead -> snapshot current set for later states
    val sReadSetWorkSetValid = WireDefault(false.B)
    val sReadSetWorkSetTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReadSetWorkSetData = Wire(Vec(c.wayNum, new CacheData))
    val sReadSetWorkSetDirty = Wire(Vec(c.wayNum, new CacheDirty))
    sReadSetWorkSetTagV := sramReadTagV
    sReadSetWorkSetData := sramReadData
    sReadSetWorkSetDirty := sramReadDirty

    // sRead -> invalidate one line in place
    val sReadInvalidateWriteValid = WireDefault(false.B)
    val sReadInvalidateWriteSet = WireDefault(transactionSet)
    val sReadInvalidateWriteTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReadInvalidateWriteDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sReadInvalidateWriteTagV(i) := sramReadTagV(i)
        sReadInvalidateWriteDirty(i) := sramReadDirty(i)
    }

    // sRead / sCleanAll -> prepare clean write-back context
    val sReadSetCleanWriteBackValid = WireDefault(false.B)
    val sReadSetCleanWriteBackIsCleanAll = WireDefault(false.B)
    val sReadSetCleanWriteBackSet = WireDefault(0.U(c.setBits.W))
    val sReadSetCleanWriteBackWay = WireDefault(0.U(c.wayBits.W))
    val sReadSetCleanWriteBackAddr = WireDefault(0.U(64.W))
    val sReadSetCleanWriteBackData = WireDefault(0.U((8 * c.dataBytes).W))

    val sCleanAllSetCleanWriteBackValid = WireDefault(false.B)
    val sCleanAllSetCleanWriteBackIsCleanAll = WireDefault(true.B)
    val sCleanAllSetCleanWriteBackSet = WireDefault(0.U(c.setBits.W))
    val sCleanAllSetCleanWriteBackWay = WireDefault(0.U(c.wayBits.W))
    val sCleanAllSetCleanWriteBackAddr = WireDefault(0.U(64.W))
    val sCleanAllSetCleanWriteBackData = WireDefault(0.U((8 * c.dataBytes).W))

    // sWrite -> merge a store into the working-set snapshot
    val sWriteWriteValid = WireDefault(false.B)
    val sWriteWriteSet = WireDefault(transactionSet)
    val sWriteWriteTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sWriteWriteData = Wire(Vec(c.wayNum, new CacheData))
    val sWriteWriteDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sWriteWriteTagV(i) := workSetTagVReg(i)
        sWriteWriteData(i) := workSetDataReg(i)
        sWriteWriteDirty(i) := workSetDirtyReg(i)
    }

    // sReplace -> write refill result back to SRAM
    val sReplaceWriteValid = WireDefault(false.B)
    val sReplaceWriteSet = WireDefault(transactionSet)
    val sReplaceWriteTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReplaceWriteData = Wire(Vec(c.wayNum, new CacheData))
    val sReplaceWriteDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sReplaceWriteTagV(i) := workSetTagVReg(i)
        sReplaceWriteData(i) := workSetDataReg(i)
        sReplaceWriteDirty(i) := workSetDirtyReg(i)
    }

    val sReplaceSetWorkSetValid = WireDefault(false.B)
    val sReplaceSetWorkSetTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReplaceSetWorkSetData = Wire(Vec(c.wayNum, new CacheData))
    val sReplaceSetWorkSetDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sReplaceSetWorkSetTagV(i) := sReplaceWriteTagV(i)
        sReplaceSetWorkSetData(i) := sReplaceWriteData(i)
        sReplaceSetWorkSetDirty(i) := sReplaceWriteDirty(i)
    }

    // sCleanWriteBack -> clear dirty bit after line write-back
    val sCleanWriteBackDirtyWriteValid = WireDefault(false.B)
    val sCleanWriteBackDirtyWriteSet = WireDefault(cleanWriteBackSet)
    val sCleanWriteBackDirtyWrite = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sCleanWriteBackDirtyWrite(i) := 0.U.asTypeOf(new CacheDirty)
    }

    val sCleanWriteBackSetCleanAllDirtyValid = WireDefault(false.B)
    val sCleanWriteBackSetCleanAllDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sCleanWriteBackSetCleanAllDirty(i) := cleanAllSetDirtyReg(i)
    }

    // sInvalidateAll -> clear valid + dirty for the whole set
    val sInvalidateAllWriteValid = WireDefault(false.B)
    val sInvalidateAllWriteSet = WireDefault(invalidateAllSetIdx)
    val sInvalidateAllWriteTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sInvalidateAllWriteDirty = Wire(Vec(c.wayNum, new CacheDirty))
    sInvalidateAllWriteTagV := VecInit(Seq.fill(c.wayNum)(0.U.asTypeOf(new CacheTagValid)))
    sInvalidateAllWriteDirty := VecInit(Seq.fill(c.wayNum)(0.U.asTypeOf(new CacheDirty)))

    // sCleanAll -> snapshot one whole set, and optionally start reading the next set
    val sCleanAllSetSnapshotValid = WireDefault(false.B)
    val sCleanAllSetSnapshotTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sCleanAllSetSnapshotData = Wire(Vec(c.wayNum, new CacheData))
    val sCleanAllSetSnapshotDirty = Wire(Vec(c.wayNum, new CacheDirty))
    sCleanAllSetSnapshotTagV := sramReadTagV
    sCleanAllSetSnapshotData := sramReadData
    sCleanAllSetSnapshotDirty := sramReadDirty

    val sCleanAllReadSramValid = WireDefault(false.B)
    val sCleanAllReadSramSet = WireDefault(0.U(c.setBits.W))

    // defaults

    io.cacheInterface.readReq.ready := false.B
    io.cacheInterface.writeReq.ready := false.B
    io.cacheInterface.cleanReq.ready := false.B
    io.cacheInterface.invalidateReq.ready := false.B

    io.cacheInterface.readResp.valid := false.B
    io.cacheInterface.readResp.bits := new CacheReadResp().zero
    io.cacheInterface.writeResp.valid := false.B
    io.cacheInterface.writeResp.bits := new CacheWriteResp().zero
    io.cacheInterface.cleanResp := false.B
    io.cacheInterface.invalidateResp := false.B

    io.ioInterface.read.get.params.valid := false.B
    io.ioInterface.read.get.params.bits := new ReadParams()(using getCacheIoConfig(c, CacheType.Dcache)).zero

    io.ioInterface.write.get.params.valid := false.B
    io.ioInterface.write.get.params.bits := new WriteParams()(using getCacheIoConfig(c, CacheType.Dcache)).zero

    io.invalidateAllOutfire := false.B
    io.cleanAllOutfire := false.B

    // hit detect for current transaction

    val readHits = sramReadTagV.map { e =>
        e.valid && (e.tag === transactionTag)
    }

    val readHit = Wire(Bool())
    val hitWay = Wire(UInt(c.wayBits.W))
    val hitData = Wire(UInt((8 * c.dataBytes).W))
    val hitDirty = Wire(Bool())
    val victimDirty = Wire(Bool())

    readHit := readHits.reduce(_ || _)
    hitWay := OHToUInt(readHits)
    hitData := Mux(readHit, Mux1H(readHits, sramReadData.map(_.data)), 0.U)
    hitDirty := Mux(readHit, Mux1H(readHits, sramReadDirty.map(_.dirty)), false.B)
    victimDirty := sramReadDirty(victimWayPtr).dirty

    val mergedWriteData = mergeWriteData(transactionReadData, transactionWriteData, transactionWriteMask)

    // request arbitration / ready

    val lookupCanPipelineNext = WireDefault(false.B)
    when(transactionType === TransactionType.read && readHit) {
        lookupCanPipelineNext := true.B
    }.elsewhen(transactionType === TransactionType.clean && (!readHit || !hitDirty)) {
        lookupCanPipelineNext := true.B
    }

    val canAcceptLocalReq =
        (state === State.sIdle) ||
        ((state === State.sRead) && lookupCanPipelineNext)

    io.cacheInterface.readReq.ready := canAcceptLocalReq
    io.cacheInterface.writeReq.ready := canAcceptLocalReq && !io.cacheInterface.readReq.valid
    io.cacheInterface.cleanReq.ready :=
        (state === State.sIdle) &&
        !io.cacheInterface.readReq.valid &&
        !io.cacheInterface.writeReq.valid
    io.cacheInterface.invalidateReq.ready :=
        (state === State.sIdle) &&
        !io.cacheInterface.readReq.valid &&
        !io.cacheInterface.writeReq.valid &&
        !io.cacheInterface.cleanReq.valid

    val readReqFire =
        io.cacheInterface.readReq.valid &&
        io.cacheInterface.readReq.ready

    val writeReqFire =
        io.cacheInterface.writeReq.valid &&
        io.cacheInterface.writeReq.ready

    val cleanReqFire =
        io.cacheInterface.cleanReq.valid &&
        io.cacheInterface.cleanReq.ready

    val invalidateReqFire =
        io.cacheInterface.invalidateReq.valid &&
        io.cacheInterface.invalidateReq.ready

    // FSM

    switch(state) {
        is(State.sIdle) {
            when(readReqFire) {
                sIdleSetTransactionValid := true.B
                sIdleSetTransactionType := TransactionType.read
                sIdleSetTransactionAddr := reqReadAddr

                sIdleReadSramValid := true.B
                sIdleReadSramSet := reqReadSet

                state := State.sRead
            }.elsewhen(writeReqFire) {
                sIdleSetTransactionValid := true.B
                sIdleSetTransactionType := TransactionType.write
                sIdleSetTransactionAddr := reqWriteAddr
                sIdleSetTransactionWriteData := io.cacheInterface.writeReq.bits.data
                sIdleSetTransactionWriteMask := io.cacheInterface.writeReq.bits.mask

                sIdleReadSramValid := true.B
                sIdleReadSramSet := reqWriteSet

                state := State.sRead
            }.elsewhen(cleanReqFire) {
                sIdleSetTransactionValid := true.B
                sIdleSetTransactionType := TransactionType.clean
                sIdleSetTransactionAddr := reqCleanAddr

                sIdleReadSramValid := true.B
                sIdleReadSramSet := reqCleanSet

                state := State.sRead
            }.elsewhen(invalidateReqFire) {
                sIdleSetTransactionValid := true.B
                sIdleSetTransactionType := TransactionType.invalidate
                sIdleSetTransactionAddr := reqInvalidateAddr

                sIdleReadSramValid := true.B
                sIdleReadSramSet := reqInvalidateSet

                state := State.sRead
            }.elsewhen(io.invalidateAll) {
                invalidateAllSetIdx := 0.U
                state := State.sInvalidateAll
            }.elsewhen(io.cleanAll) {
                cleanAllSetIdx := 0.U
                cleanAllWayPtr := 0.U
                cleanAllSetLoaded := false.B

                sCleanAllReadSramValid := true.B
                sCleanAllReadSramSet := 0.U

                state := State.sCleanAll
            }
        }

        is(State.sRead) {
            val lookupTxnDoneHere = WireDefault(false.B)

            when(readHit) {
                when(transactionType === TransactionType.read) {
                    io.cacheInterface.readResp.valid := true.B
                    io.cacheInterface.readResp.bits.code := CacheCode.CacheHitOk
                    io.cacheInterface.readResp.bits.data := hitData

                    lookupTxnDoneHere := true.B
                }.elsewhen(transactionType === TransactionType.write) {
                    sReadSetWorkSetValid := true.B

                    transactionHitWay := hitWay
                    transactionReadData := hitData
                    transactionWriteCode := CacheCode.CacheHitOk

                    state := State.sWrite
                }.elsewhen(transactionType === TransactionType.clean) {
                    when(hitDirty) {
                        sReadSetWorkSetValid := true.B

                        sReadSetCleanWriteBackValid := true.B
                        sReadSetCleanWriteBackIsCleanAll := false.B
                        sReadSetCleanWriteBackSet := transactionSet
                        sReadSetCleanWriteBackWay := hitWay
                        sReadSetCleanWriteBackAddr := transactionAddr
                        sReadSetCleanWriteBackData := hitData

                        state := State.sCleanWriteBack
                    }.otherwise {
                        io.cacheInterface.cleanResp := true.B
                        lookupTxnDoneHere := true.B
                    }
                }.otherwise {
                    sReadInvalidateWriteValid := true.B
                    for (i <- 0 until c.wayNum) {
                        when(i.U === hitWay) {
                            sReadInvalidateWriteTagV(i).valid := false.B
                            sReadInvalidateWriteDirty(i).dirty := false.B
                        }
                    }

                    io.cacheInterface.invalidateResp := true.B
                    lookupTxnDoneHere := true.B
                }
            }.otherwise {
                when(transactionType === TransactionType.clean) {
                    io.cacheInterface.cleanResp := true.B
                    lookupTxnDoneHere := true.B
                }.elsewhen(transactionType === TransactionType.invalidate) {
                    io.cacheInterface.invalidateResp := true.B
                    lookupTxnDoneHere := true.B
                }.otherwise {
                    sReadSetWorkSetValid := true.B

                    when(victimDirty) {
                        state := State.sVictimWriteBack
                    }.otherwise {
                        state := State.sReplace
                    }
                }
            }

            when(lookupTxnDoneHere) {
                when(readReqFire) {
                    sReadSetTransactionValid := true.B
                    sReadSetTransactionType := TransactionType.read
                    sReadSetTransactionAddr := reqReadAddr

                    sReadReadSramValid := true.B
                    sReadReadSramSet := reqReadSet

                    state := State.sRead
                }.elsewhen(writeReqFire) {
                    sReadSetTransactionValid := true.B
                    sReadSetTransactionType := TransactionType.write
                    sReadSetTransactionAddr := reqWriteAddr
                    sReadSetTransactionWriteData := io.cacheInterface.writeReq.bits.data
                    sReadSetTransactionWriteMask := io.cacheInterface.writeReq.bits.mask

                    sReadReadSramValid := true.B
                    sReadReadSramSet := reqWriteSet

                    state := State.sRead
                }.elsewhen(io.invalidateAll) {
                    invalidateAllSetIdx := 0.U
                    state := State.sInvalidateAll
                }.elsewhen(io.cleanAll) {
                    cleanAllSetIdx := 0.U
                    cleanAllWayPtr := 0.U
                    cleanAllSetLoaded := false.B

                    sCleanAllReadSramValid := true.B
                    sCleanAllReadSramSet := 0.U

                    state := State.sCleanAll
                }.otherwise {
                    state := State.sIdle
                }
            }
        }

        is(State.sWrite) {
            sWriteWriteValid := true.B
            for (i <- 0 until c.wayNum) {
                when(i.U === transactionHitWay) {
                    sWriteWriteTagV(i).valid := transactionWriteCode.isOk()
                    sWriteWriteData(i).data := mergedWriteData
                    sWriteWriteDirty(i).dirty := transactionWriteCode.isOk()
                }
            }

            io.cacheInterface.writeResp.valid := true.B
            io.cacheInterface.writeResp.bits.code := transactionWriteCode

            when(io.invalidateAll) {
                invalidateAllSetIdx := 0.U
                state := State.sInvalidateAll
            }.elsewhen(io.cleanAll) {
                cleanAllSetIdx := 0.U
                cleanAllWayPtr := 0.U
                cleanAllSetLoaded := false.B

                sCleanAllReadSramValid := true.B
                sCleanAllReadSramSet := 0.U

                state := State.sCleanAll
            }.otherwise {
                state := State.sIdle
            }
        }

        is(State.sReplace) {
            io.ioInterface.read.get.params.valid := true.B
            io.ioInterface.read.get.params.bits.addr := transactionAddr
            io.ioInterface.read.get.params.bits.size := 3.U // No function right now, AXI will auto determine the beat size.

            when(io.ioInterface.read.get.resp.valid) {
                val refillOk = io.ioInterface.read.get.resp.bits.resp.isOk()

                sReplaceWriteValid := true.B
                for (i <- 0 until c.wayNum) {
                    when(i.U === victimWayPtr) {
                        sReplaceWriteTagV(i).tag := transactionTag
                        sReplaceWriteTagV(i).valid := refillOk
                        sReplaceWriteData(i).data := io.ioInterface.read.get.resp.bits.data
                        sReplaceWriteDirty(i).dirty := false.B
                    }
                }

                if (c.wayNum > 1) {
                    victimWayPtr := victimWayPtr + 1.U
                }

                when(transactionType === TransactionType.read) {
                    io.cacheInterface.readResp.valid := true.B
                    io.cacheInterface.readResp.bits.code :=
                        Mux(
                            refillOk,
                            CacheCode.CacheMissOk,
                            io.ioInterface.read.get.resp.bits.resp.asTypeOf(new CacheCode.Type)
                        )
                    io.cacheInterface.readResp.bits.data := io.ioInterface.read.get.resp.bits.data

                    when(io.invalidateAll) {
                        invalidateAllSetIdx := 0.U
                        state := State.sInvalidateAll
                    }.elsewhen(io.cleanAll) {
                        cleanAllSetIdx := 0.U
                        cleanAllWayPtr := 0.U
                        cleanAllSetLoaded := false.B

                        sCleanAllReadSramValid := true.B
                        sCleanAllReadSramSet := 0.U

                        state := State.sCleanAll
                    }.otherwise {
                        state := State.sIdle
                    }
                }.otherwise {
                    when(refillOk) {
                        sReplaceSetWorkSetValid := true.B

                        transactionHitWay := victimWayPtr
                        transactionReadData := io.ioInterface.read.get.resp.bits.data
                        transactionWriteCode := CacheCode.CacheMissOk

                        state := State.sWrite
                    }.otherwise {
                        io.cacheInterface.writeResp.valid := true.B
                        io.cacheInterface.writeResp.bits.code :=
                            io.ioInterface.read.get.resp.bits.resp.asTypeOf(new CacheCode.Type)

                        when(io.invalidateAll) {
                            invalidateAllSetIdx := 0.U
                            state := State.sInvalidateAll
                        }.elsewhen(io.cleanAll) {
                            cleanAllSetIdx := 0.U
                            cleanAllWayPtr := 0.U
                            cleanAllSetLoaded := false.B

                            sCleanAllReadSramValid := true.B
                            sCleanAllReadSramSet := 0.U

                            state := State.sCleanAll
                        }.otherwise {
                            state := State.sIdle
                        }
                    }
                }
            }
        }

        is(State.sVictimWriteBack) {
            val dirtyVictimAddr = Cat(workSetTagVReg(victimWayPtr).tag, transactionSet, 0.U(c.offsetBits.W))

            io.ioInterface.write.get.params.valid := true.B
            io.ioInterface.write.get.params.bits.addr := dirtyVictimAddr
            io.ioInterface.write.get.params.bits.data := workSetDataReg(victimWayPtr).data
            io.ioInterface.write.get.params.bits.size := log2Ceil(c.dataBytes).U

            when(io.ioInterface.write.get.resp.valid) {
                // TODO: handle write-back error
                state := State.sReplace
            }
        }

        is(State.sCleanWriteBack) {
            io.ioInterface.write.get.params.valid := true.B
            io.ioInterface.write.get.params.bits.addr := cleanWriteBackAddr
            io.ioInterface.write.get.params.bits.data := cleanWriteBackData
            io.ioInterface.write.get.params.bits.size := log2Ceil(c.dataBytes).U

            when(io.ioInterface.write.get.resp.valid) {
                val writeBackOk = io.ioInterface.write.get.resp.bits.isOk()

                when(cleanWriteBackIsCleanAll) {
                    sCleanWriteBackDirtyWriteValid := true.B
                    sCleanWriteBackDirtyWriteSet := cleanWriteBackSet
                    for (i <- 0 until c.wayNum) {
                        sCleanWriteBackDirtyWrite(i) := cleanAllSetDirtyReg(i)
                        sCleanWriteBackSetCleanAllDirty(i) := cleanAllSetDirtyReg(i)
                        when(i.U === cleanWriteBackWay && writeBackOk) {
                            sCleanWriteBackDirtyWrite(i).dirty := false.B
                            sCleanWriteBackSetCleanAllDirty(i).dirty := false.B
                        }
                    }
                    sCleanWriteBackSetCleanAllDirtyValid := true.B

                    if (c.wayNum > 1) {
                        when(isLastWay(cleanAllWayPtr)) {
                            when(cleanAllSetIdx === (c.setNum - 1).U) {
                                io.cleanAllOutfire := true.B
                                state := State.sIdle
                            }.otherwise {
                                cleanAllSetIdx := cleanAllSetIdx + 1.U
                                cleanAllWayPtr := 0.U
                                cleanAllSetLoaded := false.B

                                sCleanAllReadSramValid := true.B
                                sCleanAllReadSramSet := cleanAllSetIdx + 1.U

                                state := State.sCleanAll
                            }
                        }.otherwise {
                            cleanAllWayPtr := nextWay(cleanAllWayPtr)
                            state := State.sCleanAll
                        }
                    } else {
                        when(cleanAllSetIdx === (c.setNum - 1).U) {
                            io.cleanAllOutfire := true.B
                            state := State.sIdle
                        }.otherwise {
                            cleanAllSetIdx := cleanAllSetIdx + 1.U
                            cleanAllWayPtr := 0.U
                            cleanAllSetLoaded := false.B

                            sCleanAllReadSramValid := true.B
                            sCleanAllReadSramSet := cleanAllSetIdx + 1.U

                            state := State.sCleanAll
                        }
                    }
                }.otherwise {
                    sCleanWriteBackDirtyWriteValid := true.B
                    sCleanWriteBackDirtyWriteSet := cleanWriteBackSet
                    for (i <- 0 until c.wayNum) {
                        sCleanWriteBackDirtyWrite(i) := workSetDirtyReg(i)
                        when(i.U === cleanWriteBackWay && writeBackOk) {
                            sCleanWriteBackDirtyWrite(i).dirty := false.B
                        }
                    }

                    io.cacheInterface.cleanResp := true.B

                    when(io.invalidateAll) {
                        invalidateAllSetIdx := 0.U
                        state := State.sInvalidateAll
                    }.elsewhen(io.cleanAll) {
                        cleanAllSetIdx := 0.U
                        cleanAllWayPtr := 0.U
                        cleanAllSetLoaded := false.B

                        sCleanAllReadSramValid := true.B
                        sCleanAllReadSramSet := 0.U

                        state := State.sCleanAll
                    }.otherwise {
                        state := State.sIdle
                    }
                }
            }
        }

        is(State.sInvalidateAll) {
            sInvalidateAllWriteValid := true.B
            sInvalidateAllWriteSet := invalidateAllSetIdx

            when(invalidateAllSetIdx === (c.setNum - 1).U) {
                io.invalidateAllOutfire := true.B
                state := State.sIdle
            }.otherwise {
                invalidateAllSetIdx := invalidateAllSetIdx + 1.U
            }
        }

        is(State.sCleanAll) {
            when(!cleanAllSetLoaded) {
                sCleanAllSetSnapshotValid := true.B
                cleanAllSetLoaded := true.B
                cleanAllWayPtr := 0.U
            }.otherwise {
                when(cleanAllSetDirtyReg(cleanAllWayPtr).dirty) {
                    sCleanAllSetCleanWriteBackValid := true.B
                    sCleanAllSetCleanWriteBackSet := cleanAllSetIdx
                    sCleanAllSetCleanWriteBackWay := cleanAllWayPtr
                    sCleanAllSetCleanWriteBackAddr :=
                        Cat(cleanAllSetTagVReg(cleanAllWayPtr).tag, cleanAllSetIdx, 0.U(c.offsetBits.W))
                    sCleanAllSetCleanWriteBackData := cleanAllSetDataReg(cleanAllWayPtr).data

                    state := State.sCleanWriteBack
                }.otherwise {
                    if (c.wayNum > 1) {
                        when(isLastWay(cleanAllWayPtr)) {
                            when(cleanAllSetIdx === (c.setNum - 1).U) {
                                io.cleanAllOutfire := true.B
                                state := State.sIdle
                            }.otherwise {
                                cleanAllSetIdx := cleanAllSetIdx + 1.U
                                cleanAllWayPtr := 0.U
                                cleanAllSetLoaded := false.B

                                sCleanAllReadSramValid := true.B
                                sCleanAllReadSramSet := cleanAllSetIdx + 1.U

                                state := State.sCleanAll
                            }
                        }.otherwise {
                            cleanAllWayPtr := nextWay(cleanAllWayPtr)
                            state := State.sCleanAll
                        }
                    } else {
                        when(cleanAllSetIdx === (c.setNum - 1).U) {
                            io.cleanAllOutfire := true.B
                            state := State.sIdle
                        }.otherwise {
                            cleanAllSetIdx := cleanAllSetIdx + 1.U
                            cleanAllWayPtr := 0.U
                            cleanAllSetLoaded := false.B

                            sCleanAllReadSramValid := true.B
                            sCleanAllReadSramSet := cleanAllSetIdx + 1.U

                            state := State.sCleanAll
                        }
                    }
                }
            }
        }
    }

    // commit actions

    val finalSetTransactionValid = sIdleSetTransactionValid || sReadSetTransactionValid
    when(finalSetTransactionValid) {
        transactionType :=
            Mux(sIdleSetTransactionValid, sIdleSetTransactionType, sReadSetTransactionType)
        transactionAddr :=
            Mux(sIdleSetTransactionValid, sIdleSetTransactionAddr, sReadSetTransactionAddr)
        transactionWriteData :=
            Mux(sIdleSetTransactionValid, sIdleSetTransactionWriteData, sReadSetTransactionWriteData)
        transactionWriteMask :=
            Mux(sIdleSetTransactionValid, sIdleSetTransactionWriteMask, sReadSetTransactionWriteMask)
    }

    when(sReadSetWorkSetValid) {
        workSetTagVReg := sReadSetWorkSetTagV
        workSetDataReg := sReadSetWorkSetData
        workSetDirtyReg := sReadSetWorkSetDirty
    }

    when(sReplaceSetWorkSetValid) {
        workSetTagVReg := sReplaceSetWorkSetTagV
        workSetDataReg := sReplaceSetWorkSetData
        workSetDirtyReg := sReplaceSetWorkSetDirty
    }

    val finalSetCleanWriteBackValid =
        sReadSetCleanWriteBackValid || sCleanAllSetCleanWriteBackValid

    when(finalSetCleanWriteBackValid) {
        cleanWriteBackIsCleanAll :=
            Mux(sReadSetCleanWriteBackValid, sReadSetCleanWriteBackIsCleanAll, sCleanAllSetCleanWriteBackIsCleanAll)
        cleanWriteBackSet :=
            Mux(sReadSetCleanWriteBackValid, sReadSetCleanWriteBackSet, sCleanAllSetCleanWriteBackSet)
        cleanWriteBackWay :=
            Mux(sReadSetCleanWriteBackValid, sReadSetCleanWriteBackWay, sCleanAllSetCleanWriteBackWay)
        cleanWriteBackAddr :=
            Mux(sReadSetCleanWriteBackValid, sReadSetCleanWriteBackAddr, sCleanAllSetCleanWriteBackAddr)
        cleanWriteBackData :=
            Mux(sReadSetCleanWriteBackValid, sReadSetCleanWriteBackData, sCleanAllSetCleanWriteBackData)
    }

    when(sCleanAllSetSnapshotValid) {
        cleanAllSetTagVReg := sCleanAllSetSnapshotTagV
        cleanAllSetDataReg := sCleanAllSetSnapshotData
        cleanAllSetDirtyReg := sCleanAllSetSnapshotDirty
    }

    when(sCleanWriteBackSetCleanAllDirtyValid) {
        cleanAllSetDirtyReg := sCleanWriteBackSetCleanAllDirty
    }

    when(sWriteWriteValid) {
        tagVArray.write(sWriteWriteSet, sWriteWriteTagV)
        dataArray.write(sWriteWriteSet, sWriteWriteData)
        dirtyArray.write(sWriteWriteSet, sWriteWriteDirty)
    }

    when(sReplaceWriteValid) {
        tagVArray.write(sReplaceWriteSet, sReplaceWriteTagV)
        dataArray.write(sReplaceWriteSet, sReplaceWriteData)
        dirtyArray.write(sReplaceWriteSet, sReplaceWriteDirty)
    }

    when(sReadInvalidateWriteValid) {
        tagVArray.write(sReadInvalidateWriteSet, sReadInvalidateWriteTagV)
        dirtyArray.write(sReadInvalidateWriteSet, sReadInvalidateWriteDirty)
    }

    when(sCleanWriteBackDirtyWriteValid) {
        dirtyArray.write(sCleanWriteBackDirtyWriteSet, sCleanWriteBackDirtyWrite)
    }

    when(sInvalidateAllWriteValid) {
        tagVArray.write(sInvalidateAllWriteSet, sInvalidateAllWriteTagV)
        dirtyArray.write(sInvalidateAllWriteSet, sInvalidateAllWriteDirty)
    }

    // unified SRAM read port

    val finalSramReadValid =
        sIdleReadSramValid ||
        sReadReadSramValid ||
        sCleanAllReadSramValid

    val finalSramReadSet = WireDefault(0.U(c.setBits.W))

    when(sIdleReadSramValid) {
        finalSramReadSet := sIdleReadSramSet
    }
    when(sReadReadSramValid) {
        finalSramReadSet := sReadReadSramSet
    }
    when(sCleanAllReadSramValid) {
        finalSramReadSet := sCleanAllReadSramSet
    }

    sramReadTagV := tagVArray.read(finalSramReadSet, finalSramReadValid)
    sramReadData := dataArray.read(finalSramReadSet, finalSramReadValid)
    sramReadDirty := dirtyArray.read(finalSramReadSet, finalSramReadValid)
}