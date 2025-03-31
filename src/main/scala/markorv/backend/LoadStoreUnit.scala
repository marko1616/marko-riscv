package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.backend._
import markorv.frontend.DecoderOutParams
import markorv.bus.ReadReq
import markorv.bus.WriteReq

object LSUOpcode extends ChiselEnum {
    val load = Value("b000000".U)
    val amoadd = Value("b000001".U)
    val store = Value("b000010".U)
    val amoswap = Value("b000011".U)
    val lr = Value("b000101".U)
    val sc = Value("b000111".U)
    val amoxor = Value("b001001".U)
    val amoor = Value("b010001".U)
    val amoand = Value("b011001".U)
    val amomin = Value("b100001".U)
    val amomax = Value("b101001".U)
    val amominu = Value("b110001".U)
    val amomaxu = Value("b111001".U)
    def isamo(op: LSUOpcode.Type): Bool = op.asUInt(0) === 1.U
    def isload(op: LSUOpcode.Type): Bool = op === LSUOpcode.load
    def isstore(op: LSUOpcode.Type): Bool = op === LSUOpcode.store
    def needwb(op: LSUOpcode.Type): Bool = isamo(op) | isload(op)
}

class LoadStoreUnit(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        val lsu_instr = Flipped(Decoupled(new Bundle {
            val lsu_opcode = new LoadStoreOpcode
            val params = new DecoderOutParams(data_width)
        }))

        val read_req = Decoupled(new ReadReq)
        val read_data = Flipped(Decoupled((UInt(data_width.W))))

        val write_req = Decoupled(new WriteReq)
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

    val (opcode, valid_funct) = LSUOpcode.safe(io.lsu_instr.bits.lsu_opcode.funct)
    val size = io.lsu_instr.bits.lsu_opcode.size(1,0)
    val sign = !io.lsu_instr.bits.lsu_opcode.size(2)
    val params = io.lsu_instr.bits.params
    val read_req = io.read_req.bits
    val write_req = io.write_req.bits

    val op_fired = Wire(Bool())
    val load_data = Wire(UInt(data_width.W))
    val amo_data_reg = Reg(UInt(data_width.W))
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
    write_req := new WriteReq().zero
    write_req.direct := false.B
    io.read_req.valid := false.B
    io.read_data.ready := false.B
    read_req := new ReadReq().zero

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
        state := State.stat_amo_read
    }

    when(state === State.stat_amo_read) {
        // Read data from memory
        val addr = params.source1.asUInt
        io.read_req.valid := true.B
        read_req.size := size
        read_req.addr := addr
        read_req.sign := sign
        read_req.direct := true.B
        io.read_data.ready := true.B

        when(io.read_data.valid) {
            amo_data_reg := io.read_data.bits
            state := State.stat_amo_write
        }
    }

    when(state === State.stat_amo_write) {
        val addr = params.source1.asUInt
        val data = Wire(UInt(64.W))
        val source2 = Wire(UInt(64.W))
        data := 0.U
        source2 := MuxLookup(size,params.source2)(Seq(
            0.U -> params.source2(7,0).sextu(64),
            1.U -> params.source2(15,0).sextu(64),
            2.U -> params.source2(31,0).sextu(64)
        ))
        val is_lr = opcode === LSUOpcode.lr
        val is_sc = opcode === LSUOpcode.sc

        data := MuxLookup(opcode, 0.U)(Seq(
            LSUOpcode.sc      -> source2,
            LSUOpcode.amoswap -> source2,
            LSUOpcode.amoadd  -> (amo_data_reg + source2),
            LSUOpcode.amoxor  -> (amo_data_reg ^ source2),
            LSUOpcode.amoor   -> (amo_data_reg | source2),
            LSUOpcode.amoand  -> (amo_data_reg & source2),
            LSUOpcode.amomin  -> Mux(amo_data_reg.asSInt < source2.asSInt, amo_data_reg, source2),
            LSUOpcode.amomax  -> Mux(amo_data_reg.asSInt > source2.asSInt, amo_data_reg, source2),
            LSUOpcode.amominu -> Mux(amo_data_reg < source2, amo_data_reg, source2),
            LSUOpcode.amomaxu -> Mux(amo_data_reg > source2, amo_data_reg, source2)
        ))

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
                write_req.size := size
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
            write_req.size := size
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

    when(io.lsu_instr.valid && valid_funct && io.register_commit.ready && state === State.stat_normal) {
        when(LSUOpcode.isamo(opcode)) {
            // AMO
            state := State.stat_amo_cache
        }.otherwise {
            // Normal
            when(LSUOpcode.isload(opcode)) {
                val addr = params.source1.asUInt
                read_req.size := size
                read_req.addr := addr
                read_req.sign := sign
                io.read_req.valid := true.B
                io.read_data.ready := true.B
                when(io.read_data.valid) {
                    op_fired := true.B
                    load_data := io.read_data.bits
                }
            }.otherwise {
                val store_data = params.source2.asUInt
                val addr = params.source1.asUInt
                invalidate_reserved(addr)
                io.write_req.valid := true.B
                write_req.size := size
                write_req.addr := addr
                write_req.data := store_data
                when(io.write_outfire) {
                    op_fired := true.B
                }
            }
        }
    }

    when(op_fired) {
        io.outfire := true.B
        io.lsu_instr.ready := true.B
        when(state === State.stat_normal && LSUOpcode.needwb(opcode)) {
            io.register_commit.valid := true.B
            io.register_commit.bits.data := load_data
            io.register_commit.bits.reg := params.rd
        }
    }
}
