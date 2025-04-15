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
    val data = UInt((8 * config.icache_config.data_bytes).W)
}

class InstrPrefetchUnit(implicit val config: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val fetch_pc = Input(UInt(64.W))
        val fetched = Decoupled(UInt((8 * config.icache_config.data_bytes).W))

        val read_addr = Decoupled(UInt(64.W))
        val read_data = Flipped(Decoupled(UInt((8 * config.icache_config.data_bytes).W)))
        val transaction_addr = Input(UInt(64.W))

        val flush = Input(Bool())
    })
    val curr_cacheline = RegInit(new CacheLine().zero)
    val pref_cacheline = RegInit(new CacheLine().zero)

    val masked_pc = io.fetch_pc & config.icache_config.offset_mask

    val curr_valid = curr_cacheline.valid && curr_cacheline.addr === masked_pc
    val pref_valid = pref_cacheline.valid && pref_cacheline.addr === masked_pc

    val fetch_target = Mux(curr_valid, FetchTarget.pref, FetchTarget.curr)

    io.fetched.valid := false.B
    io.fetched.bits := 0.U
    io.read_addr.valid := false.B
    io.read_addr.bits := 0.U
    io.read_data.ready := false.B
    
    switch(fetch_target) {
        is(FetchTarget.curr) {
            when(pref_valid) {
                // Swap current and prefetch lines, and use prefetched data
                curr_cacheline := pref_cacheline
                pref_cacheline.valid := false.B

                io.fetched.valid := true.B
                io.fetched.bits := pref_cacheline.data
            }.otherwise {
                // Prefetch not valid, request from upstream
                io.read_addr.valid := true.B
                io.read_addr.bits := masked_pc
                // Prevent flush
                val read_valid = io.read_data.valid && io.transaction_addr === masked_pc
                when(read_valid) {
                    io.fetched.valid := true.B
                    io.fetched.bits := io.read_data.bits

                    curr_cacheline.valid := true.B
                    curr_cacheline.addr := masked_pc
                    curr_cacheline.data := io.read_data.bits
                }
            }
        }
        is(FetchTarget.pref) {
            io.fetched.valid := true.B
            io.fetched.bits := curr_cacheline.data

            val next_pc = masked_pc + (1.U << config.icache_config.offset_bits)
            val need_prefetch = !pref_cacheline.valid || pref_cacheline.addr =/= next_pc

            when(need_prefetch) {
                io.read_addr.valid := true.B
                io.read_addr.bits := next_pc
                // Prevent flush
                val read_valid = io.read_data.valid && io.transaction_addr === next_pc
                when(read_valid) {
                    pref_cacheline.valid := true.B
                    pref_cacheline.addr := next_pc
                    pref_cacheline.data := io.read_data.bits
                }
            }
        }
    }

    when(io.flush) {
        curr_cacheline.valid := false.B
        pref_cacheline.valid := false.B
    }
}
