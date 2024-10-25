package markorv.cache

import chisel3._
import chisel3.util._

class CacheWriteWarpper(n_set: Int, n_way: Int, n_byte: Int) extends Module {
    val io = IO(new Bundle {
        val write_req = Flipped(Decoupled(new Bundle {
            val addr = UInt(64.W)
            val data = new CacheLine(n_set, n_way, n_byte)
        }))
        val write_feedback = Decoupled(Bool())

        val write_cache_line_addr = Decoupled((UInt(64.W)))
        val write_cache_line = Flipped(Decoupled(new CacheLine(n_set, n_way, n_byte)))
    })


}