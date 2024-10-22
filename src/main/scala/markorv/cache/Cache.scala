package markorv.cache

import chisel3._
import chisel3.util._

class CacheLine(n_set: Int, n_way: Int, n_byte: Int) extends Bundle {
    val data = UInt((8 * n_byte).W)
    val tag = UInt(
      (64 - log2Ceil(n_set) - log2Ceil(n_way) - log2Ceil(n_byte)).W
    )
    val valid = Bool()
    val dirty = Bool()
}

class CacheWay(n_set: Int, n_way: Int, n_byte: Int) extends Bundle {
    val data = Vec(n_way, new CacheLine(n_set, n_way, n_byte))
}

class Cache(
    n_set: Int = 8,
    n_way: Int = 4,
    n_byte: Int = 16,
    upstream_bandwidth: Int = 64
) extends Module {
    val io = IO(new Bundle {
        val upstream_read_addr = Decoupled(UInt(64.W))
        val upstream_read_data = Flipped(Decoupled(UInt(64.W)))

        val read_addr = Flipped(Decoupled((UInt(64.W))))
        val read_cache_line = Decoupled(new CacheLine(n_set, n_way, n_byte))
    })

    val offset_width = log2Ceil(n_byte)
    val set_width = log2Ceil(n_set)
    val tag_width = 64 - offset_width - set_width

    val cache_mems = SyncReadMem(n_set, new CacheWay(n_set, n_way, n_byte))
    val last_cache_mem_read = Wire(new CacheWay(n_set, n_way, n_byte))
    val temp_cache_line = Reg(new CacheLine(n_set, n_way, n_byte))
    val temp_cache_way = Reg(new CacheWay(n_set, n_way, n_byte))

    val replace_way = Reg(UInt(log2Ceil(n_way).W))

    val read_set = Wire(UInt(set_width.W))
    val read_tag_reg = Reg(UInt((64 - set_width + offset_width).W))
    val read_set_reg = Reg(UInt(set_width.W))

    val read_ptr = Reg(UInt(log2Ceil(n_byte).W))

    object State extends ChiselEnum {
        val stat_idle, stat_look_up, stat_read_upstream, stat_write_upstream,
            stat_replace = Value
    }
    val state = RegInit(State.stat_idle)

    io.read_cache_line.valid := false.B
    io.read_cache_line.bits := 0.U.asTypeOf(new CacheLine(n_set, n_way, n_byte))
    io.read_addr.ready := state === State.stat_idle

    io.upstream_read_addr.valid := false.B
    io.upstream_read_addr.bits := 0.U(64.W)

    io.upstream_read_data.ready := false.B

    read_set := io.read_addr.bits(set_width + offset_width - 1, offset_width)
    when(state =/= State.stat_replace) {
        last_cache_mem_read := cache_mems.read(read_set)
    }.otherwise {
        last_cache_mem_read := 0.U.asTypeOf(new CacheWay(n_set, n_way, n_byte))
    }

    switch(state) {
        is(State.stat_idle) {
            when(io.read_addr.valid) {
                read_tag_reg := io.read_addr.bits(63, set_width + offset_width)
                read_set_reg := read_set
                state := State.stat_look_up
            }
        }
        is(State.stat_look_up) {
            val hit = Wire(Bool())
            hit := false.B

            for (i <- 0 until n_way) {
                when(
                  last_cache_mem_read
                      .data(i)
                      .tag === read_tag_reg && last_cache_mem_read.data(i).valid
                ) {
                    // Hit
                    io.read_cache_line.valid := true.B
                    io.read_cache_line.bits := last_cache_mem_read.data(i)
                    hit := true.B
                    state := State.stat_idle
                }
            }

            when(!hit) {
                // Miss
                temp_cache_way := last_cache_mem_read
                io.upstream_read_data.ready := true.B
                io.upstream_read_addr.valid := true.B
                io.upstream_read_addr.bits := Cat(
                  read_tag_reg,
                  read_set_reg,
                  0.U(log2Ceil(n_byte).W)
                )

                read_ptr := 0.U
                temp_cache_line.valid := true.B
                temp_cache_line.dirty := false.B
                temp_cache_line.tag := read_tag_reg
                state := State.stat_read_upstream
            }
        }
        is(State.stat_read_upstream) {
            io.upstream_read_data.ready := true.B
            io.upstream_read_addr.valid := true.B
            io.upstream_read_addr.bits := Cat(read_tag_reg, read_set_reg, read_ptr)
            when(io.upstream_read_data.valid) {
                // Read upstream data
                val next_cache_line = Wire(
                  Vec(
                    (8 * n_byte) / upstream_bandwidth,
                    UInt(upstream_bandwidth.W)
                  )
                )

                for (i <- 0 until (8 * n_byte) / upstream_bandwidth) {
                    when((i * upstream_bandwidth / 8).U === read_ptr) {
                        next_cache_line(i) := io.upstream_read_data.bits
                    }.otherwise {
                        next_cache_line(i) := temp_cache_line.data(
                          (i + 1) * upstream_bandwidth - 1,
                          i * upstream_bandwidth
                        )
                    }
                }
                temp_cache_line.data := Cat(next_cache_line.reverse)

                read_ptr := read_ptr + (1 << log2Ceil(upstream_bandwidth / 8)).U
                when(
                  read_ptr === (1 << (log2Ceil(
                    ((8 * n_byte / upstream_bandwidth) * upstream_bandwidth / 8) - upstream_bandwidth / 8
                  ))).U
                ) {
                    // Make sure wont replace the same
                    state := State.stat_replace
                }
            }
        }
        is(State.stat_write_upstream) {
            // TODO
            
            temp_cache_line.data := 0.U
            temp_cache_line.tag := 0.U
            temp_cache_line.valid := false.B
            temp_cache_line.dirty := false.B
        }
        is(State.stat_replace) {
            val next_cache_way = Wire(new CacheWay(n_set, n_way, n_byte))
            state := State.stat_idle

            for (i <- 0 until n_way) {
                when(i.U === replace_way) {
                    next_cache_way.data(i) := temp_cache_line
                    // reserved for write back
                    when(next_cache_way.data(i).dirty) {
                        temp_cache_line := next_cache_way.data(i)
                        state := State.stat_write_upstream
                    }.otherwise {
                        temp_cache_line.data := 0.U
                        temp_cache_line.tag := 0.U
                        temp_cache_line.valid := false.B
                        temp_cache_line.dirty := false.B
                    }
                }.otherwise {
                    next_cache_way.data(i) := last_cache_mem_read.data(i)
                }
            }
            replace_way := replace_way + 1.U
            cache_mems.write(read_set_reg, next_cache_way)
        }
    }
}
