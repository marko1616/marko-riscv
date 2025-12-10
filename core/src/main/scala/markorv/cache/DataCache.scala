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

        val transactionAddr = Output(UInt(64.W))
        val invalidateAll = Input(Bool())
        val invalidateAllOutfire = Output(Bool())
        val cleanAll = Input(Bool())
        val cleanAllOutfire = Output(Bool())
    })
    // TODO handle write back bus error

    object State extends ChiselEnum {
        val statIdle, statRead, statWrite, statReadReplace, statWriteBack, statInvalidateAll, statCleanAll = Value
    }

    object TransactionType extends ChiselEnum {
        val read, write, clean, cleanAll = Value
    }

    val transactionType = Reg(new TransactionType.Type)
    val reqAddr = Reg(UInt(64.W))
    val readData = Reg(UInt((8 * c.dataBytes).W)) // This is for write allocation not for read
    val regHitWay = Reg(UInt(log2Ceil(c.wayNum).W)) // This is for write allocation not for read
    val writeCode = Reg(new CacheCode.Type)
    val writeData = Reg(UInt((8 * c.dataBytes).W))
    val writeMask = Reg(UInt(c.dataBytes.W)) // Bytewise write mask
    val state = RegInit(State.statIdle)
    val invalidateAllState = RegInit(0.U(c.setBits.W))
    val cleanAllState = RegInit(0.U(c.setBits.W))
    val cleanAllWriteBackState = RegInit(false.B)
    val replacePtr = RegInit(0.U(c.wayBits.W))

    val tagVArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheTagValid))
    val dataArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheData))
    val dirtyArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheDirty))

    val tagvRead = Wire(Vec(c.wayNum, new CacheTagValid))
    val dataRead = Wire(Vec(c.wayNum, new CacheData))
    val dirtyRead = Wire(Vec(c.wayNum, new CacheDirty))

    val defaultIndex = MuxCase(0.U, Seq(
                        io.cacheInterface.readReq.valid -> io.cacheInterface.readReq.bits.addr(c.setEnd, c.setStart),
                        io.cacheInterface.writeReq.valid -> io.cacheInterface.writeReq.bits.addr(c.setEnd, c.setStart)))

    // There is no need to worrid about SRAM read sync problem in statReadReplace because bus can't return value in the same cycle as read requests asserted.
    val reqIndex = reqAddr(c.setEnd, c.setStart)
    val isRefillOrWriteback = state === State.statReadReplace || state === State.statWriteBack
    val isWriteOrClean      = transactionType === TransactionType.write || transactionType === TransactionType.clean
    val isWriteUpdate       = state === State.statRead && isWriteOrClean
    val useRegisteredAddr   = isRefillOrWriteback || isWriteUpdate
    val useCleanAllState    = state === State.statCleanAll
    val lookupIndex = MuxCase(defaultIndex, Seq(
                            useCleanAllState -> cleanAllState,
                            useRegisteredAddr -> reqIndex))

    // Although we don't need read it at invalidate stage
    val lookupValid = io.cacheInterface.readReq.valid || io.cacheInterface.writeReq.valid || io.cacheInterface.cleanReq.valid || io.cleanAll

    io.cacheInterface.readReq.ready := (state === State.statIdle || state === State.statRead)
    io.cacheInterface.writeReq.ready := state === State.statIdle
    io.cacheInterface.cleanReq.ready := state === State.statIdle
    io.ioInterface.read.get.resp.ready := (state === State.statReadReplace)
    io.ioInterface.write.get.resp.ready := state === State.statWriteBack

    io.cacheInterface.readResp.valid := false.B
    io.cacheInterface.readResp.bits := new CacheReadResp().zero
    io.cacheInterface.writeResp.valid := false.B
    io.cacheInterface.writeResp.bits := new CacheWriteResp().zero
    io.cacheInterface.cleanResp := false.B

    io.ioInterface.read.get.params.valid := false.B
    io.ioInterface.read.get.params.bits := new ReadParams()(using getCacheIoConfig(c, CacheType.Dcache)).zero
    io.ioInterface.write.get.params.valid := false.B
    io.ioInterface.write.get.params.bits := new WriteParams()(using getCacheIoConfig(c, CacheType.Dcache)).zero

    io.transactionAddr := reqAddr
    io.invalidateAllOutfire := false.B
    io.cleanAllOutfire := false.B

    tagvRead := tagVArray.read(lookupIndex, lookupValid)
    dataRead := dataArray.read(lookupIndex, lookupValid)
    dirtyRead := dirtyArray.read(lookupIndex, lookupValid)

    switch(state) {
        is(State.statIdle) {
            when(io.cacheInterface.readReq.valid) {
                transactionType := TransactionType.read
                reqAddr := io.cacheInterface.readReq.bits.addr
                state := State.statRead
            }.elsewhen(io.cacheInterface.writeReq.valid) {
                transactionType := TransactionType.write
                reqAddr := io.cacheInterface.writeReq.bits.addr
                writeData := io.cacheInterface.writeReq.bits.data
                writeMask := io.cacheInterface.writeReq.bits.mask
                state := State.statRead
            }.elsewhen(io.cacheInterface.cleanReq.valid){
                transactionType := TransactionType.clean
                reqAddr := io.cacheInterface.cleanReq.bits.addr
                state := State.statRead
            }.elsewhen(io.invalidateAll) {
                invalidateAllState := 0.U
                state := State.statInvalidateAll
            }.elsewhen(io.cleanAll) {
                transactionType := TransactionType.cleanAll
                cleanAllState := 0.U
                cleanAllWriteBackState := false.B
                replacePtr := 0.U
                state := State.statCleanAll
            }
        }

        is(State.statRead) {
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
                        state := State.statWriteBack
                    }.otherwise {
                        pipelineOpReady := true.B
                    }
                }.otherwise {
                    regHitWay := hitWay
                    readData := dataRead(hitWay).data
                    state := State.statWrite
                }
            }.otherwise {
                val replaceWay = replacePtr
                when(transactionType === TransactionType.clean) {
                    pipelineOpReady := true.B
                }.elsewhen(dirtyRead(replaceWay).dirty) {
                    state := State.statWriteBack
                }.otherwise {
                    state := State.statReadReplace
                }
            }

            when(pipelineOpReady) {
                when(io.cacheInterface.readReq.valid) {
                    transactionType := TransactionType.read
                    reqAddr := io.cacheInterface.readReq.bits.addr
                    state := State.statRead
                }.elsewhen(io.cacheInterface.writeReq.valid) {
                    transactionType := TransactionType.write
                    reqAddr := io.cacheInterface.writeReq.bits.addr
                    writeData := io.cacheInterface.writeReq.bits.data
                    writeMask := io.cacheInterface.writeReq.bits.mask
                    state := State.statRead
                }.otherwise {
                    state := State.statIdle
                }
            }
        }

        is(State.statWrite) {
            val writeTag = reqAddr(c.tagEnd, c.tagStart)
            val writeIndex = reqAddr(c.setEnd, c.setStart)

            val newTagV = Wire(Vec(c.wayNum, new CacheTagValid))
            val newData = Wire(Vec(c.wayNum, new CacheData))
            val newDirty = Wire(Vec(c.wayNum, new CacheDirty))

            for (i <- 0 until c.wayNum) {
                newTagV(i) := tagvRead(i)
                newData(i) := dataRead(i)
                newDirty(i) := dirtyRead(i)

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
            state := State.statIdle
        }

        is(State.statReadReplace) {
            io.ioInterface.read.get.params.valid := true.B
            io.ioInterface.read.get.params.bits.addr := reqAddr
            io.ioInterface.read.get.params.bits.size := 3.U

            when(io.ioInterface.read.get.resp.valid) {
                val index = reqAddr(c.setEnd, c.setStart)
                val tag = reqAddr(c.tagEnd, c.tagStart)
                val way = replacePtr

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

                tagVArray.write(index, newTagV)
                dataArray.write(index, newData)
                dirtyArray.write(index, newDirty)

                replacePtr := replacePtr + 1.U
                when(transactionType === TransactionType.read) {
                    io.cacheInterface.readResp.valid := true.B
                    io.cacheInterface.readResp.bits.code := Mux(io.ioInterface.read.get.resp.bits.resp.isOk(), 
                                                                CacheCode.CacheMissOk, 
                                                                io.ioInterface.read.get.resp.bits.resp.asTypeOf(new CacheCode.Type))
                    io.cacheInterface.readResp.bits.data := io.ioInterface.read.get.resp.bits.data
                    state := State.statIdle
                } otherwise {
                    writeCode := Mux(io.ioInterface.read.get.resp.bits.resp.isOk(), 
                                                                CacheCode.CacheMissOk, 
                                                                io.ioInterface.read.get.resp.bits.resp.asTypeOf(new CacheCode.Type))
                    regHitWay := replacePtr
                    readData := io.ioInterface.read.get.resp.bits.data
                    state := State.statWrite
                }
            }
        }

        is(State.statWriteBack) {
            val index = reqAddr(c.setEnd, c.setStart)
            val way = replacePtr
            val dirtyTag = tagvRead(way).tag
            val dirtyAddr = Cat(dirtyTag, index, 0.U(c.offsetBits.W))

            io.ioInterface.write.get.params.valid := true.B
            io.ioInterface.write.get.params.bits.addr := dirtyAddr
            io.ioInterface.write.get.params.bits.data := dataRead(way).data
            io.ioInterface.write.get.params.bits.size := 3.U

            when(io.ioInterface.write.get.resp.valid) {
                when(transactionType === TransactionType.clean) {
                    state := State.statIdle
                }.elsewhen(transactionType === TransactionType.cleanAll) {
                    cleanAllWriteBackState := true.B
                    if (c.wayBits != 0) {
                        when(replacePtr === (-1.S(c.wayBits.U)).asUInt) {
                            cleanAllState := cleanAllState + 1.U
                            when(cleanAllState === (c.setNum - 1).U) {
                                state := State.statIdle
                                io.cleanAllOutfire := true.B
                            }.otherwise {
                                state := State.statCleanAll
                            }
                        }.otherwise {
                            state := State.statCleanAll
                        }
                    } else {
                        cleanAllState := cleanAllState + 1.U
                        when(cleanAllState === (c.setNum - 1).U) {
                            state := State.statIdle
                            io.cleanAllOutfire := true.B
                        }.otherwise {
                            state := State.statCleanAll
                        }
                    }
                }.otherwise {
                    state := State.statReadReplace
                }
            }
        }

        is(State.statInvalidateAll) {
            val currentSet = invalidateAllState
            val invalidateTagV = Vec(c.wayNum, new CacheTagValid()).zero
            val invalidateDirty = Vec(c.wayNum, new CacheDirty()).zero

            tagVArray.write(currentSet, invalidateTagV)
            dirtyArray.write(currentSet, invalidateDirty)

            invalidateAllState := currentSet + 1.U
            when(currentSet === (c.setNum - 1).U) {
                state := State.statIdle
                io.invalidateAllOutfire := true.B
            }
        }

        is(State.statCleanAll) {
            val currentSet = cleanAllState

            when(cleanAllWriteBackState) {
                cleanAllWriteBackState := false.B
                if (c.wayBits != 0) {
                    replacePtr := replacePtr + 1.U
                }
            }.otherwise {
                if (c.wayBits != 0) {
                    when(dirtyRead(replacePtr).dirty) {
                        reqAddr := currentSet << c.setStart.U
                        state := State.statWriteBack
                    }.otherwise {
                        cleanAllWriteBackState := true.B
                        when(replacePtr === (-1.S(c.wayBits.U)).asUInt) {
                            cleanAllState := currentSet + 1.U
                            when(cleanAllState === (c.setNum - 1).U) {
                                state := State.statIdle
                                io.cleanAllOutfire := true.B
                            }.otherwise {
                                state := State.statCleanAll
                            }
                        }.otherwise {
                            state := State.statCleanAll
                        }
                    }
                } else {
                    when(dirtyRead(replacePtr).dirty) {
                        reqAddr := currentSet << c.setStart.U
                        state := State.statWriteBack
                    }.otherwise {
                        cleanAllWriteBackState := true.B
                        cleanAllState := currentSet + 1.U
                        when(cleanAllState === (c.setNum - 1).U) {
                            state := State.statIdle
                            io.cleanAllOutfire := true.B
                        }.otherwise {
                            state := State.statCleanAll
                        }
                    }
                }
            }
        }
    }
}