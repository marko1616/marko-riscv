package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.cache._

object FetchTarget extends ChiselEnum {
    val curr = Value(0.U)
    val pref = Value(1.U)
}

class CacheLine(implicit val config: CoreConfig) extends Bundle {
    val valid = Bool()
    val addr  = UInt(64.W)
    val code = new ICacheCode.Type
    val data  = UInt((8 * config.icacheConfig.dataBytes).W)
}

class InstrPrefetchUnit(implicit val config: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val fetchPc        = Input(UInt(64.W))
        val fetched        = Valid(new PreFetchedLine)
        val cacheInterface = Flipped(new IcacheInterface()(config.icacheConfig))
        val flush          = Input(Bool())
    })

    object State extends ChiselEnum {
        val sIdle, sWaitResp, sDrain = Value
    }

    val currCacheline = RegInit(new CacheLine().zero)
    val prefCacheline = RegInit(new CacheLine().zero)
    val state         = RegInit(State.sIdle)

    val pendingAddr   = Reg(UInt(64.W))
    val pendingTarget = Reg(FetchTarget())

    val cachelineBytes = config.icacheConfig.dataBytes
    val maskedPc       = io.fetchPc & config.icacheConfig.offsetMask
    val nextLinePc     = maskedPc + cachelineBytes.U

    val currValid   = currCacheline.valid && currCacheline.addr === maskedPc
    val prefValid   = prefCacheline.valid && prefCacheline.addr === maskedPc
    val fetchTarget = Mux(currValid, FetchTarget.pref, FetchTarget.curr)

    val pendingStale = Mux(
        pendingTarget === FetchTarget.curr,
        pendingAddr =/= maskedPc,
        pendingAddr =/= nextLinePc
    )

    // TODO Fetch access error / page fault
    io.fetched.valid                 := false.B
    io.fetched.bits                  := new PreFetchedLine().zero
    io.cacheInterface.readReq.valid  := false.B
    io.cacheInterface.readReq.bits   := new ICacheReadReq().zero
    io.cacheInterface.readResp.ready := false.B

    switch(state) {
        is(State.sIdle) {
            switch(fetchTarget) {
                is(FetchTarget.curr) {
                    when(prefValid) {
                        currCacheline := prefCacheline
                        prefCacheline.valid := false.B

                        io.fetched.valid := true.B
                        io.fetched.bits.data  := prefCacheline.data
                        io.fetched.bits.code  := prefCacheline.code
                    }.otherwise {
                        io.cacheInterface.readReq.valid     := true.B
                        io.cacheInterface.readReq.bits.vaddr := maskedPc

                        when(io.cacheInterface.readReq.ready) {
                            pendingAddr   := maskedPc
                            pendingTarget := FetchTarget.curr
                            state         := State.sWaitResp
                        }
                    }
                }
                is(FetchTarget.pref) {
                    io.fetched.valid := true.B
                    io.fetched.bits.data  := currCacheline.data
                    io.fetched.bits.code  := currCacheline.code

                    // Speculatively prefetch the next sequential cacheline
                    val needPrefetch = !prefCacheline.valid ||
                                       prefCacheline.addr =/= nextLinePc

                    when(needPrefetch) {
                        io.cacheInterface.readReq.valid     := true.B
                        io.cacheInterface.readReq.bits.vaddr := nextLinePc

                        when(io.cacheInterface.readReq.ready) {
                            pendingAddr   := nextLinePc
                            pendingTarget := FetchTarget.pref
                            state         := State.sWaitResp
                        }
                    }
                }
            }
        }

        is(State.sWaitResp) {
            io.cacheInterface.readResp.ready := true.B

            when(currValid) {
                io.fetched.valid := true.B
                io.fetched.bits.data  := currCacheline.data
                io.fetched.bits.code  := currCacheline.code
            }

            when(io.cacheInterface.readResp.valid) {
                val respData = io.cacheInterface.readResp.bits.data
                val respCode = io.cacheInterface.readResp.bits.code

                when(pendingStale) {
                    prefCacheline.valid := true.B
                    prefCacheline.addr  := pendingAddr
                    prefCacheline.code  := respCode
                    prefCacheline.data  := respData
                }.otherwise {
                    when(pendingTarget === FetchTarget.curr) {
                        currCacheline.valid := true.B
                        currCacheline.addr  := pendingAddr
                        currCacheline.code  := respCode
                        currCacheline.data  := respData

                        io.fetched.valid := true.B
                        io.fetched.bits.code  := respCode
                        io.fetched.bits.data  := respData
                    }.otherwise {
                        prefCacheline.valid := true.B
                        prefCacheline.addr  := pendingAddr
                        prefCacheline.code  := respCode
                        prefCacheline.data  := respData
                    }
                }
                state := State.sIdle
            }
        }

        is(State.sDrain) {
            io.cacheInterface.readResp.ready := true.B

            when(io.cacheInterface.readResp.valid) {
                state := State.sIdle
            }
        }
    }

    when(io.flush) {
        currCacheline.valid := false.B
        prefCacheline.valid := false.B
        io.cacheInterface.readReq.valid := false.B

        when(state === State.sWaitResp && !io.cacheInterface.readResp.valid) {
            state := State.sDrain
        }.otherwise {
            state := State.sIdle
        }
    }
}
