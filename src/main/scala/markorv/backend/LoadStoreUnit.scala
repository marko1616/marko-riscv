package markorv.backend

import chisel3._
import chisel3.util._

import markorv.frontend.DecoderOutParams
import markorv.backend._

class LoadStoreUnit(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        // lsu_opcode encoding:
        // Bit [5]
        //             0 = Normal
        //             1 = AMO
        //
        // Normal Bit [4]
        //             0 = Load
        //             1 = Store
        //
        // Normal Bit [3]   - Flag: Load from memory or Immediate
        //             0 = Memory(base address is params.source1 offset is immediate)
        //             1 = Immediate
        //
        // Normal Bit [2]   - Sign: Indicates if the data is signed or unsigned.
        //             0 = Signed integer (SInt)
        //             1 = Unsigned integer (UInt)
        //
        // Normal Bits [1:0] - Size: Specifies the size of the data being loaded or stored.
        //             00 = Byte (8 bits)
        //             01 = Halfword (16 bits)
        //             10 = Word (32 bits)
        //             11 = Doubleword (64 bits)
        //
        // AMO Bit [4]
        //             0 = AMO Comparison and calculation
        //             1 = AMO Swap, load reserved, store conditional
        //
        // AMO Bit [3]
        //             0 = AMO Comparison / AMO Swap
        //             1 = AMO Calculation / load reserved and store conditional
        //
        // AMO Bit [2:1]
        //             00 = add/min/load reserved
        //             01 = xor/max/load reserved
        //             10 = or/minu/store conditional
        //             11 = and/maxu/store conditional
        //
        // AMO Bit [0]
        //             0 = Word (32 bits)
        //             1 = Doubleword (64 bits)
        val lsu_instr = Flipped(Decoupled(new Bundle {
            val lsu_opcode = UInt(6.W)
            val params = new DecoderOutParams(data_width)
        }))

        val read_req = Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(64.W)
            val sign = Bool()
            val direct = Bool()
        })
        val read_data = Flipped(Decoupled((UInt(data_width.W))))

        val write_req = Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(addr_width.W)
            val data = UInt(data_width.W)
            val direct = Bool()
        })
        val write_outfire = Input(Bool())
        val register_commit = Decoupled(new RegisterCommit)

        val local_load_reserved = Decoupled(UInt(64.W))
        val invalidate_reserved = Input(Bool())

        val outfire = Output(Bool())
    })
    object State extends ChiselEnum {
        val stat_normal, stat_amo_cache, stat_amo_read, stat_amo_write = Value
    }
    val state = RegInit(State.stat_normal)
    val local_load_reserved_valid = RegInit(false.B)
    val local_load_reserved_addr = RegInit(0.U(64.W))

    // Alias
    val opcode = io.lsu_instr.bits.lsu_opcode
    val params = io.lsu_instr.bits.params
    val read_req = io.read_req.bits
    val write_req = io.write_req.bits

    val op_fired = Wire(Bool())
    val load_data = Wire(UInt(data_width.W))
    val amo_data_reg = Reg(UInt(data_width.W))

    val AMO_SWAP = "b1000".U

    val AMO_ADD  = "b0100".U
    val AMO_XOR  = "b0101".U
    val AMO_OR   = "b0110".U
    val AMO_AND  = "b0111".U

    val AMO_MIN  = "b0000".U
    val AMO_MAX  = "b0001".U
    val AMO_MINU = "b0010".U
    val AMO_MAXU = "b0011".U

    val AMO_LR   = "b1110".U
    val AMO_SC   = "b1111".U
    val AMO_SC_FAILED = "h0000000000000001".U

    def invalidate_reserved(write_addr: UInt) = {
        when(write_addr === local_load_reserved_addr) {
            local_load_reserved_valid := false.B
            local_load_reserved_addr := 0.U
        }
    }

    // default
    io.lsu_instr.ready := false.B
    io.write_req.valid := false.B
    write_req.size := 0.U
    write_req.addr := 0.U
    write_req.data := 0.U
    write_req.direct := false.B
    io.read_req.valid := false.B
    read_req.size := 0.U
    read_req.addr := 0.U
    read_req.sign := false.B
    read_req.direct := false.B
    io.read_data.ready := false.B

    io.register_commit.valid := false.B
    io.register_commit.bits.data := 0.U(data_width.W)
    io.register_commit.bits.reg := 0.U(5.W)

    io.local_load_reserved.valid := local_load_reserved_valid
    io.local_load_reserved.bits := local_load_reserved_addr

    io.outfire := false.B
    op_fired := false.B
    load_data := 0.U

    when(state === State.stat_amo_cache) {
        // TODO: Cache
        // Clear cache
        val addr = params.source1.asUInt & "hfffffffffffffff0".U
        state := State.stat_amo_read
    }

    when(state === State.stat_amo_read) {
        // Read data from memory
        val addr = params.source1.asUInt
        io.read_req.valid := true.B
        read_req.size := 3.U
        read_req.addr := addr
        read_req.sign := false.B
        read_req.direct := true.B
        io.read_data.ready := true.B

        when(io.read_data.valid) {
            val raw_data = io.read_data.bits
            when(opcode(0)) {
                amo_data_reg := raw_data
            }.otherwise {
                amo_data_reg := raw_data(31,0).asSInt.pad(64).asUInt
            }
            state := State.stat_amo_write
        }
    }

    when(state === State.stat_amo_write) {
        val addr = params.source1.asUInt
        val data = Wire(UInt(64.W))
        val source2 = Wire(UInt(64.W))
        data := 0.U
        source2 := 0.U

        when(opcode(0)) {
            source2 := params.source2
        }.otherwise {
            source2 := params.source2(31,0).asSInt.pad(64).asUInt
        }

        val is_lr = io.lsu_instr.bits.lsu_opcode(4, 1) === AMO_LR
        val is_sc = io.lsu_instr.bits.lsu_opcode(4, 1) === AMO_SC

        switch(io.lsu_instr.bits.lsu_opcode(4, 1)) {
            is(AMO_SWAP) {
                data := source2
            }

            is(AMO_ADD) {
                data := amo_data_reg + source2
            }
            is(AMO_XOR) {
                data := amo_data_reg ^ source2
            }
            is(AMO_OR) {
                data := amo_data_reg | source2
            }
            is(AMO_AND) {
                data := amo_data_reg & source2
            }

            is(AMO_MIN) {
                when(amo_data_reg.asSInt < source2.asSInt) {
                    data := amo_data_reg
                }.otherwise {
                    data := source2
                }
            }
            is(AMO_MAX) {
                when(amo_data_reg.asSInt > source2.asSInt) {
                    data := amo_data_reg
                }.otherwise {
                    data := source2
                }
            }
            is(AMO_MINU) {
                when(amo_data_reg < source2) {
                    data := amo_data_reg
                }.otherwise {
                    data := source2
                }
            }
            is(AMO_MAXU) {
                when(amo_data_reg > source2) {
                    data := amo_data_reg
                }.otherwise {
                    data := source2
                }
            }
            is(AMO_SC) {
                data := source2
            }
        }

        when(is_lr) {
            op_fired := true.B
            local_load_reserved_valid := true.B
            local_load_reserved_addr := addr

            io.register_commit.valid := true.B
            io.register_commit.bits.data := amo_data_reg
            io.register_commit.bits.reg := params.rd
            state := State.stat_normal
        }.elsewhen(is_sc) {
            when(local_load_reserved_valid && local_load_reserved_addr === addr) {
                io.write_req.valid := true.B
                write_req.size := "b10".U + opcode(0)
                write_req.addr := addr
                write_req.data := data
                write_req.direct := true.B
                when(io.write_outfire) {
                    op_fired := true.B
                    local_load_reserved_valid := false.B
                    local_load_reserved_addr := 0.U
                    io.register_commit.valid := true.B
                    io.register_commit.bits.data := 0.U
                    io.register_commit.bits.reg := params.rd
                    state := State.stat_normal
                }
            }.otherwise {
                op_fired := true.B
                io.register_commit.valid := true.B
                io.register_commit.bits.data := AMO_SC_FAILED
                io.register_commit.bits.reg := params.rd
                state := State.stat_normal
            }
        }.otherwise {
            invalidate_reserved(addr)
            io.write_req.valid := true.B
            write_req.size := "b10".U + opcode(0)
            write_req.addr := addr
            write_req.data := data
            write_req.direct := true.B
            when(io.write_outfire) {
                op_fired := true.B
                io.register_commit.valid := true.B
                io.register_commit.bits.data := amo_data_reg
                io.register_commit.bits.reg := params.rd
                state := State.stat_normal
            }
        }
    }

    when(!io.lsu_instr.valid) {
        io.lsu_instr.ready := io.register_commit.ready && state === State.stat_normal
    }

    when(io.lsu_instr.valid && io.register_commit.ready && state === State.stat_normal) {
        when(io.lsu_instr.bits.lsu_opcode(5)) {
            // AMO
            state := State.stat_amo_cache
        }.otherwise {
            // Normal
            when(opcode(4) === 0.U) {
                val is_signed = !opcode(2)
                val size = opcode(1, 0)
                val addr = params.source1.asUInt + params.immediate.asUInt
                read_req.size := size
                read_req.addr := addr
                read_req.sign := is_signed
                io.read_req.valid := true.B
                io.read_data.ready := true.B
                when(io.read_data.valid) {
                    op_fired := true.B
                    load_data := io.read_data.bits
                }
            }.otherwise {
                val size = opcode(1, 0)
                val store_data = params.source2.asUInt
                val addr = params.source1.asUInt + params.immediate.asUInt
                invalidate_reserved(addr)
                io.write_req.valid := true.B
                write_req.size := size
                write_req.addr := addr
                write_req.data := MuxCase(
                    store_data,
                    Seq(
                        (size === 0.U) -> store_data(7, 0).pad(64),
                        (size === 1.U) -> store_data(15, 0).pad(64),
                        (size === 2.U) -> store_data(31, 0).pad(64)
                        // size === 3.U is the default case (raw_data)
                    )
                )

                when(io.write_outfire) {
                    op_fired := true.B
                }
            }
        }
    }

    when(op_fired) {
        io.outfire := true.B
        io.lsu_instr.ready := true.B
        when(state === State.stat_normal && opcode(4) === 0.U) {
            io.register_commit.valid := true.B
            io.register_commit.bits.data := load_data
            io.register_commit.bits.reg := params.rd
        }
    }
}
