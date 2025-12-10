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
    val addr = UInt(64.W)
    val data = UInt((8 * config.icacheConfig.dataBytes).W)
}

class InstrPrefetchUnit(implicit val config: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val fetchPc = Input(UInt(64.W))
        val fetched = Decoupled(UInt((8 * config.icacheConfig.dataBytes).W))

        val cacheInterface = Flipped(new IcacheInterface()(config.icacheConfig))
        val transactionAddr = Input(UInt(64.W))

        val flush = Input(Bool())
    })
    val currCacheline = RegInit(new CacheLine().zero)
    val prefCacheline = RegInit(new CacheLine().zero)

    val maskedPc = io.fetchPc & config.icacheConfig.offsetMask

    val currValid = currCacheline.valid && currCacheline.addr === maskedPc
    val prefValid = prefCacheline.valid && prefCacheline.addr === maskedPc

    val fetchTarget = Mux(currValid, FetchTarget.pref, FetchTarget.curr)

    io.fetched.valid := false.B
    io.fetched.bits := 0.U
    
    // Default Interface Values
    io.cacheInterface.readReq.valid := false.B
    io.cacheInterface.readReq.bits := new CacheReadReq().zero
    io.cacheInterface.readResp.ready := false.B

    switch(fetchTarget) {
        is(FetchTarget.curr) {
            when(prefValid) {
                // Swap current and prefetch lines, and use prefetched data
                currCacheline := prefCacheline
                prefCacheline.valid := false.B

                io.fetched.valid := true.B
                io.fetched.bits := prefCacheline.data
            }.otherwise {
                io.cacheInterface.readReq.valid := true.B
                io.cacheInterface.readReq.bits.addr := maskedPc
                io.cacheInterface.readResp.ready := true.B

                // Check validity and address match
                val readValid = io.cacheInterface.readResp.valid && io.transactionAddr === maskedPc
                when(readValid) {
                    io.fetched.valid := true.B
                    io.fetched.bits := io.cacheInterface.readResp.bits.data

                    currCacheline.valid := true.B
                    currCacheline.addr := maskedPc
                    currCacheline.data := io.cacheInterface.readResp.bits.data
                }
            }
        }
        is(FetchTarget.pref) {
            io.fetched.valid := true.B
            io.fetched.bits := currCacheline.data

            val nextPc = maskedPc + (1.U << config.icacheConfig.offsetBits)
            val needPrefetch = !prefCacheline.valid || prefCacheline.addr =/= nextPc

            when(needPrefetch) {
                io.cacheInterface.readReq.valid := true.B
                io.cacheInterface.readReq.bits.addr := nextPc
                io.cacheInterface.readResp.ready := true.B

                val readValid = io.cacheInterface.readResp.valid && io.transactionAddr === nextPc
                when(readValid) {
                    prefCacheline.valid := true.B
                    prefCacheline.addr := nextPc
                    prefCacheline.data := io.cacheInterface.readResp.bits.data
                }
            }
        }
    }

    when(io.flush) {
        currCacheline.valid := false.B
        prefCacheline.valid := false.B
    }
}
