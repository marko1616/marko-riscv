package markorv.cache

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.utils.ConfigUtils._
import markorv.config._
import markorv.bus._
import markorv.cache._

class InstrCache(implicit val c: CacheConfig) extends Module {
    val io = IO(new Bundle {
        val cacheInterface = new IcacheInterface
        val ioInterface = new IOInterface()(getCacheIoConfig(c, CacheType.Icache), true)

        val privilege = Input(UInt(2.W))
        val satpModeField = Input(UInt(4.W))

        val mmuReq = Decoupled(new MMUReq)
        val mmuResp = Flipped(Valid(new MmuResp))

        val invalidateAll = Input(Bool())
        val invalidateAllOutfire = Output(Bool())
    })

    object State extends ChiselEnum {
        val sIdle, sRead, sReplace, sInvalidate = Value
    }

    val tagVArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheTagValid))
    val dataArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheData))

    // current transaction
    val transactionVaLow      = RegInit(0.U(12.W))
    val transactionPaHigh     = RegInit(0.U((c.addrWidth - 12).W))
    val transactionPaLow      = transactionVaLow(11, 0)
    val transactionPa         = Cat(transactionPaHigh, transactionPaLow)
    val transactionLineBasePa = Cat(transactionPa(c.addrWidth - 1, c.offsetBits), 0.U(c.offsetBits.W))

    val victimPtr = RegInit(0.U(c.wayBits.W))

    val state = RegInit(State.sInvalidate)
    val invalidateIdx = RegInit(0.U(c.setBits.W))

    val replaceSetTagVReg = RegInit(VecInit(Seq.fill(c.wayNum)(new CacheTagValid().zero)))
    val replaceSetDataReg = RegInit(VecInit(Seq.fill(c.wayNum)(new CacheData().zero)))

    val sramReadTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sramReadData = Wire(Vec(c.wayNum, new CacheData))

    val latchedValid = RegInit(false.B)
    val latchedReadTagV = Reg(Vec(c.wayNum, new CacheTagValid))
    val latchedReadData = Reg(Vec(c.wayNum, new CacheData))

    val finalTagV = Mux(latchedValid, latchedReadTagV, sramReadTagV)
    val finalData = Mux(latchedValid, latchedReadData, sramReadData)

    val reqReadVa  = io.cacheInterface.readReq.bits.vaddr
    val reqReadSet = if (c.setNum == 1) 0.U else reqReadVa(c.setEnd, c.setStart)

    val transactionSet = if (c.setNum == 1) 0.U else transactionPa(c.setEnd, c.setStart)
    val transactionTag = transactionPa(c.tagEnd, c.tagStart)

    // state local actions

    // sIdle -> latch transaction / start SRAM read
    val sIdleSetTransactionValid = WireDefault(false.B)
    val sIdleSetTransactionVaLow = WireDefault(0.U(12.W))

    val sIdleReadSramValid = WireDefault(false.B)
    val sIdleReadSramSet   = WireDefault(0.U(c.setBits.W))

    // sRead -> latch next transaction / start next SRAM read
    val sReadSetTransactionValid = WireDefault(false.B)
    val sReadSetTransactionVaLow = WireDefault(0.U(12.W))

    val sReadSetTransactionPaHighValid = WireDefault(false.B)
    val sReadSetTransactionPaHigh      = WireDefault(0.U((c.addrWidth - 12).W))

    val sReadReadSramValid = WireDefault(false.B)
    val sReadReadSramSet   = WireDefault(0.U(c.setBits.W))

    // sRead -> miss snapshot
    val sReadSetReplaceSetValid = WireDefault(false.B)
    val sReadSetReplaceSetTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReadSetReplaceSetData  = Wire(Vec(c.wayNum, new CacheData))
    sReadSetReplaceSetTagV := finalTagV
    sReadSetReplaceSetData := finalData

    // sReplace -> write refill result back
    val sReplaceWriteValid = WireDefault(false.B)
    val sReplaceWriteSet   = WireDefault(transactionSet)
    val sReplaceWriteTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReplaceWriteData  = Wire(Vec(c.wayNum, new CacheData))

    for (i <- 0 until c.wayNum) {
        sReplaceWriteTagV(i) := replaceSetTagVReg(i)
        sReplaceWriteData(i) := replaceSetDataReg(i)
    }

    // sInvalidate -> clear all valids
    val sInvalidateWriteValid = WireDefault(false.B)
    val sInvalidateWriteSet   = WireDefault(invalidateIdx)
    val sInvalidateWriteTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    sInvalidateWriteTagV := VecInit(Seq.fill(c.wayNum)(new CacheTagValid().zero))

    // defaults
    val mmuHlt    = !io.mmuReq.ready
    val mmuPaHigh = io.mmuResp.bits.pa(c.addrWidth - 1, 12)

    io.cacheInterface.readReq.ready := false.B

    io.cacheInterface.readResp.valid := false.B
    io.cacheInterface.readResp.bits  := new ICacheReadResp().zero

    io.ioInterface.read.get.params.valid := false.B
    io.ioInterface.read.get.params.bits  := new ReadParams()(using getCacheIoConfig(c, CacheType.Icache)).zero

    io.invalidateAllOutfire := false.B

    io.mmuReq.valid := false.B
    io.mmuReq.bits  := new MMUReq().zero

    // Priv check && MMU mode selection
    // SV39 only, same assumption as DataCache
    val useProt = io.satpModeField === 8.U && io.privilege =/= 3.U
    val mmuMode = Mux(useProt, MmuMode.sv39, MmuMode.bare)

    val mmuRespPteValid = io.mmuResp.bits.valid

    val mmuPrivValidM = true.B
    val mmuPrivValidS = !io.mmuResp.bits.user
    val mmuPrivValidU = io.mmuResp.bits.user
    val mmuPrivValid = MuxLookup(io.privilege, false.B)(Seq(
        "b00".U -> mmuPrivValidU,
        "b01".U -> mmuPrivValidS,
        "b11".U -> mmuPrivValidM
    )) || !useProt

    val mmuWalkPmaFault = io.mmuResp.bits.walkPmaFault

    // Instruction fetch permission:
    // valid leaf + privilege match + A + X
    val instTransactionPteValid =
        mmuRespPteValid &&
        mmuPrivValid &&
        io.mmuResp.bits.accessed &&
        io.mmuResp.bits.pteExec

    // NOTE:
    // Prefer pmaExec for instruction fetch.
    // If your current MmuResp still only has pmaRead/pmaWrite, replace pmaExec with your exec-side PMA bit.
    val instTransactionPmaValid = io.mmuResp.bits.pmaExec
    val transactionPmaCacheable = io.mmuResp.bits.cache

    // hit detect for current transaction (compare PA tag, not VA tag)
    val mmuTag = io.mmuResp.bits.pa(c.tagEnd, c.tagStart)
    val readHits = finalTagV.map { e =>
        e.valid && (e.tag === mmuTag)
    }

    val readHit = Wire(Bool())
    val hitData = Wire(UInt((8 * c.dataBytes).W))

    readHit := readHits.reduce(_ || _)
    hitData := Mux(readHit, Mux1H(readHits, finalData.map(_.data)), 0.U)

    // request arbitration / ready
    // same style as new DataCache: only hit can fully complete in sRead and accept next read
    val lookupCompletesThisCycle = io.mmuResp.valid && readHit
    val canAcceptLocalReq = (state === State.sIdle) || ((state === State.sRead) && lookupCompletesThisCycle)

    io.cacheInterface.readReq.ready := canAcceptLocalReq && !mmuHlt

    val readReqValid = io.cacheInterface.readReq.valid
    val readReqFire  = io.cacheInterface.readReq.fire

    // FSM
    switch(state) {
        is(State.sIdle) {
            val transactionReqVaValid = WireInit(false.B)
            val transactionReqVaFired = WireInit(false.B)
            val transactionReqVa      = WireInit(0.U(64.W))

            when(readReqValid) {
                transactionReqVaValid := true.B
                transactionReqVa := reqReadVa
            }

            when(readReqFire) {
                sIdleSetTransactionValid := true.B
                sIdleSetTransactionVaLow := reqReadVa(11, 0)

                sIdleReadSramValid := true.B
                sIdleReadSramSet   := reqReadSet

                transactionReqVaFired := true.B
            }.elsewhen(io.invalidateAll) {
                invalidateIdx := 0.U
            }

            when(transactionReqVaValid) {
                io.mmuReq.valid := true.B
                io.mmuReq.bits.va   := transactionReqVa
                io.mmuReq.bits.mode := mmuMode
            }

            val nextState = Mux(transactionReqVaFired, State.sRead,
                Mux(io.invalidateAll, State.sInvalidate, State.sIdle))
            state := nextState
        }

        is(State.sRead) {
            val lookupTxnDoneHere   = WireDefault(false.B)
            val transactionReqVaValid = WireInit(false.B)
            val transactionReqVaFired = WireInit(false.B)
            val transactionReqVa      = WireInit(0.U(64.W))
            val normalNextState       = WireDefault(State.sRead)

            when(!latchedValid) {
                latchedValid := true.B
                latchedReadTagV := sramReadTagV
                latchedReadData := sramReadData
            }

            when(io.mmuResp.valid) {
                val pteFault      = !instTransactionPteValid
                val pmaFault      = mmuRespPteValid && mmuPrivValid && !instTransactionPmaValid
                val pmaCacheFault = instTransactionPteValid && !transactionPmaCacheable

                // Error priority:
                // pmaWalkErr > pmaInstErr > pageInstErr > pmaCacheErr
                val transactionFault = mmuWalkPmaFault || pmaFault || pteFault || pmaCacheFault

                val faultCode = MuxCase(ICacheCode.pmaMmuWalkErr, Seq(
                    mmuWalkPmaFault -> ICacheCode.pmaMmuWalkErr,
                    pmaFault        -> ICacheCode.pmaInstErr,
                    pteFault        -> ICacheCode.pageInstErr,
                    pmaCacheFault   -> ICacheCode.pmaCacheErr
                ))

                val noFaultRespValid = !transactionFault && readHit
                val respValid = transactionFault || noFaultRespValid

                val respCode = Mux(transactionFault, faultCode,
                    Mux(readHit, ICacheCode.cacheHitOk, ICacheCode.cacheMissOk))
                val respData = Mux(!transactionFault && readHit, hitData, 0.U)

                latchedValid := false.B

                io.cacheInterface.readResp.valid     := respValid
                io.cacheInterface.readResp.bits.code := respCode
                io.cacheInterface.readResp.bits.data := respData

                lookupTxnDoneHere := respValid

                when(!transactionFault) {
                    sReadSetTransactionPaHighValid := true.B
                    sReadSetTransactionPaHigh := mmuPaHigh

                    when(!readHit) {
                        sReadSetReplaceSetValid := true.B
                        normalNextState := State.sReplace
                    }
                }
            }

            when(lookupTxnDoneHere) {
                when(readReqValid) {
                    transactionReqVaValid := true.B
                    transactionReqVa := reqReadVa
                }

                when(readReqFire) {
                    sReadSetTransactionValid := true.B
                    sReadSetTransactionVaLow := reqReadVa(11, 0)

                    sReadReadSramValid := true.B
                    sReadReadSramSet   := reqReadSet

                    transactionReqVaFired := true.B
                }.elsewhen(io.invalidateAll) {
                    invalidateIdx := 0.U
                }
            }

            when(transactionReqVaValid) {
                io.mmuReq.valid := true.B
                io.mmuReq.bits.va   := transactionReqVa
                io.mmuReq.bits.mode := mmuMode
            }

            val pipelineNextState = Mux(transactionReqVaFired, State.sRead,
                Mux(io.invalidateAll, State.sInvalidate, State.sIdle))

            val finalNextState = Mux(lookupTxnDoneHere, pipelineNextState, normalNextState)
            state := finalNextState
        }

        is(State.sReplace) {
            io.ioInterface.read.get.params.valid := true.B
            io.ioInterface.read.get.params.bits.addr := transactionLineBasePa
            io.ioInterface.read.get.params.bits.size := c.offsetBits.U

            when(io.ioInterface.read.get.resp.valid) {
                val refillOk = io.ioInterface.read.get.resp.bits.resp.isOk()

                sReplaceWriteValid := true.B
                for (i <- 0 until c.wayNum) {
                    when(i.U === victimPtr) {
                        sReplaceWriteTagV(i).tag   := transactionTag
                        sReplaceWriteTagV(i).valid := refillOk
                        sReplaceWriteData(i).data  := io.ioInterface.read.get.resp.bits.data
                    }
                }

                if (c.wayNum > 1) {
                    victimPtr := victimPtr + 1.U
                }

                io.cacheInterface.readResp.valid := true.B
                io.cacheInterface.readResp.bits.code.fromAxiResp(io.ioInterface.read.get.resp.bits.resp, false.B)
                io.cacheInterface.readResp.bits.data := io.ioInterface.read.get.resp.bits.data

                when(io.invalidateAll) {
                    invalidateIdx := 0.U
                    state := State.sInvalidate
                }.otherwise {
                    state := State.sIdle
                }
            }
        }

        is(State.sInvalidate) {
            sInvalidateWriteValid := true.B
            sInvalidateWriteSet   := invalidateIdx

            when(invalidateIdx === (c.setNum - 1).U) {
                io.invalidateAllOutfire := true.B
                state := State.sIdle
            }.otherwise {
                invalidateIdx := invalidateIdx + 1.U
            }
        }
    }

    // Commit
    val finalSetTransactionValid = sIdleSetTransactionValid || sReadSetTransactionValid
    when(finalSetTransactionValid) {
        transactionVaLow := Mux(sIdleSetTransactionValid, sIdleSetTransactionVaLow, sReadSetTransactionVaLow)
    }

    when(sReadSetTransactionPaHighValid) {
        transactionPaHigh := sReadSetTransactionPaHigh
    }

    when(sReadSetReplaceSetValid) {
        replaceSetTagVReg := sReadSetReplaceSetTagV
        replaceSetDataReg := sReadSetReplaceSetData
    }

    when(sReplaceWriteValid) {
        tagVArray.write(sReplaceWriteSet, sReplaceWriteTagV)
        dataArray.write(sReplaceWriteSet, sReplaceWriteData)
    }

    when(sInvalidateWriteValid) {
        tagVArray.write(sInvalidateWriteSet, sInvalidateWriteTagV)
    }

    // unified SRAM read port
    val finalSramReadValid = sIdleReadSramValid || sReadReadSramValid
    val finalSramReadSet   = WireDefault(0.U(c.setBits.W))

    when(sIdleReadSramValid) {
        finalSramReadSet := sIdleReadSramSet
    }
    when(sReadReadSramValid) {
        finalSramReadSet := sReadReadSramSet
    }

    sramReadTagV := tagVArray.read(finalSramReadSet, finalSramReadValid)
    sramReadData := dataArray.read(finalSramReadSet, finalSramReadValid)
}
