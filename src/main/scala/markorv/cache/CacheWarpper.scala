package markorv.cache

import chisel3._
import chisel3.util._

class CacheWarpper(n_set: Int, n_way: Int, n_byte: Int) extends Module {
    val io = IO(new Bundle {
        val read_requests = Flipped(Decoupled(new Bundle {
            val addr = UInt(64.W)
            val size = UInt(2.W)
            // true for signed read
            val sign = Bool()
        }))
        val read_data = Decoupled(UInt(64.W))

        val read_cache_line_addr = Decoupled((UInt(64.W)))
        val read_cache_line = Flipped(Decoupled(new CacheLine(n_set, n_way, n_byte)))
    })

    io.read_requests.ready := false.B
    io.read_data.valid := false.B
    io.read_data.bits := 0.U

    io.read_cache_line_addr.valid := false.B
    io.read_cache_line_addr.bits := 0.U
    io.read_cache_line.ready := false.B

    // Use 2 Cacheline buffer so that cant handle not well aligned address
    val cache_line_buffer = Reg(Vec(2, new CacheLine(n_set, n_way, n_byte)))

    when(io.read_requests.valid) {
        
    }
}