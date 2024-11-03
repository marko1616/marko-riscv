package markorv.cache

import chisel3._
import chisel3.util._

class CacheReadWriteWarpper(n_set: Int, n_way: Int, n_byte: Int) extends Module {
    val io = IO(new Bundle {
        val read_req = Flipped(Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(64.W)
            // true for signed read
            val sign = Bool()
        }))
        val read_data = Decoupled(UInt(64.W))

        val write_req = Flipped(Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(64.W)
            val data = UInt(64.W)
        }))
        val write_outfire = Output(Bool())

        val read_cache_line_addr = Decoupled(UInt(64.W))
        val read_cache_line = Flipped(Decoupled(new CacheLine(n_set, n_way, n_byte)))

        val cache_write_req = Decoupled(new Bundle {
            val addr = UInt(64.W)
            val cache_line = new CacheLine(n_set, n_way, n_byte)
        })
        val cache_write_outfire = Input(Bool())

        val debug_peek = Output(UInt(64.W))
    })

    io.debug_peek := 1.U

    io.read_req.ready := true.B
    io.read_data.valid := false.B
    io.read_data.bits := 0.U

    io.write_req.ready := true.B
    io.write_outfire := false.B

    io.read_cache_line_addr.valid := false.B
    io.read_cache_line_addr.bits := 0.U
    io.read_cache_line.ready := false.B

    io.cache_write_req.valid := false.B
    io.cache_write_req.bits.addr := 0.U
    io.cache_write_req.bits.cache_line := 0.U.asTypeOf(new CacheLine(n_set, n_way, n_byte))

    // Use 2 Cacheline buffer so that cant handle not well aligned address
    val cache_line_buff = Reg(Vec(2, new CacheLine(n_set, n_way, n_byte)))
    val cache_line_addr = Reg(Vec(2, UInt(64.W)))
    val cache_replace = Reg(UInt(1.W))
    val raw_data = Wire(UInt(64.W))
    val size_mask = Wire(UInt(64.W))
    val cache_mask = Wire(UInt(64.W))
    val request_aligned = Wire(Bool())
    raw_data := 0.U
    size_mask := "hffffffffffffffff".U << io.write_req.bits.size
    cache_mask := "hfffffffffffffff0".U
    request_aligned := (io.write_req.bits.addr & ~size_mask) === 0.U

    when((io.write_req.valid | io.read_req.valid) && request_aligned) {
        val op_addr = Wire(UInt(64.W))
        val cache_line_hit = Wire(Bool())
        when(io.read_req.valid) {
            op_addr := io.read_req.bits.addr
        }.otherwise {
            op_addr := io.write_req.bits.addr
        }
        cache_line_hit := false.B

        for (i <- 0 until 2) {
            when(cache_line_addr(i) === (op_addr & cache_mask) && cache_line_buff(i).valid) {
                // Cacheline hit.
                val data_offset = 8.U*op_addr(3,0)
                cache_line_hit := true.B
                when(io.read_req.valid) {
                    io.read_data.valid := true.B
                    raw_data := cache_line_buff(i).data >> data_offset
                }

                when(io.write_req.valid) {
                    val write_mask = Wire(UInt(128.W))
                    val write_n_bits = Wire(UInt(7.W))
                    write_n_bits := 1.U(7.W) << (3.U(3.W) + io.write_req.bits.size)
                    write_mask := ~0.U(128.W) << write_n_bits

                    val masked_cache_data = cache_line_buff(i).data & ~(~write_mask << data_offset)
                    val masked_write_data = (io.write_req.bits.data.pad(128) & ~write_mask) << data_offset
                    cache_line_buff(i).dirty := true.B
                    cache_line_buff(i).data := masked_cache_data | masked_write_data
                    io.write_outfire := true.B
                }
            }
        }

        when(!cache_line_hit) {
            val worte_able = Wire(Bool())
            val dirty = Wire(Bool())
            dirty := cache_line_buff(cache_replace).dirty
            worte_able := (~dirty) | (~cache_line_buff(cache_replace).valid)

            when(dirty) {
                io.cache_write_req.valid := true.B
                io.cache_write_req.bits.addr := cache_line_addr(cache_replace)
                io.cache_write_req.bits.cache_line := cache_line_buff(cache_replace)
            }

            when(io.cache_write_outfire) {
                cache_line_buff(cache_replace).dirty := false.B
            }

            when(worte_able) {
                io.read_cache_line.ready := true.B
                io.read_cache_line_addr.valid := true.B
                io.read_cache_line_addr.bits := op_addr & cache_mask
            }

            when(io.read_cache_line.valid && worte_able) {
                cache_line_buff(cache_replace) := io.read_cache_line.bits
                cache_line_addr(cache_replace) := op_addr & cache_mask
                cache_line_buff(cache_replace).dirty := false.B
                cache_replace := cache_replace + 1.U
            }
        }
    }

    io.read_data.bits := MuxCase(
        raw_data,
        Seq(
            (io.read_req.bits.size === 0.U) -> Mux(
                io.read_req.bits.sign,
                (raw_data(7, 0).asSInt
                    .pad(64))
                    .asUInt, // Sign extend byte
                raw_data(7, 0).pad(64) // Zero extend byte
            ),
            (io.read_req.bits.size === 1.U) -> Mux(
                io.read_req.bits.sign,
                (raw_data(15, 0).asSInt
                    .pad(64))
                    .asUInt, // Sign extend halfword
                raw_data(15, 0).pad(64) // Zero extend halfword
            ),
            (io.read_req.bits.size === 2.U) -> Mux(
                io.read_req.bits.sign,
                (raw_data(31, 0).asSInt
                    .pad(64))
                    .asUInt, // Sign extend word
                raw_data(31, 0).pad(64) // Zero extend word
            )
        )
    )
    // TODO: unaligned
}