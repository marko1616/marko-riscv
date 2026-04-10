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

        val invalidateAll = Input(Bool())
        val invalidateAllOutfire = Output(Bool())
    })

    object State extends ChiselEnum {
        val sIdle, sRead, sReadReplace, sInvalidate = Value
    }

    val tagVArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheTagValid))
    val dataArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheData))

    val transactionReadAddr = Reg(UInt(64.W))
    val transactionReadSet  = Reg(UInt(c.setBits.W))
    val transactionReadTag  = Reg(UInt(c.tagBits.W))
    val victimPtr           = RegInit(0.U(log2Ceil(c.wayNum).W))

    val state = RegInit(State.sIdle)
    val invalidateIdx = RegInit(0.U(c.setBits.W))

    val replaceSetTagVReg = RegInit(VecInit(Seq.fill(c.wayNum)(new CacheTagValid().zero)))
    val replaceSetDataReg = RegInit(VecInit(Seq.fill(c.wayNum)(new CacheData().zero)))

    val sramReadTagV = Wire(Vec(c.wayNum, new CacheTagValid))
    val sramReadData = Wire(Vec(c.wayNum, new CacheData))

    val reqReadAddr = io.cacheInterface.readReq.bits.addr
    val reqReadSet  = reqReadAddr(c.setEnd, c.setStart)
    val reqReadTag  = reqReadAddr(c.tagEnd, c.tagStart)


    // State actions
    // sIdle -> latch transaction / start sram read
    val sIdleSetTransactionValid = WireDefault(false.B)
    val sIdleSetTransactionAddr  = WireDefault(0.U(64.W))
    val sIdleSetTransactionSet   = WireDefault(0.U(c.setBits.W))
    val sIdleSetTransactionTag   = WireDefault(0.U(c.tagBits.W))

    val sIdleReadSramValid = WireDefault(false.B)
    val sIdleReadSramSet   = WireDefault(0.U(c.setBits.W))

    // read result for current transaction
    val readHit = Wire(Bool())
    val hitData = Wire(UInt((8 * c.dataBytes).W))

    // sRead -> pipeline next transaction only when current read hits
    val sReadSetTransactionValid = WireDefault(false.B)
    val sReadSetTransactionAddr  = WireDefault(0.U(64.W))
    val sReadSetTransactionSet   = WireDefault(0.U(c.setBits.W))
    val sReadSetTransactionTag   = WireDefault(0.U(c.tagBits.W))

    // sRead -> miss snapshot
    val sReadSetReplaceSetValid = WireDefault(false.B)
    val sReadSetReplaceSetTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReadSetReplaceSetData  = Wire(Vec(c.wayNum, new CacheData))
    sReadSetReplaceSetTagV := sramReadTagV
    sReadSetReplaceSetData := sramReadData

    // sRead -> read next set into SRAM
    val sReadReadSramValid = WireDefault(false.B)
    val sReadReadSramSet   = WireDefault(0.U(c.setBits.W))

    // sReadReplace -> write refill result back
    val sReadReplaceWriteValid = WireDefault(false.B)
    val sReadReplaceWriteSet   = WireDefault(transactionReadSet)
    val sReadReplaceWriteWay   = WireDefault(victimPtr)
    val sReadReplaceWriteTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReadReplaceWriteData  = Wire(Vec(c.wayNum, new CacheData))

    for (i <- 0 until c.wayNum) {
        sReadReplaceWriteTagV(i) := replaceSetTagVReg(i)
        sReadReplaceWriteData(i) := replaceSetDataReg(i)
    }

    // sInvalidate -> clear all valids
    val sInvalidateWriteValid = WireDefault(false.B)
    val sInvalidateWriteSet   = WireDefault(invalidateIdx)
    val sInvalidateWriteTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    sInvalidateWriteTagV := VecInit(Seq.fill(c.wayNum)(new CacheTagValid().zero))

    // Default assignments
    io.cacheInterface.readReq.ready := false.B

    io.cacheInterface.readResp.valid := false.B
    io.cacheInterface.readResp.bits := new CacheReadResp().zero

    io.ioInterface.read.get.params.valid := false.B
    io.ioInterface.read.get.params.bits := new ReadParams()(using getCacheIoConfig(c, CacheType.Icache)).zero

    io.invalidateAllOutfire := false.B

    // Hit
    val readHits = sramReadTagV.map { e =>
        e.valid && (e.tag === transactionReadTag)
    }

    readHit := readHits.reduce(_ || _)
    hitData := Mux(readHit, Mux1H(readHits, sramReadData.map(_.data)), 0.U)

    io.cacheInterface.readReq.ready := !io.invalidateAll && (
        (state === State.sIdle) ||
        ((state === State.sRead) && readHit)
    )

    // FSM
    switch(state) {
        is(State.sIdle) {
            when(io.invalidateAll) {
                invalidateIdx := 0.U
                state := State.sInvalidate
            }.elsewhen(io.cacheInterface.readReq.fire) {
                sIdleSetTransactionValid := true.B
                sIdleSetTransactionAddr  := reqReadAddr
                sIdleSetTransactionSet   := reqReadSet
                sIdleSetTransactionTag   := reqReadTag

                sIdleReadSramValid := true.B
                sIdleReadSramSet   := reqReadSet

                state := State.sRead
            }
        }

        is(State.sRead) {
            when(readHit) {
                io.cacheInterface.readResp.valid := true.B
                io.cacheInterface.readResp.bits.code := CacheCode.CacheHitOk
                io.cacheInterface.readResp.bits.data := hitData

                when(io.invalidateAll) {
                    invalidateIdx := 0.U
                    state := State.sInvalidate
                }.elsewhen(io.cacheInterface.readReq.fire) {
                    sReadSetTransactionValid := true.B
                    sReadSetTransactionAddr  := reqReadAddr
                    sReadSetTransactionSet   := reqReadSet
                    sReadSetTransactionTag   := reqReadTag

                    sReadReadSramValid := true.B
                    sReadReadSramSet   := reqReadSet

                    state := State.sRead
                }.otherwise {
                    state := State.sIdle
                }
            }.otherwise {
                sReadSetReplaceSetValid := true.B
                state := State.sReadReplace
            }
        }

        is(State.sReadReplace) {
            io.ioInterface.read.get.params.valid := true.B
            io.ioInterface.read.get.params.bits.addr := transactionReadAddr
            io.ioInterface.read.get.params.bits.size := 3.U // No function right now, AXI will auto determine the beat size.

            when(io.ioInterface.read.get.resp.valid) {
                val refillOk = io.ioInterface.read.get.resp.bits.resp.isOk()

                sReadReplaceWriteValid := true.B

                for (i <- 0 until c.wayNum) {
                    when(sReadReplaceWriteWay === i.U) {
                        sReadReplaceWriteTagV(i).tag   := transactionReadTag
                        sReadReplaceWriteTagV(i).valid := refillOk
                        sReadReplaceWriteData(i).data  := io.ioInterface.read.get.resp.bits.data
                    }
                }

                victimPtr := victimPtr + 1.U

                io.cacheInterface.readResp.valid := true.B
                io.cacheInterface.readResp.bits.code :=
                Mux(
                    refillOk,
                    CacheCode.CacheMissOk,
                    io.ioInterface.read.get.resp.bits.resp.asTypeOf(new CacheCode.Type)
                )
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
        transactionReadAddr := Mux(sIdleSetTransactionValid, sIdleSetTransactionAddr, sReadSetTransactionAddr)
        transactionReadSet  := Mux(sIdleSetTransactionValid, sIdleSetTransactionSet,  sReadSetTransactionSet)
        transactionReadTag  := Mux(sIdleSetTransactionValid, sIdleSetTransactionTag,  sReadSetTransactionTag)
    }

    when(sReadSetReplaceSetValid) {
        replaceSetTagVReg := sReadSetReplaceSetTagV
        replaceSetDataReg := sReadSetReplaceSetData
    }

    when(sReadReplaceWriteValid) {
        tagVArray.write(sReadReplaceWriteSet, sReadReplaceWriteTagV)
        dataArray.write(sReadReplaceWriteSet, sReadReplaceWriteData)
    }

    when(sInvalidateWriteValid) {
        tagVArray.write(sInvalidateWriteSet, sInvalidateWriteTagV)
    }

    // Sram read
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
