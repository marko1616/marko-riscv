package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.backend._
import markorv.frontend.DecoderOutParams
import markorv.bus._
import markorv.config._

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

class LoadStoreUnit(implicit val config: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val lsu_instr = Flipped(Decoupled(new Bundle {
            val lsu_opcode = new LoadStoreOpcode
            val params = new DecoderOutParams(config.data_width)
        }))

        val interface = new IOInterface()(config.ls_io_config,true)

        val register_commit = Decoupled(new RegisterCommit)
        val invalidate_reserved = Input(Bool())
        val outfire = Output(Bool())
    })
    private val read_channel = io.interface.read.get
    private val write_channel = io.interface.write.get
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

    val op_fired = Wire(Bool())
    val load_data = Wire(UInt(config.data_width.W))
    val amo_data_reg = Reg(UInt(config.data_width.W))
    val AMO_SC_FAILED = "h0000000000000001".U
    val AMO_SC_SUCCED = "h0000000000000000".U

    def invalidate_reserved(write_addr: UInt) = {
        when(write_addr === local_load_reserved_addr) {
            local_load_reserved_valid := false.B
            local_load_reserved_addr := 0.U
        }
    }

    // default
    read_channel.params.valid := false.B
    read_channel.params.bits := new ReadParams()(config.ls_io_config).zero
    read_channel.resp.ready := false.B

    write_channel.params.valid := false.B
    write_channel.params.bits := new WriteParams()(config.ls_io_config).zero
    write_channel.resp.ready := false.B

    io.register_commit.valid := false.B
    io.register_commit.bits.data := 0.U(config.data_width.W)
    io.register_commit.bits.reg := 0.U(5.W)

    io.lsu_instr.ready := false.B
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
        read_channel.params.valid := true.B
        read_channel.params.bits.size := size
        read_channel.params.bits.addr := addr
        read_channel.params.bits.lock.get := true.B
        read_channel.resp.ready := true.B

        when(read_channel.resp.valid) {
            val raw = read_channel.resp.bits.data
            val extended = MuxLookup(size, raw)(Seq(
                0.U -> Mux(sign, raw(7,0).asSInt.pad(64).asUInt, raw(7,0).zextu(64)),
                1.U -> Mux(sign, raw(15,0).asSInt.pad(64).asUInt, raw(15,0).zextu(64)),
                2.U -> Mux(sign, raw(31,0).asSInt.pad(64).asUInt, raw(31,0).zextu(64)),
                3.U -> raw // full 64-bit load
            ))
            amo_data_reg := extended
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
                write_channel.params.valid := true.B
                write_channel.params.bits.size := size
                write_channel.params.bits.addr := addr
                write_channel.params.bits.data := data
                write_channel.params.bits.lock.get := true.B
                when(write_channel.resp.valid) {
                    op_fired := true.B
                    local_load_reserved_valid := false.B
                    local_load_reserved_addr := 0.U
                    io.register_commit.valid := true.B
                    io.register_commit.bits.data := Mux(write_channel.resp.bits === AxiResp.exokay,AMO_SC_SUCCED, AMO_SC_FAILED)
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
            write_channel.params.valid := true.B
            write_channel.params.bits.size := size
            write_channel.params.bits.addr := addr
            write_channel.params.bits.data := data
            when(write_channel.resp.valid) {
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
                read_channel.params.valid := true.B
                read_channel.params.bits.size := size
                read_channel.params.bits.addr := addr
                read_channel.resp.ready := true.B
                when(read_channel.resp.valid) {
                    op_fired := true.B
                    val raw = read_channel.resp.bits.data
                    val extended = MuxLookup(size, raw)(Seq(
                        0.U -> Mux(sign, raw(7,0).asSInt.pad(64).asUInt, raw(7,0).zextu(64)),
                        1.U -> Mux(sign, raw(15,0).asSInt.pad(64).asUInt, raw(15,0).zextu(64)),
                        2.U -> Mux(sign, raw(31,0).asSInt.pad(64).asUInt, raw(31,0).zextu(64)),
                        3.U -> raw // full 64-bit load
                    ))
                    load_data := extended
                }
            }.otherwise {
                val data = params.source2.asUInt
                val addr = params.source1.asUInt
                invalidate_reserved(addr)
                write_channel.params.valid := true.B
                write_channel.params.bits.size := size
                write_channel.params.bits.addr := addr
                write_channel.params.bits.data := data
                when(write_channel.resp.valid) {
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
