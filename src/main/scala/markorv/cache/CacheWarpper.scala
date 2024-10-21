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
    val cache_line_addr = Reg(Vec(2, UInt(64.W)))
    val cache_replace = Reg(UInt(1.W))
    val size_mask = Wire(UInt(64.W))
    val request_aligned = Wire(Bool())
    val raw_data = Wire(UInt(64.W))
    raw_data := 0.U
    size_mask := ("hffffffffffffffff".U << io.read_requests.bits.size)
    request_aligned := (io.read_requests.bits.addr & ~size_mask) === 0.U

    when(io.read_requests.valid && request_aligned) {
        val cache_line_hit = Wire(Bool())
        cache_line_hit := false.B

        for (i <- 0 until 2) {
            when(cache_line_addr(i) === (io.read_requests.bits.addr & size_mask) && cache_line_buffer(i).valid) {
                // Cacheline hit.
                cache_line_hit := true.B
                io.read_data.valid := true.B
                raw_data := cache_line_buffer(i).data >> (8.U*(io.read_requests.bits.addr & "h000000000000000f".U)(3,0))
            }
        }

        when(!cache_line_hit) {
            io.read_cache_line.ready := true.B
            io.read_cache_line_addr.valid := true.B
            io.read_cache_line_addr.bits := (io.read_requests.bits.addr & size_mask)

            when(io.read_cache_line.valid) {
                cache_line_buffer(cache_replace) := io.read_cache_line.bits
                cache_line_addr(cache_replace) := (io.read_requests.bits.addr & size_mask)
                cache_replace := cache_replace + 1.U

                io.read_data.valid := true.B
                raw_data := io.read_cache_line.bits.data >> (8.U*(io.read_requests.bits.addr & "h000000000000000f".U)(3,0))
            }
        }
    }

    io.read_data.bits := MuxCase(
        raw_data,
        Seq(
            (io.read_requests.bits.size === 0.U) -> Mux(
                io.read_requests.bits.sign,
                (raw_data(7, 0).asSInt
                    .pad(64))
                    .asUInt, // Sign extend byte
                raw_data(7, 0).pad(64) // Zero extend byte
            ),
            (io.read_requests.bits.size === 1.U) -> Mux(
                io.read_requests.bits.sign,
                (raw_data(15, 0).asSInt
                    .pad(64))
                    .asUInt, // Sign extend halfword
                raw_data(15, 0).pad(64) // Zero extend halfword
            ),
            (io.read_requests.bits.size === 2.U) -> Mux(
                io.read_requests.bits.sign,
                (raw_data(31, 0).asSInt
                    .pad(64))
                    .asUInt, // Sign extend word
                raw_data(31, 0).pad(64) // Zero extend word
            )
        )
    )
    // TODO: unaligned
}