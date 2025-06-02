package markorv.cache

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.bus._
import markorv.cache._

class DataCache(implicit val config: CacheConfig) extends Module {
    val io = IO(new Bundle {
        val inReadReq = Flipped(Decoupled(UInt(64.W)))
        val inReadData = Decoupled(UInt((8 * config.dataBytes).W))
        val outReadReq = Decoupled(UInt(64.W))
        val outReadData = Flipped(Decoupled(UInt((8 * config.dataBytes).W)))

        val inWriteReq = Flipped(Decoupled(new Bundle {
            val addr = UInt(64.W)
            val data = UInt((8 * config.dataBytes).W)
        }))
        val inWriteResp = Decoupled(Bool())
        val outWriteReq = Decoupled(new Bundle {
            val addr = UInt(64.W)
            val data = UInt((8 * config.dataBytes).W)
        })
        val outWriteResp = Flipped(Decoupled(Bool()))

        val transactionAddr = Output(UInt(64.W))
        val invalidate = Input(Bool())
        val invalidateOutfire = Output(Bool())
    })

    object State extends ChiselEnum {
        val statIdle, statRead, statWrite, statReadReplace, statWriteReplace,
            statWriteBack, statInvalidate = Value
    }

    val reqAddr = Reg(UInt(64.W))
    val writeData = Reg(UInt((8 * config.dataBytes).W))
    val state = RegInit(State.statIdle)
    val invalidateState = Reg(UInt(config.setBits.W))
    val replacePtr = Reg(UInt(log2Ceil(config.wayNum).W))

    val tagVArray = SyncReadMem(config.setNum, Vec(config.wayNum, new CacheTagValid))
    val dataArray = SyncReadMem(config.setNum, Vec(config.wayNum, new CacheData))
    val dirtyArray = SyncReadMem(config.setNum, Vec(config.wayNum, new CacheDirty))

    val tagvRead = Wire(Vec(config.wayNum, new CacheTagValid))
    val dataRead = Wire(Vec(config.wayNum, new CacheData))
    val dirtyRead = Wire(Vec(config.wayNum, new CacheDirty))

    val defaultIndex = Mux(io.inReadReq.valid,
                        io.inReadReq.bits(config.setEnd, config.setStart),
                        io.inWriteReq.bits.addr(config.setEnd, config.setStart))

    val lookupIndex = MuxLookup(state, defaultIndex)(Seq(
        State.statReadReplace -> reqAddr(config.setEnd, config.setStart),
        State.statWriteReplace -> reqAddr(config.setEnd, config.setStart),
        State.statWriteBack -> reqAddr(config.setEnd, config.setStart)
    ))

    val lookupValid = (io.inReadReq.valid || io.inWriteReq.valid) &&
                      (state === State.statIdle || state === State.statRead || state === State.statWrite ||
                       state === State.statReadReplace || state === State.statWriteReplace || state === State.statWriteBack)

    io.inReadReq.ready := (state === State.statIdle || state === State.statRead)
    io.inWriteReq.ready := (state === State.statIdle || state === State.statWrite)
    io.outReadData.ready := (state === State.statReadReplace)
    io.outWriteResp.ready := (state === State.statWriteReplace || state === State.statWriteBack)

    io.inReadData.valid := false.B
    io.outReadReq.valid := false.B
    io.inWriteResp.valid := false.B
    io.outWriteReq.valid := false.B

    io.inReadData.bits := 0.U
    io.outReadReq.bits := 0.U
    io.inWriteResp.bits := false.B
    io.outWriteReq.bits.addr := 0.U
    io.outWriteReq.bits.data := 0.U

    io.transactionAddr := reqAddr
    io.invalidateOutfire := false.B

    tagvRead := tagVArray.read(lookupIndex, lookupValid)
    dataRead := dataArray.read(lookupIndex, lookupValid)
    dirtyRead := dirtyArray.read(lookupIndex, lookupValid)

    switch(state) {
        is(State.statIdle) {
            when(io.inReadReq.valid) {
                reqAddr := io.inReadReq.bits
                state := State.statRead
            }.elsewhen(io.inWriteReq.valid) {
                reqAddr := io.inWriteReq.bits.addr
                writeData := io.inWriteReq.bits.data
                state := State.statWrite
            }.elsewhen(io.invalidate) {
                invalidateState := 0.U
                state := State.statInvalidate
            }
        }

        is(State.statRead) {
            val readTag = reqAddr(config.tagEnd, config.tagStart)
            val readValid = WireInit(false.B)
            val hitWay = WireInit(0.U(log2Ceil(config.wayNum).W))

            for(i <- 0 until config.wayNum) {
                val tagv = tagvRead(i)
                when(tagv.valid && tagv.tag === readTag) {
                    readValid := true.B
                    hitWay := i.U
                }
            }

            when(readValid) {
                io.inReadData.valid := true.B
                io.inReadData.bits := dataRead(hitWay).data

                when(io.inReadReq.valid) {
                    reqAddr := io.inReadReq.bits
                    state := State.statRead
                }.elsewhen(io.inWriteReq.valid) {
                    reqAddr := io.inWriteReq.bits.addr
                    writeData := io.inWriteReq.bits.data
                    state := State.statWrite
                }.otherwise {
                    state := State.statIdle
                }
            }.otherwise {
                state := State.statReadReplace
            }
        }

        is(State.statWrite) {
            val writeTag = reqAddr(config.tagEnd, config.tagStart)
            val writeIndex = reqAddr(config.setEnd, config.setStart)
            val writeValid = WireInit(false.B)
            val hitWay = WireInit(0.U(log2Ceil(config.wayNum).W))

            for(i <- 0 until config.wayNum) {
                val tagv = tagvRead(i)
                when(tagv.valid && tagv.tag === writeTag) {
                    writeValid := true.B
                    hitWay := i.U
                }
            }

            when(writeValid) {
                val newTagV = Wire(Vec(config.wayNum, new CacheTagValid))
                val newData = Wire(Vec(config.wayNum, new CacheData))
                val newDirty = Wire(Vec(config.wayNum, new CacheDirty))

                for (i <- 0 until config.wayNum) {
                    newTagV(i) := tagvRead(i)
                    newData(i) := dataRead(i)
                    newDirty(i) := dirtyRead(i)

                    when(i.U === hitWay) {
                        newData(i).data := writeData
                        newDirty(i).dirty := true.B
                    }
                }

                tagVArray.write(writeIndex, newTagV)
                dataArray.write(writeIndex, newData)
                dirtyArray.write(writeIndex, newDirty)

                io.inWriteResp.valid := true.B
                io.inWriteResp.bits := true.B

                when(io.inReadReq.valid) {
                    reqAddr := io.inReadReq.bits
                    state := State.statRead
                }.elsewhen(io.inWriteReq.valid) {
                    reqAddr := io.inWriteReq.bits.addr
                    writeData := io.inWriteReq.bits.data
                    state := State.statWrite
                }.otherwise {
                    state := State.statIdle
                }
            }.otherwise {
                val way = replacePtr
                when(dirtyRead(way).dirty) {
                    state := State.statWriteBack
                }.otherwise {
                    state := State.statWriteReplace
                }
            }
        }

        is(State.statReadReplace) {
            io.outReadReq.valid := true.B
            io.outReadReq.bits := reqAddr

            when(io.outReadData.valid) {
                val index = reqAddr(config.setEnd, config.setStart)
                val tag = reqAddr(config.tagEnd, config.tagStart)
                val way = replacePtr

                val newTagV = Wire(Vec(config.wayNum, new CacheTagValid))
                val newData = Wire(Vec(config.wayNum, new CacheData))
                val newDirty = Wire(Vec(config.wayNum, new CacheDirty))

                for (i <- 0 until config.wayNum) {
                    newTagV(i) := tagvRead(i)
                    newData(i) := dataRead(i)
                    newDirty(i) := dirtyRead(i)

                    when(i.U === way) {
                        newTagV(i).tag := tag
                        newTagV(i).valid := true.B
                        newData(i).data := io.outReadData.bits
                        newDirty(i).dirty := false.B
                    }
                }

                tagVArray.write(index, newTagV)
                dataArray.write(index, newData)
                dirtyArray.write(index, newDirty)

                replacePtr := replacePtr + 1.U
                io.inReadData.valid := true.B
                io.inReadData.bits := io.outReadData.bits
                state := State.statIdle
            }
        }

        is(State.statWriteBack) {
            val index = reqAddr(config.setEnd, config.setStart)
            val way = replacePtr
            val dirtyTag = tagvRead(way).tag
            val dirtyAddr = Cat(dirtyTag, index, 0.U(config.offsetBits.W))

            io.outWriteReq.valid := true.B
            io.outWriteReq.bits.addr := dirtyAddr
            io.outWriteReq.bits.data := dataRead(way).data

            when(io.outWriteResp.valid) {
                state := State.statWriteReplace
            }
        }

        is(State.statWriteReplace) {
            val index = reqAddr(config.setEnd, config.setStart)
            val tag = reqAddr(config.tagEnd, config.tagStart)
            val way = replacePtr

            val newTagV = Wire(Vec(config.wayNum, new CacheTagValid))
            val newData = Wire(Vec(config.wayNum, new CacheData))
            val newDirty = Wire(Vec(config.wayNum, new CacheDirty))

            for (i <- 0 until config.wayNum) {
                newTagV(i) := tagvRead(i)
                newData(i) := dataRead(i)
                newDirty(i) := dirtyRead(i)

                when(i.U === way) {
                    newTagV(i).tag := tag
                    newTagV(i).valid := true.B
                    newData(i).data := writeData
                    newDirty(i).dirty := true.B
                }
            }

            tagVArray.write(index, newTagV)
            dataArray.write(index, newData)
            dirtyArray.write(index, newDirty)

            replacePtr := replacePtr + 1.U
            io.inWriteResp.valid := true.B
            io.inWriteResp.bits := true.B
            state := State.statIdle
        }

        is(State.statInvalidate) {
            val currentSet = invalidateState
            val invalidateTagV = Vec(config.wayNum, new CacheTagValid()).zero
            val invalidateDirty = Vec(config.wayNum, new CacheDirty()).zero

            tagVArray.write(currentSet, invalidateTagV)
            dirtyArray.write(currentSet, invalidateDirty)

            invalidateState := currentSet + 1.U
            when(currentSet === (config.setNum - 1).U) {
                state := State.statIdle
                io.invalidateOutfire := true.B
            }
        }
    }
}