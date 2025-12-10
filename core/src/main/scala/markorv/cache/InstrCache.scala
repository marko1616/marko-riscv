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
        val ioInterface = new IOInterface()(getCacheIoConfig(c, CacheType.Icache),true)

        val transactionAddr = Output(UInt(64.W))
        val invalidateAll = Input(Bool())
        val invalidateAllOutfire = Output(Bool())
    })
    object State extends ChiselEnum {
        val statIdle, statRead, statReplace, statInvalidate = Value
    }

    val readAddr = Reg(UInt(64.W))

    val state = RegInit(State.statIdle)
    val invalidateAllSetIdx = Reg(UInt(c.setBits.W))
    val replacePtr = Reg(UInt(log2Ceil(c.wayNum).W))

    val tagVArray = SyncReadMem(c.setNum, Vec(c.wayNum,new CacheTagValid))
    val dataArray = SyncReadMem(c.setNum, Vec(c.wayNum,new CacheData))

    val tagvRead = Wire(Vec(c.wayNum,new CacheTagValid))
    val dataRead = Wire(Vec(c.wayNum,new CacheData))
    val lookupIndex = Mux(state === State.statReplace,readAddr(c.setEnd,c.setStart),io.cacheInterface.readReq.bits.addr(c.setEnd,c.setStart))
    val lookupValid = io.cacheInterface.readReq.valid && (state === State.statIdle || state === State.statRead || state === State.statReplace)

    io.cacheInterface.readReq.ready := (state === State.statIdle || state === State.statRead)
    io.ioInterface.read.get.resp.ready := state === State.statReplace

    io.cacheInterface.readResp.valid := false.B
    io.cacheInterface.readResp.bits := new CacheReadResp().zero
    io.ioInterface.read.get.params.valid := false.B
    io.ioInterface.read.get.params.bits := new ReadParams()(using getCacheIoConfig(c, CacheType.Dcache)).zero
    io.transactionAddr := readAddr

    io.invalidateAllOutfire := false.B

    tagvRead := tagVArray.read(lookupIndex,lookupValid)
    dataRead := dataArray.read(lookupIndex,lookupValid)

    switch(state) {
        is(State.statIdle) {
            when(io.cacheInterface.readReq.valid) {
                readAddr := io.cacheInterface.readReq.bits.addr
                state := State.statRead
            }
            when(io.invalidateAll) {
                invalidateAllSetIdx := 0.U
                state := State.statInvalidate
            }
        }
        is(State.statRead) {
            val readTag = readAddr(c.tagEnd,c.tagStart)
            val readValid = WireInit(false.B)
            for(i <- 0 until c.wayNum) {
                val tagv = tagvRead(i)
                when(tagv.valid && tagv.tag === readTag) {
                    readValid := true.B
                    io.cacheInterface.readResp.valid := true.B
                    io.cacheInterface.readResp.bits.code := CacheCode.CacheHitOk
                    io.cacheInterface.readResp.bits.data := dataRead(i).data
                    when(io.cacheInterface.readReq.valid) {
                        readAddr := io.cacheInterface.readReq.bits.addr
                        state := State.statRead
                    }.otherwise {
                        state := State.statIdle
                    }
                }
            }
            when(!readValid) {
                state := State.statReplace
            }
            when(io.invalidateAll) {
                invalidateAllSetIdx := 0.U
                state := State.statInvalidate
            }
        }
        is(State.statReplace) {
            io.ioInterface.read.get.params.valid := true.B
            io.ioInterface.read.get.params.bits.addr := readAddr
            io.ioInterface.read.get.params.bits.size := 3.U
            when(io.ioInterface.read.get.resp.valid) {
                val index = readAddr(c.setEnd, c.setStart)
                val tag = readAddr(c.tagEnd, c.tagStart)
                val way = replacePtr

                val newTagV = Wire(Vec(c.wayNum, new CacheTagValid))
                val newData = Wire(Vec(c.wayNum, new CacheData))

                for (i <- 0 until c.wayNum) {
                    newTagV(i) := tagvRead(i)
                    newData(i) := dataRead(i)
                    when(i.U === way) {
                        newTagV(i).tag := tag
                        newTagV(i).valid := io.ioInterface.read.get.resp.bits.resp.isOk()
                        newData(i).data := io.ioInterface.read.get.resp.bits.data
                    }
                }

                tagVArray.write(index, newTagV)
                dataArray.write(index, newData)

                replacePtr := replacePtr + 1.U
                io.cacheInterface.readResp.valid := true.B
                io.cacheInterface.readResp.bits.code := Mux(io.ioInterface.read.get.resp.bits.resp.isOk(),
                                                            CacheCode.CacheMissOk,
                                                            io.ioInterface.read.get.resp.bits.resp.asTypeOf(new CacheCode.Type))
                io.cacheInterface.readResp.bits.data := io.ioInterface.read.get.resp.bits.data
                state := State.statIdle
            }
        }
        is(State.statInvalidate) {
            val currentSet = invalidateAllSetIdx
            val invalidateTagV = Vec(c.wayNum, new CacheTagValid()).zero
            tagVArray.write(currentSet, invalidateTagV)
            invalidateAllSetIdx := currentSet + 1.U
            when(currentSet === (c.setNum - 1).U) {
                state := State.statIdle
                io.invalidateAllOutfire := true.B
            }
        }
    }
}
