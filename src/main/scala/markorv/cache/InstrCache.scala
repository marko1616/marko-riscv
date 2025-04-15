package markorv.cache

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.bus._
import markorv.cache._

class InstrCache(implicit val config: CacheConfig) extends Module {
    val io = IO(new Bundle {
        val in_read_req = Flipped(Decoupled(UInt(64.W)))
        val in_read_data = Decoupled(UInt((8 * config.data_bytes).W))
        val out_read_req = Decoupled(UInt(64.W))
        val out_read_data = Flipped(Decoupled(UInt((8 * config.data_bytes).W)))
        val transaction_addr = Output(UInt(64.W))
        val invalidate = Input(Bool())
        val invalidate_outfire = Output(Bool())
    })
    object State extends ChiselEnum {
        val stat_idle, stat_read, stat_replace, stat_invalidate = Value
    }

    val read_addr = Reg(UInt(64.W))

    val state = RegInit(State.stat_idle)
    val invalidate_state = Reg(UInt(config.set_bits.W))
    val replace_ptr = Reg(UInt(log2Ceil(config.way_num).W))

    val tagv_array = SyncReadMem(config.set_num, Vec(config.way_num,new CacheTagValid))
    val data_array = SyncReadMem(config.set_num, Vec(config.way_num,new CacheData))

    val tagv_read = Wire(Vec(config.way_num,new CacheTagValid))
    val data_read = Wire(Vec(config.way_num,new CacheData))
    val lookup_index = Mux(state === State.stat_replace,read_addr(config.set_end,config.set_start),io.in_read_req.bits(config.set_end,config.set_start))
    val lookup_valid = io.in_read_req.valid && (state === State.stat_idle || state === State.stat_read || state === State.stat_replace)

    io.in_read_req.ready := (state === State.stat_idle || state === State.stat_read)
    io.out_read_data.ready := state === State.stat_replace

    io.in_read_data.valid := false.B
    io.out_read_req.valid := false.B
    io.in_read_data.bits := 0.U
    io.out_read_req.bits := 0.U
    io.transaction_addr := read_addr

    io.invalidate_outfire := false.B

    tagv_read := tagv_array.read(lookup_index,lookup_valid)
    data_read := data_array.read(lookup_index,lookup_valid)

    switch(state) {
        is(State.stat_idle) {
            when(io.in_read_req.valid) {
                read_addr := io.in_read_req.bits
                state := State.stat_read
            }
            when(io.invalidate) {
                invalidate_state := 0.U
                state := State.stat_invalidate
            }
        }
        is(State.stat_read) {
            val read_tag = read_addr(config.tag_end,config.tag_start)
            val read_valid = WireInit(false.B)
            for(i <- 0 until config.way_num) {
                val tagv = tagv_read(i)
                when(tagv.valid && tagv.tag === read_tag) {
                    read_valid := true.B
                    io.in_read_data.valid := true.B
                    io.in_read_data.bits := data_read(i).data
                    when(io.in_read_req.valid) {
                        read_addr := io.in_read_req.bits
                        state := State.stat_read
                    }.otherwise {
                        state := State.stat_idle
                    }
                }
            }
            when(!read_valid) {
                state := State.stat_replace
            }
            when(io.invalidate) {
                invalidate_state := 0.U
                state := State.stat_invalidate
            }
        }
        is(State.stat_replace) {
            io.out_read_req.valid := true.B
            io.out_read_req.bits := read_addr
            when(io.out_read_data.valid) {
                val index = read_addr(config.set_end, config.set_start)
                val tag = read_addr(config.tag_end, config.tag_start)
                val way = replace_ptr

                val new_tagv = Wire(Vec(config.way_num, new CacheTagValid))
                val new_data = Wire(Vec(config.way_num, new CacheData))

                for (i <- 0 until config.way_num) {
                    new_tagv(i) := tagv_read(i)
                    new_data(i) := data_read(i)
                    when(i.U === way) {
                        new_tagv(i).tag := tag
                        new_tagv(i).valid := true.B
                        new_data(i).data := io.out_read_data.bits
                    }
                }

                tagv_array.write(index, new_tagv)
                data_array.write(index, new_data)

                replace_ptr := replace_ptr + 1.U
                io.in_read_data.valid := true.B
                io.in_read_data.bits := io.out_read_data.bits
                state := State.stat_idle
            }
        }
        is(State.stat_invalidate) {
            val current_set = invalidate_state
            val invalidate_tagv = Vec(config.way_num, new CacheTagValid()).zero
            tagv_array.write(current_set, invalidate_tagv)
            invalidate_state := current_set + 1.U
            when(current_set === (config.set_num - 1).U) {
                state := State.stat_idle
                io.invalidate_outfire := true.B
            }
        }
    }
}
