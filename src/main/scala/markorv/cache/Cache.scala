package markorv.cache

import chisel3._
import chisel3.util._

class Cache(
    n_set: Int = 8,
    n_way: Int = 4,
    n_byte: Int = 16,
    upstream_bandwidth: Int = 64
) extends Module {
    val io = IO(new Bundle {
        val upstream_read_addr = Decoupled(UInt(upstream_bandwidth.W))
        val upstream_read_data = Flipped(Decoupled(UInt(upstream_bandwidth.W)))

        val read_addr = Flipped(Decoupled(UInt(upstream_bandwidth.W)))
        val read_cache_line = Decoupled(new CacheLine(n_set, n_way, n_byte))

        val write_req = Flipped(Decoupled(new Bundle {
            val addr = UInt(upstream_bandwidth.W)
            val cache_line = new CacheLine(n_set, n_way, n_byte)
        }))
        val write_outfire = Output(Bool())

        val upstream_write_req = Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(upstream_bandwidth.W)
            val data = UInt(upstream_bandwidth.W)
        })
        val upstream_write_outfire = Input(Bool())
    })
    val upstream_n_bytes = upstream_bandwidth / 8
    val offset_width = log2Ceil(n_byte)
    val set_width = log2Ceil(n_set)
    val tag_width = 64 - offset_width - set_width

    val cache_mems = SyncReadMem(n_set, new CacheWay(n_set, n_way, n_byte))
    val op_cache_way = Wire(new CacheWay(n_set, n_way, n_byte))
    val temp_cache_line = Reg(new CacheLine(n_set, n_way, n_byte))
    val temp_cache_way = Reg(new CacheWay(n_set, n_way, n_byte))

    val replace_way = Reg(UInt(log2Ceil(n_way).W))

    val op_set = Wire(UInt(set_width.W))
    val op_tag_reg = Reg(UInt((64 - set_width + offset_width).W))
    val op_set_reg = Reg(UInt(set_width.W))
    val hit_index = Reg(UInt(log2Ceil(n_way).W))

    val max_ptr = (1 << (log2Ceil(((8 * n_byte / upstream_bandwidth) * upstream_n_bytes) - upstream_n_bytes))).U
    val read_ptr = Reg(UInt(log2Ceil(n_byte).W))
    val write_ptr = Reg(UInt(log2Ceil(n_byte).W))

    object State extends ChiselEnum {
        val stat_idle, stat_look_up, stat_read_upstream, stat_write_upstream, stat_replace = Value
    }
    val state = RegInit(State.stat_idle)
    // 0 for read 1 for write
    val state_op_type = RegInit(0.U(1.W))

    io.read_cache_line.valid := false.B
    io.read_cache_line.bits := 0.U.asTypeOf(new CacheLine(n_set, n_way, n_byte))
    io.read_addr.ready := state === State.stat_idle

    // Should NOT write and read simultaneously.
    io.write_outfire := false.B
    io.write_req.ready := (state === State.stat_idle && !io.read_addr.valid)

    io.upstream_read_addr.valid := false.B
    io.upstream_read_addr.bits := 0.U(64.W)
    io.upstream_read_data.ready := false.B

    io.upstream_write_req.valid := false.B
    // Size is a constent.
    io.upstream_write_req.bits.size := 2.U
    io.upstream_write_req.bits.addr := 0.U
    io.upstream_write_req.bits.data := 0.U

    when(io.read_addr.valid) {
        op_set := io.read_addr.bits(set_width + offset_width - 1, offset_width)
    }.otherwise {
        op_set := io.write_req.bits.addr(set_width + offset_width - 1, offset_width)
    }
    when(state =/= State.stat_replace) {
        op_cache_way := cache_mems.read(op_set)
    }.otherwise {
        op_cache_way := 0.U.asTypeOf(new CacheWay(n_set, n_way, n_byte))
    }

    switch(state) {
        is(State.stat_idle) {
            op_tag_reg := io.read_addr.bits(63, set_width + offset_width)
            op_set_reg := op_set
            when(io.write_req.valid) {
                state_op_type := 1.U
                state := State.stat_look_up
            }
            when(io.read_addr.valid) {
                state_op_type := 0.U
                state := State.stat_look_up
            }
        }
        is(State.stat_look_up) {
            val next_cache_way = Wire(new CacheWay(n_set, n_way, n_byte))
            val hit = Wire(Bool())
            hit := false.B

            for (i <- 0 until n_way) {
                when(op_cache_way.data(i).valid && op_cache_way.data(i).tag === op_tag_reg) {
                    // Hit
                    next_cache_way.data(i) := op_cache_way.data(i)
                    hit_index := i.U
                    when(state_op_type === 1.U) {
                        // Write
                        next_cache_way.data(i) := io.write_req.bits.cache_line
                        next_cache_way.data(i).valid := true.B
                        next_cache_way.data(i).dirty := true.B
                        hit := true.B
                        state := State.stat_idle

                        io.write_outfire := true.B
                    }.otherwise {
                        // Read
                        io.read_cache_line.valid := true.B
                        io.read_cache_line.bits := op_cache_way.data(i)

                        hit := true.B
                        state := State.stat_idle
                    }
                }.otherwise {
                    next_cache_way.data(i) := op_cache_way.data(i)
                }
            }

            when(hit && state_op_type === 1.U) {
                cache_mems.write(op_set_reg, next_cache_way)
            }

            when(!hit) {
                // Miss
                temp_cache_way := op_cache_way
                io.upstream_read_data.ready := true.B
                io.upstream_read_addr.valid := true.B
                io.upstream_read_addr.bits := Cat(
                  op_tag_reg,
                  op_set_reg,
                  0.U(log2Ceil(n_byte).W)
                )

                read_ptr := 0.U
                temp_cache_line.valid := true.B
                temp_cache_line.dirty := false.B
                temp_cache_line.tag := op_tag_reg
                state := State.stat_read_upstream
            }
        }
        is(State.stat_read_upstream) {
            io.upstream_read_data.ready := true.B
            io.upstream_read_addr.valid := true.B
            io.upstream_read_addr.bits := Cat(op_tag_reg, op_set_reg, read_ptr)
            when(io.upstream_read_data.valid) {
                // Read upstream data
                val next_cache_line = Wire(
                  Vec((8 * n_byte) / upstream_bandwidth,UInt(upstream_bandwidth.W))
                )

                for (i <- 0 until (8 * n_byte) / upstream_bandwidth) {
                    when((i * upstream_n_bytes).U === read_ptr) {
                        next_cache_line(i) := io.upstream_read_data.bits
                    }.otherwise {
                        next_cache_line(i) := temp_cache_line.data(
                          (i + 1) * upstream_bandwidth - 1,
                          i * upstream_bandwidth
                        )
                    }
                }
                temp_cache_line.data := Cat(next_cache_line.reverse)

                when(read_ptr === max_ptr) {
                    // Make sure wont replace the same
                    state := State.stat_replace
                }
                read_ptr := read_ptr + (1 << log2Ceil(upstream_n_bytes)).U
            }
        }
        is(State.stat_write_upstream) {
            io.upstream_write_req.valid := true.B
            io.upstream_write_req.bits.addr := Cat(temp_cache_line.tag, op_set_reg, write_ptr)
            io.upstream_write_req.bits.data := temp_cache_line.data
            for (i <- 0 until (8 * n_byte) / upstream_bandwidth) {
                when((i * upstream_n_bytes).U === write_ptr) {
                    io.upstream_write_req.bits.data := temp_cache_line.data((i + 1) * upstream_bandwidth - 1,i * upstream_bandwidth)
                }
            }

            when(write_ptr === max_ptr) {
                temp_cache_line.data := 0.U
                temp_cache_line.tag := 0.U
                temp_cache_line.valid := false.B
                temp_cache_line.dirty := false.B

                state := State.stat_idle
            }

            when(io.upstream_write_outfire) {
                write_ptr := write_ptr + (1 << log2Ceil(upstream_n_bytes)).U
            }
        }
        is(State.stat_replace) {
            val next_cache_way = Wire(new CacheWay(n_set, n_way, n_byte))
            state := State.stat_idle

            for (i <- 0 until n_way) {
                when(i.U === replace_way) {
                    next_cache_way.data(i) := temp_cache_line
                    // reserved for write back
                    when(op_cache_way.data(i).valid && op_cache_way.data(i).dirty) {
                        write_ptr := 0.U
                        temp_cache_line := op_cache_way.data(i)
                        state := State.stat_write_upstream
                    }.otherwise {
                        temp_cache_line.data := 0.U
                        temp_cache_line.tag := 0.U
                        temp_cache_line.valid := false.B
                        temp_cache_line.dirty := false.B
                    }
                }.otherwise {
                    next_cache_way.data(i) := op_cache_way.data(i)
                }
            }
            replace_way := replace_way + 1.U
            cache_mems.write(op_set_reg, next_cache_way)
        }
    }
}
