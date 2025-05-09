package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._

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

        val readAddr = Decoupled(UInt(64.W))
        val readData = Flipped(Decoupled(UInt((8 * config.icacheConfig.dataBytes).W)))
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
    io.readAddr.valid := false.B
    io.readAddr.bits := 0.U
    io.readData.ready := false.B

    switch(fetchTarget) {
        is(FetchTarget.curr) {
            when(prefValid) {
                // Swap current and prefetch lines, and use prefetched data
                currCacheline := prefCacheline
                prefCacheline.valid := false.B

                io.fetched.valid := true.B
                io.fetched.bits := prefCacheline.data
            }.otherwise {
                // Prefetch not valid, request from upstream
                io.readAddr.valid := true.B
                io.readAddr.bits := maskedPc
                // Prevent flush
                val readValid = io.readData.valid && io.transactionAddr === maskedPc
                when(readValid) {
                    io.fetched.valid := true.B
                    io.fetched.bits := io.readData.bits

                    currCacheline.valid := true.B
                    currCacheline.addr := maskedPc
                    currCacheline.data := io.readData.bits
                }
            }
        }
        is(FetchTarget.pref) {
            io.fetched.valid := true.B
            io.fetched.bits := currCacheline.data

            val nextPc = maskedPc + (1.U << config.icacheConfig.offsetBits)
            val needPrefetch = !prefCacheline.valid || prefCacheline.addr =/= nextPc

            when(needPrefetch) {
                io.readAddr.valid := true.B
                io.readAddr.bits := nextPc
                // Prevent flush
                val readValid = io.readData.valid && io.transactionAddr === nextPc
                when(readValid) {
                    prefCacheline.valid := true.B
                    prefCacheline.addr := nextPc
                    prefCacheline.data := io.readData.bits
                }
            }
        }
    }

    when(io.flush) {
        currCacheline.valid := false.B
        prefCacheline.valid := false.B
    }
}
