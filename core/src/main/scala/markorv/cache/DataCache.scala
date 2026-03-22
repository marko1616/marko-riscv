package markorv.cache

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.utils.ConfigUtils._
import markorv.config._
import markorv.bus._
import markorv.cache._

class DataCache(implicit val c: CacheConfig) extends Module {
    // Priority: read > write > clean > invalidate all > clean all
    val io = IO(new Bundle {
        val cacheInterface = new DcacheInterface
        val ioInterface = new IOInterface()(getCacheIoConfig(c, CacheType.Dcache), true)

        val invalidateAll = Input(Bool())
        val invalidateAllOutfire = Output(Bool())
        val cleanAll = Input(Bool())
        val cleanAllOutfire = Output(Bool())
    })
    // TODO handle write back bus error

    object State extends ChiselEnum {
        val sIdle, sRead, sWrite, sReplace, sWriteBack, sInvalidateAll, sCleanAll = Value
    }

    object TransactionType extends ChiselEnum {
        val read, write, clean, invalidate, cleanAll = Value
    }

    val transactionType = Reg(new TransactionType.Type)
    val reqAddr = Reg(UInt(64.W))
    val readData = Reg(UInt((8 * c.dataBytes).W)) // This is for write allocation not for read
    val regHitWay = Reg(UInt(log2Ceil(c.wayNum).W)) // This is for write allocation not for read
    val writeCode = Reg(new CacheCode.Type)
    val writeData = Reg(UInt((8 * c.dataBytes).W))
    val writeMask = Reg(UInt(c.dataBytes.W)) // Bytewise write mask
    val state = RegInit(State.sIdle)
    val invalidateAllSetIdx = RegInit(0.U(c.setBits.W))
    val cleanAllSetIdx  = RegInit(0.U(c.setBits.W))
    val isCleanAllSramReadWait = RegInit(false.B)
    val writeBackPtr = RegInit(0.U(c.wayBits.W))

    val tagVArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheTagValid))
    val dataArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheData))
    val dirtyArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheDirty))

    val tagvRead = Wire(Vec(c.wayNum, new CacheTagValid))
    val dataRead = Wire(Vec(c.wayNum, new CacheData))
    val dirtyRead = Wire(Vec(c.wayNum, new CacheDirty))

    val replaceNewTagV  = Reg(Vec(c.wayNum, new CacheTagValid))
    val replaceNewData  = Reg(Vec(c.wayNum, new CacheData))
    val replaceNewDirty = Reg(Vec(c.wayNum, new CacheDirty))
    val useReplaceBypass = RegInit(false.B)

    val defaultIndex = MuxCase(0.U, Seq(
                        io.cacheInterface.readReq.valid -> io.cacheInterface.readReq.bits.addr(c.setEnd, c.setStart),
                        io.cacheInterface.writeReq.valid -> io.cacheInterface.writeReq.bits.addr(c.setEnd, c.setStart),
                        io.cacheInterface.cleanReq.valid -> io.cacheInterface.cleanReq.bits.addr(c.setEnd, c.setStart),
                        io.cacheInterface.invalidateReq.valid -> io.cacheInterface.invalidateReq.bits.addr(c.setEnd, c.setStart)))

    // There is no need to worrid about SRAM read sync problem in sReplace because bus can't return value in the same cycle as read requests asserted.
    val reqIndex = reqAddr(c.setEnd, c.setStart)
    val isRefillOrWriteback = state === State.sReplace || state === State.sWriteBack
    val useRegisteredAddr   = isRefillOrWriteback || state === State.sRead
    val usecleanAllSetIdx     = state === State.sCleanAll
    val lookupIndex = MuxCase(defaultIndex, Seq(
                            usecleanAllSetIdx  -> cleanAllSetIdx ,
                            useRegisteredAddr -> reqIndex))

    val internalNeedLookup = state === State.sCleanAll || 
                            state === State.sWriteBack || 
                            state === State.sReplace ||
                            state === State.sRead ||
                            state === State.sWrite

    // Although we don't need read it at invalidate stage
    val lookupValid = io.cacheInterface.readReq.valid || io.cacheInterface.writeReq.valid || io.cacheInterface.cleanReq.valid || io.cacheInterface.invalidateReq.valid || io.cleanAll || internalNeedLookup

    io.cacheInterface.readReq.ready := (state === State.sIdle || state === State.sRead)
    io.cacheInterface.writeReq.ready := state === State.sIdle
    io.cacheInterface.cleanReq.ready := state === State.sIdle
    io.cacheInterface.invalidateReq.ready := state === State.sIdle
    io.ioInterface.read.get.resp.ready := (state === State.sReplace)
    io.ioInterface.write.get.resp.ready := state === State.sWriteBack

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

    tagvRead := tagVArray.read(lookupIndex, lookupValid)
    dataRead := dataArray.read(lookupIndex, lookupValid)
    dirtyRead := dirtyArray.read(lookupIndex, lookupValid)

    switch(state) {
        is(State.sIdle) {
            when(io.cacheInterface.readReq.valid) {
                transactionType := TransactionType.read
                reqAddr := io.cacheInterface.readReq.bits.addr
                state := State.sRead
            }.elsewhen(io.cacheInterface.writeReq.valid) {
                transactionType := TransactionType.write
                reqAddr := io.cacheInterface.writeReq.bits.addr
                writeData := io.cacheInterface.writeReq.bits.data
                writeMask := io.cacheInterface.writeReq.bits.mask
                state := State.sRead
            }.elsewhen(io.cacheInterface.cleanReq.valid){
                transactionType := TransactionType.clean
                reqAddr := io.cacheInterface.cleanReq.bits.addr
                state := State.sRead
            }.elsewhen(io.cacheInterface.invalidateReq.valid){
                transactionType := TransactionType.invalidate
                reqAddr := io.cacheInterface.invalidateReq.bits.addr
                state := State.sRead
            }.elsewhen(io.invalidateAll) {
                invalidateAllSetIdx := 0.U
                state := State.sInvalidateAll
            }.elsewhen(io.cleanAll) {
                transactionType := TransactionType.cleanAll
                cleanAllSetIdx  := 0.U
                isCleanAllSramReadWait := false.B
                writeBackPtr := 0.U
                state := State.sCleanAll
            }
        }

        is(State.sRead) {
            val readTag = reqAddr(c.tagEnd, c.tagStart)
            val readValid = WireInit(false.B)
            val hitWay = WireInit(0.U(log2Ceil(c.wayNum).W))
            val pipelineOpReady = WireInit(false.B)

            for(i <- 0 until c.wayNum) {
                val tagv = tagvRead(i)
                when(tagv.valid && tagv.tag === readTag) {
                    readValid := true.B
                    hitWay := i.U
                }
            }

            when(readValid) {
                writeCode := CacheCode.CacheHitOk
                when(transactionType === TransactionType.read) {
                    io.cacheInterface.readResp.valid := true.B
                    io.cacheInterface.readResp.bits.code := CacheCode.CacheHitOk
                    io.cacheInterface.readResp.bits.data := dataRead(hitWay).data
                    pipelineOpReady := true.B
                }.elsewhen(transactionType === TransactionType.clean){
                    when(dirtyRead(hitWay).dirty) {
                        writeBackPtr := hitWay
                        state := State.sWriteBack
                    }.otherwise {
                        io.cacheInterface.cleanResp := true.B
                        pipelineOpReady := true.B
                    }
                }.elsewhen(transactionType === TransactionType.invalidate){
                    val invalidateTagV = WireInit(Vec(c.wayNum, new CacheTagValid()).zero)
                    val invalidateDirty = WireInit(Vec(c.wayNum, new CacheDirty()).zero)
                    for (i <- 0 until c.wayNum) {
                        invalidateTagV(i) := tagvRead(i)
                        invalidateDirty(i) := dirtyRead(i)
                        when(i.U === hitWay) {
                            invalidateTagV(i).valid := false.B
                            invalidateDirty(i).dirty := false.B
                        }
                    }
                    tagVArray.write(reqAddr(c.setEnd, c.setStart), invalidateTagV)
                    dirtyArray.write(reqAddr(c.setEnd, c.setStart), invalidateDirty)
                    io.cacheInterface.invalidateResp := true.B
                    pipelineOpReady := true.B
                }.otherwise {
                    regHitWay := hitWay
                    readData := dataRead(hitWay).data
                    state := State.sWrite
                }
            }.otherwise {
                val replaceWay = writeBackPtr
                when(transactionType === TransactionType.clean) {
                    io.cacheInterface.cleanResp := true.B
                    pipelineOpReady := true.B
                }.elsewhen(transactionType === TransactionType.invalidate) {
                    io.cacheInterface.invalidateResp := true.B
                    pipelineOpReady := true.B
                }.elsewhen(dirtyRead(replaceWay).dirty) {
                    state := State.sWriteBack
                }.otherwise {
                    state := State.sReplace
                }
            }

            when(pipelineOpReady) {
                when(io.cacheInterface.readReq.valid) {
                    transactionType := TransactionType.read
                    reqAddr := io.cacheInterface.readReq.bits.addr
                    state := State.sRead
                }.elsewhen(io.cacheInterface.writeReq.valid) {
                    transactionType := TransactionType.write
                    reqAddr := io.cacheInterface.writeReq.bits.addr
                    writeData := io.cacheInterface.writeReq.bits.data
                    writeMask := io.cacheInterface.writeReq.bits.mask
                    state := State.sRead
                }.otherwise {
                    state := State.sIdle
                }
            }
        }

        is(State.sWrite) {
            val writeTag = reqAddr(c.tagEnd, c.tagStart)
            val writeIndex = reqAddr(c.setEnd, c.setStart)

            val newTagV = Wire(Vec(c.wayNum, new CacheTagValid))
            val newData = Wire(Vec(c.wayNum, new CacheData))
            val newDirty = Wire(Vec(c.wayNum, new CacheDirty))

            for (i <- 0 until c.wayNum) {
                newTagV(i)  := Mux(useReplaceBypass, replaceNewTagV(i),  tagvRead(i))
                newData(i)  := Mux(useReplaceBypass, replaceNewData(i),  dataRead(i))
                newDirty(i) := Mux(useReplaceBypass, replaceNewDirty(i), dirtyRead(i))

                when(i.U === regHitWay) {
                    newTagV(i).valid := writeCode.isOk()
                    val combinedBytes = Wire(Vec(c.dataBytes, UInt(8.W)))
                    for (j <- 0 until c.dataBytes) {
                        val dataMsbIdx = (j + 1) * 8 - 1
                        val dataLsbIdx = j * 8
                        combinedBytes(j) := Mux(writeMask(j), 
                                                writeData(dataMsbIdx, dataLsbIdx), 
                                                readData(dataMsbIdx, dataLsbIdx))
                    }
                    newData(i).data := combinedBytes.asUInt
                    newDirty(i).dirty := true.B
                }
            }

            tagVArray.write(writeIndex, newTagV)
            dataArray.write(writeIndex, newData)
            dirtyArray.write(writeIndex, newDirty)

            io.cacheInterface.writeResp.valid := true.B
            io.cacheInterface.writeResp.bits.code := writeCode

            useReplaceBypass := false.B
            state := State.sIdle
        }

        is(State.sReplace) {
            io.ioInterface.read.get.params.valid := true.B
            io.ioInterface.read.get.params.bits.addr := reqAddr
            io.ioInterface.read.get.params.bits.size := 3.U

            when(io.ioInterface.read.get.resp.valid) {
                val index = reqAddr(c.setEnd, c.setStart)
                val tag = reqAddr(c.tagEnd, c.tagStart)
                val way = writeBackPtr

                val newTagV = Wire(Vec(c.wayNum, new CacheTagValid))
                val newData = Wire(Vec(c.wayNum, new CacheData))
                val newDirty = Wire(Vec(c.wayNum, new CacheDirty))

                for (i <- 0 until c.wayNum) {
                    newTagV(i) := tagvRead(i)
                    newData(i) := dataRead(i)
                    newDirty(i) := dirtyRead(i)

                    when(i.U === way) {
                        newTagV(i).tag := tag
                        newTagV(i).valid := io.ioInterface.read.get.resp.bits.resp.isOk()
                        newData(i).data := io.ioInterface.read.get.resp.bits.data
                        newDirty(i).dirty := false.B
                    }
                }

                replaceNewTagV  := newTagV
                replaceNewData  := newData
                replaceNewDirty := newDirty

                tagVArray.write(index, newTagV)
                dataArray.write(index, newData)
                dirtyArray.write(index, newDirty)

                writeBackPtr := writeBackPtr + 1.U
                when(transactionType === TransactionType.read) {
                    io.cacheInterface.readResp.valid := true.B
                    io.cacheInterface.readResp.bits.code := Mux(io.ioInterface.read.get.resp.bits.resp.isOk(), 
                                                                CacheCode.CacheMissOk, 
                                                                io.ioInterface.read.get.resp.bits.resp.asTypeOf(new CacheCode.Type))
                    io.cacheInterface.readResp.bits.data := io.ioInterface.read.get.resp.bits.data
                    state := State.sIdle
                } otherwise {
                    useReplaceBypass := true.B
                    writeCode := Mux(io.ioInterface.read.get.resp.bits.resp.isOk(), 
                                                                CacheCode.CacheMissOk, 
                                                                io.ioInterface.read.get.resp.bits.resp.asTypeOf(new CacheCode.Type))
                    regHitWay := writeBackPtr
                    readData := io.ioInterface.read.get.resp.bits.data
                    state := State.sWrite
                }
            }
        }

        is(State.sWriteBack) {
            val index = reqAddr(c.setEnd, c.setStart)
            val way = writeBackPtr
            val dirtyTag = tagvRead(way).tag
            val dirtyAddr = Cat(dirtyTag, index, 0.U(c.offsetBits.W))

            io.ioInterface.write.get.params.valid := true.B
            io.ioInterface.write.get.params.bits.addr := dirtyAddr
            io.ioInterface.write.get.params.bits.data := dataRead(way).data
            io.ioInterface.write.get.params.bits.size := 3.U

            when(io.ioInterface.write.get.resp.valid) {
                when(transactionType === TransactionType.clean) {
                    val cleanDirty = Wire(Vec(c.wayNum, new CacheDirty))
                    for (i <- 0 until c.wayNum) {
                        cleanDirty(i) := dirtyRead(i)
                        when(i.U === writeBackPtr) {
                            cleanDirty(i).dirty := false.B
                        }
                    }
                    io.cacheInterface.cleanResp := true.B
                    state := State.sIdle
                }.elsewhen(transactionType === TransactionType.cleanAll) {
                    val cleanDirty = Wire(Vec(c.wayNum, new CacheDirty))
                    for (i <- 0 until c.wayNum) {
                        cleanDirty(i) := dirtyRead(i)
                        when(i.U === writeBackPtr) {
                            cleanDirty(i).dirty := false.B
                        }
                    }
                    dirtyArray.write(reqAddr(c.setEnd, c.setStart), cleanDirty)

                    isCleanAllSramReadWait := true.B
                    if (c.wayBits != 0) {
                        when(~writeBackPtr === 0.U) {
                            cleanAllSetIdx  := cleanAllSetIdx  + 1.U
                            when(cleanAllSetIdx  === (c.setNum - 1).U) {
                                state := State.sIdle
                                io.cleanAllOutfire := true.B
                            }.otherwise {
                                state := State.sCleanAll
                            }
                        }.otherwise {
                            state := State.sCleanAll
                        }
                    } else {
                        cleanAllSetIdx  := cleanAllSetIdx  + 1.U
                        when(cleanAllSetIdx  === (c.setNum - 1).U) {
                            state := State.sIdle
                            io.cleanAllOutfire := true.B
                        }.otherwise {
                            state := State.sCleanAll
                        }
                    }
                }.otherwise {
                    state := State.sReplace
                }
            }
        }

        is(State.sInvalidateAll) {
            val currentSet = invalidateAllSetIdx
            val invalidateTagV = Vec(c.wayNum, new CacheTagValid()).zero
            val invalidateDirty = Vec(c.wayNum, new CacheDirty()).zero

            tagVArray.write(currentSet, invalidateTagV)
            dirtyArray.write(currentSet, invalidateDirty)

            invalidateAllSetIdx := currentSet + 1.U
            when(currentSet === (c.setNum - 1).U) {
                state := State.sIdle
                io.invalidateAllOutfire := true.B
            }
        }

        is(State.sCleanAll) {
            val currentSet = cleanAllSetIdx 

            when(isCleanAllSramReadWait) {
                isCleanAllSramReadWait := false.B
                if (c.wayBits != 0) {
                    writeBackPtr := writeBackPtr + 1.U
                }
            }.otherwise {
                if (c.wayBits != 0) {
                    when(dirtyRead(writeBackPtr).dirty) {
                        reqAddr := currentSet << c.setStart
                        state := State.sWriteBack
                    }.otherwise {
                        isCleanAllSramReadWait := true.B
                        when(~writeBackPtr === 0.U) {
                            cleanAllSetIdx  := currentSet + 1.U
                            when(cleanAllSetIdx  === (c.setNum - 1).U) {
                                state := State.sIdle
                                io.cleanAllOutfire := true.B
                            }.otherwise {
                                state := State.sCleanAll
                            }
                        }.otherwise {
                            state := State.sCleanAll
                        }
                    }
                } else {
                    when(dirtyRead(writeBackPtr).dirty) {
                        reqAddr := currentSet << c.setStart
                        state := State.sWriteBack
                    }.otherwise {
                        isCleanAllSramReadWait := true.B
                        cleanAllSetIdx  := currentSet + 1.U
                        when(cleanAllSetIdx  === (c.setNum - 1).U) {
                            state := State.sIdle
                            io.cleanAllOutfire := true.B
                        }.otherwise {
                            state := State.sCleanAll
                        }
                    }
                }
            }
        }
    }
}