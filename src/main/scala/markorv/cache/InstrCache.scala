package markorv.cache

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.bus._
import markorv.cache._

class InstrCache(implicit val config: CacheConfig) extends Module {
    val io = IO(new Bundle {
        val inReadReq = Flipped(Decoupled(UInt(64.W)))
        val inReadData = Decoupled(UInt((8 * config.dataBytes).W))

        val outReadReq = Decoupled(UInt(64.W))
        val outReadData = Flipped(Decoupled(UInt((8 * config.dataBytes).W)))

        val transactionAddr = Output(UInt(64.W))
        val invalidate = Input(Bool())
        val invalidateOutfire = Output(Bool())
    })
    object State extends ChiselEnum {
        val statIdle, statRead, statReplace, statInvalidate = Value
    }

    val readAddr = Reg(UInt(64.W))

    val state = RegInit(State.statIdle)
    val invalidateState = Reg(UInt(config.setBits.W))
    val replacePtr = Reg(UInt(log2Ceil(config.wayNum).W))

    val tagVArray = SyncReadMem(config.setNum, Vec(config.wayNum,new CacheTagValid))
    val dataArray = SyncReadMem(config.setNum, Vec(config.wayNum,new CacheData))

    val tagvRead = Wire(Vec(config.wayNum,new CacheTagValid))
    val dataRead = Wire(Vec(config.wayNum,new CacheData))
    val lookupIndex = Mux(state === State.statReplace,readAddr(config.setEnd,config.setStart),io.inReadReq.bits(config.setEnd,config.setStart))
    val lookupValid = io.inReadReq.valid && (state === State.statIdle || state === State.statRead || state === State.statReplace)

    io.inReadReq.ready := (state === State.statIdle || state === State.statRead)
    io.outReadData.ready := state === State.statReplace

    io.inReadData.valid := false.B
    io.outReadReq.valid := false.B
    io.inReadData.bits := 0.U
    io.outReadReq.bits := 0.U
    io.transactionAddr := readAddr

    io.invalidateOutfire := false.B

    tagvRead := tagVArray.read(lookupIndex,lookupValid)
    dataRead := dataArray.read(lookupIndex,lookupValid)

    switch(state) {
        is(State.statIdle) {
            when(io.inReadReq.valid) {
                readAddr := io.inReadReq.bits
                state := State.statRead
            }
            when(io.invalidate) {
                invalidateState := 0.U
                state := State.statInvalidate
            }
        }
        is(State.statRead) {
            val readTag = readAddr(config.tagEnd,config.tagStart)
            val readValid = WireInit(false.B)
            for(i <- 0 until config.wayNum) {
                val tagv = tagvRead(i)
                when(tagv.valid && tagv.tag === readTag) {
                    readValid := true.B
                    io.inReadData.valid := true.B
                    io.inReadData.bits := dataRead(i).data
                    when(io.inReadReq.valid) {
                        readAddr := io.inReadReq.bits
                        state := State.statRead
                    }.otherwise {
                        state := State.statIdle
                    }
                }
            }
            when(!readValid) {
                state := State.statReplace
            }
            when(io.invalidate) {
                invalidateState := 0.U
                state := State.statInvalidate
            }
        }
        is(State.statReplace) {
            io.outReadReq.valid := true.B
            io.outReadReq.bits := readAddr
            when(io.outReadData.valid) {
                val index = readAddr(config.setEnd, config.setStart)
                val tag = readAddr(config.tagEnd, config.tagStart)
                val way = replacePtr

                val newTagV = Wire(Vec(config.wayNum, new CacheTagValid))
                val newData = Wire(Vec(config.wayNum, new CacheData))

                for (i <- 0 until config.wayNum) {
                    newTagV(i) := tagvRead(i)
                    newData(i) := dataRead(i)
                    when(i.U === way) {
                        newTagV(i).tag := tag
                        newTagV(i).valid := true.B
                        newData(i).data := io.outReadData.bits
                    }
                }

                tagVArray.write(index, newTagV)
                dataArray.write(index, newData)

                replacePtr := replacePtr + 1.U
                io.inReadData.valid := true.B
                io.inReadData.bits := io.outReadData.bits
                state := State.statIdle
            }
        }
        is(State.statInvalidate) {
            val currentSet = invalidateState
            val invalidateTagV = Vec(config.wayNum, new CacheTagValid()).zero
            tagVArray.write(currentSet, invalidateTagV)
            invalidateState := currentSet + 1.U
            when(currentSet === (config.setNum - 1).U) {
                state := State.statIdle
                io.invalidateOutfire := true.B
            }
        }
    }
}
