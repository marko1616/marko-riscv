package markorv.cache

import chisel3._
import chisel3.util._

class CacheWriteWarpper(n_set: Int, n_way: Int, n_byte: Int) extends Module {
    val io = IO(new Bundle {
        val write_req = Flipped(Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(64.W)
            val data = UInt(64.W)
        }))
        val write_outfire = Output(Bool())

        val read_cache_line_addr = Decoupled((UInt(64.W)))
        val read_cache_line = Flipped(Decoupled(new CacheLine(n_set, n_way, n_byte)))

        val cache_write_req = Decoupled(new Bundle {
            val addr = UInt(64.W)
            val data = new CacheLine(n_set, n_way, n_byte)
        })
        val cache_write_outfire = Input(Bool())
    })

    io.write_req.ready := false.B
    io.write_outfire := false.B

    io.read_cache_line_addr.valid := false.B
    io.read_cache_line_addr.bits := 0.U
    io.read_cache_line.ready := false.B

    io.cache_write_req.valid := false.B
    io.cache_write_req.bits.addr := 0.U
    io.cache_write_req.bits.data := 0.U.asTypeOf(new CacheLine(n_set, n_way, n_byte))

    // Use 2 Cacheline buffer so that cant handle not well aligned address
    val cache_line_buff = Reg(Vec(2, new CacheLine(n_set, n_way, n_byte)))
    val cache_line_addr = Reg(Vec(2, UInt(64.W)))
    val cache_replace = Reg(UInt(1.W))
    val raw_data = Wire(UInt(64.W))
    val size_mask = Wire(UInt(64.W))
    val request_aligned = Wire(Bool())
    size_mask := "hffffffffffffffff".U << io.write_req.bits.size
    request_aligned := (io.write_req.bits.addr & ~size_mask) === 0.U

    when(io.write_req.valid && request_aligned) {
        val cache_line_hit = Wire(Bool())
        cache_line_hit := false.B

        for (i <- 0 until 2) {
            when(cache_line_addr(i) === (io.write_req.bits.addr & size_mask) && cache_line_buff(i).valid) {
                // Cacheline hit.
                val write_mask = "hffffffffffffffff".U << 8.U*(io.write_req.bits.size.asTypeOf(UInt(3.W))+1.U)
                val data_offset = 8.U*(io.write_req.bits.addr & ~size_mask)
                val masked_cache_data = cache_line_buff(i).data & (~write_mask) << data_offset
                val masked_write_data = (io.write_req.bits.data & ~write_mask) << data_offset
                cache_line_hit := true.B
                cache_line_buff(i).dirty := true.B
                cache_line_buff(i).data := masked_cache_data | masked_write_data
                io.write_outfire := true.B
            }
        }

        when(!cache_line_hit) {
            io.read_cache_line.ready := true.B
            io.read_cache_line_addr.valid := true.B
            io.read_cache_line_addr.bits := (io.write_req.bits.addr & size_mask)

            when(cache_line_buff(cache_replace).dirty) {
                io.cache_write_req.valid := true.B
                io.cache_write_req.bits.addr := cache_line_addr(cache_replace)
                io.cache_write_req.bits.data := cache_line_buff(cache_replace)
            }

            when(io.read_cache_line.valid && (io.cache_write_outfire | ~cache_line_buff(cache_replace).dirty)) {
                cache_line_buff(cache_replace) := io.read_cache_line.bits
                cache_line_addr(cache_replace) := (io.write_req.bits.addr & size_mask)
                cache_replace := cache_replace + 1.U
            }
        }
    }
    // TODO: unaligned
}