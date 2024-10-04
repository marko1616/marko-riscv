package markorv

import chisel3._
import chisel3.util._

import markorv.DecoderOutParams

class LoadStoreUnit(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        // lsu_opcode encoding:
        // Bit [4]
        //             0 = Load
        //             1 = Store
        //
        // Bit [3]   - Flag: Load from memory or Immediate
        //             0 = Memory(base address is params.source1 offset is immediate)
        //             1 = Immediate
        //
        // Bit [2]   - Sign: Indicates if the data is signed or unsigned.
        //             0 = Signed integer (SInt)
        //             1 = Unsigned integer (UInt)
        //
        // Bits [1:0] - Size: Specifies the size of the data being loaded or stored.
        //             00 = Byte (8 bits)
        //             01 = Halfword (16 bits)
        //             10 = Word (32 bits)
        //             11 = Doubleword (64 bits)
        val lsu_instr = Flipped(Decoupled(new Bundle {
            val lsu_opcode = UInt(5.W)
            val params = new DecoderOutParams(data_width)
        }))

        val mem_write = Decoupled(UInt(data_width.W))
        val mem_read = Flipped(Decoupled((UInt(data_width.W))))
        val mem_read_addr = Decoupled(UInt(addr_width.W))
        val mem_write_addr = Output(UInt(addr_width.W))
        val mem_write_outfire = Input(Bool())
        val mem_write_width = Output(UInt(2.W))

        val write_back = Decoupled(new Bundle {
            val reg = Input(UInt(5.W))
            val data = Input(UInt(64.W))
        })

        val state_peek = Output(UInt(3.W))
    })

    // State def
    object State extends ChiselEnum {
        val stat_idle, stat_load, stat_store, stat_writeback = Value
    }
    val state = RegInit(State.stat_idle)

    // Register def
    val opcode = Reg(UInt(5.W))
    val params = Reg(new DecoderOutParams(data_width))
    val load_data = Reg(UInt(data_width.W))

    // default
    io.mem_write_addr := 0.U(addr_width.W)
    io.mem_read_addr.valid := false.B
    io.mem_read_addr.bits := 0.U(addr_width.W)
    io.mem_write.bits := 0.U(data_width.W)
    io.mem_write.valid := false.B
    io.lsu_instr.ready := false.B
    io.mem_read.ready := false.B
    io.mem_write_width := 0.U(2.W)

    io.write_back.valid := false.B
    io.write_back.bits.data := 0.U(data_width.W)
    io.write_back.bits.reg := 0.U(5.W)

    io.state_peek := state.asUInt

    switch(state) {
        is(State.stat_idle) {
            val next_state = WireInit(State.stat_idle)
            val operation_addr = WireInit(0.U(addr_width.W))
            next_state := Mux(io.lsu_instr.bits.lsu_opcode(4), State.stat_store, State.stat_load)
            operation_addr := io.lsu_instr.bits.params.source1.asUInt + params.immediate

            when(io.lsu_instr.valid && next_state === State.stat_load) {
                // prefetch
                io.mem_read_addr.bits := operation_addr
                io.mem_read_addr.valid := true.B
                io.mem_read.ready := true.B
                state := next_state
            }

            when(io.lsu_instr.valid) {
                opcode := io.lsu_instr.bits.lsu_opcode
                params := io.lsu_instr.bits.params
                state := next_state
            }
            io.lsu_instr.ready := io.write_back.ready
        }

        is(State.stat_load) {
            val is_immediate = opcode(3)
            val is_signed = !opcode(2)
            val size = opcode(1, 0)

            when(is_immediate) {
                load_data := params.immediate.asUInt
                state := State.stat_writeback
            }.otherwise {
                io.mem_read_addr.bits := params.source1.asUInt + params.immediate.asUInt
                io.mem_read_addr.valid := true.B
                io.mem_read.ready := true.B
                when(io.mem_read.valid) {
                    val raw_data = io.mem_read.bits
                    load_data := MuxCase(
                      raw_data,
                      Seq(
                        (size === 0.U) -> Mux(
                          is_signed,
                          (raw_data(7, 0).asSInt
                              .pad(64))
                              .asUInt, // Sign extend byte
                          raw_data(7, 0).pad(64) // Zero extend byte
                        ),
                        (size === 1.U) -> Mux(
                          is_signed,
                          (raw_data(15, 0).asSInt
                              .pad(64))
                              .asUInt, // Sign extend halfword
                          raw_data(15, 0).pad(64) // Zero extend halfword
                        ),
                        (size === 2.U) -> Mux(
                          is_signed,
                          (raw_data(31, 0).asSInt
                              .pad(64))
                              .asUInt, // Sign extend word
                          raw_data(31, 0).pad(64) // Zero extend word
                        )
                        // size === 3.U is the default case (raw_data)
                      )
                    )
                    state := State.stat_writeback
                }
            }
        }

        is(State.stat_store) {
            val size = opcode(1, 0)
            val store_data = params.source2.asUInt

            io.mem_write_addr := params.source1.asUInt + params.immediate.asUInt
            io.mem_write.valid := true.B
            io.mem_write_width := size

            io.mem_write.bits := MuxCase(
              store_data,
              Seq(
                (size === 0.U) -> store_data(7, 0).pad(64),
                (size === 1.U) -> store_data(15, 0).pad(64),
                (size === 2.U) -> store_data(31, 0).pad(64)
                // size === 3.U is the default case (raw_data)
              )
            )

            when(io.mem_write.ready && io.mem_write_outfire) {
                state := State.stat_idle
            }
        }

        is(State.stat_writeback) {
            when(io.write_back.ready) {
                io.write_back.valid := true.B
                io.write_back.bits.data := load_data
                io.write_back.bits.reg := params.rd
                state := State.stat_idle
            }
        }
    }
}
