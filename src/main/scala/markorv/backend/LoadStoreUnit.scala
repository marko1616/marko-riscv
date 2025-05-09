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
        val lsuInstr = Flipped(Decoupled(new Bundle {
            val lsuOpcode = new LoadStoreOpcode
            val params = new DecoderOutParams
        }))

        val interface = new IOInterface()(config.loadStoreIoConfig,true)

        val commit = Decoupled(new CommitBundle)
        val invalidateReserved = Input(Bool())
        val outfire = Output(Bool())
    })
    private val readChannel = io.interface.read.get
    private val writeChannel = io.interface.write.get
    object State extends ChiselEnum {
        val statNormal, statAmoCache, statAmoRead, statAmoWrite = Value
    }
    val state = RegInit(State.statNormal)
    val localLoadReservedValid = RegInit(false.B)
    val localLoadReservedAddr = RegInit(0.U(64.W))

    val (opcode, validFunct) = LSUOpcode.safe(io.lsuInstr.bits.lsuOpcode.funct)
    val size = io.lsuInstr.bits.lsuOpcode.size(1,0)
    val sign = !io.lsuInstr.bits.lsuOpcode.size(2)
    val params = io.lsuInstr.bits.params

    val opFired = Wire(Bool())
    val loadData = Wire(UInt(config.dataWidth.W))
    val amoDataReg = Reg(UInt(config.dataWidth.W))
    val AMO_SC_FAILED = "h0000000000000001".U
    val AMO_SC_SUCCED = "h0000000000000000".U

    def invalidateReserved(writeAddr: UInt) = {
        when(writeAddr === localLoadReservedAddr) {
            localLoadReservedValid := false.B
            localLoadReservedAddr := 0.U
        }
    }

    // default
    readChannel.params.valid := false.B
    readChannel.params.bits := new ReadParams()(config.loadStoreIoConfig).zero
    readChannel.resp.ready := false.B

    writeChannel.params.valid := false.B
    writeChannel.params.bits := new WriteParams()(config.loadStoreIoConfig).zero
    writeChannel.resp.ready := false.B

    io.commit.valid := false.B
    io.commit.bits.data := 0.U(config.dataWidth.W)
    io.commit.bits.reg := 0.U(5.W)

    io.lsuInstr.ready := false.B
    io.outfire := false.B
    opFired := false.B
    loadData := 0.U

    when(state === State.statAmoCache) {
        // TODO: Cache
        // Clear cache
        state := State.statAmoRead
    }

    when(state === State.statAmoRead) {
        // Read data from memory
        val addr = params.source1.asUInt
        readChannel.params.valid := true.B
        readChannel.params.bits.size := size
        readChannel.params.bits.addr := addr
        readChannel.params.bits.lock.get := true.B
        readChannel.resp.ready := true.B

        when(readChannel.resp.valid) {
            val raw = readChannel.resp.bits.data
            val extended = MuxLookup(size, raw)(Seq(
                0.U -> Mux(sign, raw(7,0).sextu(64), raw(7,0).zextu(64)),
                1.U -> Mux(sign, raw(15,0).sextu(64), raw(15,0).zextu(64)),
                2.U -> Mux(sign, raw(31,0).sextu(64), raw(31,0).zextu(64)),
                3.U -> raw // full 64-bit load
            ))
            amoDataReg := extended
            state := State.statAmoWrite
        }
    }

    when(state === State.statAmoWrite) {
        val addr = params.source1.asUInt
        val data = Wire(UInt(64.W))
        val source2 = Wire(UInt(64.W))
        data := 0.U
        source2 := MuxLookup(size,params.source2)(Seq(
            0.U -> params.source2(7,0).sextu(64),
            1.U -> params.source2(15,0).sextu(64),
            2.U -> params.source2(31,0).sextu(64)
        ))
        val isLr = opcode === LSUOpcode.lr
        val isSc = opcode === LSUOpcode.sc

        data := MuxLookup(opcode, 0.U)(Seq(
            LSUOpcode.sc      -> source2,
            LSUOpcode.amoswap -> source2,
            LSUOpcode.amoadd  -> (amoDataReg + source2),
            LSUOpcode.amoxor  -> (amoDataReg ^ source2),
            LSUOpcode.amoor   -> (amoDataReg | source2),
            LSUOpcode.amoand  -> (amoDataReg & source2),
            LSUOpcode.amomin  -> Mux(amoDataReg.asSInt < source2.asSInt, amoDataReg, source2),
            LSUOpcode.amomax  -> Mux(amoDataReg.asSInt > source2.asSInt, amoDataReg, source2),
            LSUOpcode.amominu -> Mux(amoDataReg < source2, amoDataReg, source2),
            LSUOpcode.amomaxu -> Mux(amoDataReg > source2, amoDataReg, source2)
        ))

        when(isLr) {
            opFired := true.B
            localLoadReservedValid := true.B
            localLoadReservedAddr := addr

            io.commit.valid := true.B
            io.commit.bits.data := amoDataReg
            io.commit.bits.reg := params.rd
            state := State.statNormal
        }.elsewhen(isSc) {
            when(localLoadReservedValid && localLoadReservedAddr === addr) {
                writeChannel.params.valid := true.B
                writeChannel.params.bits.size := size
                writeChannel.params.bits.addr := addr
                writeChannel.params.bits.data := data
                writeChannel.params.bits.lock.get := true.B
                when(writeChannel.resp.valid) {
                    opFired := true.B
                    localLoadReservedValid := false.B
                    localLoadReservedAddr := 0.U
                    io.commit.valid := true.B
                    io.commit.bits.data := Mux(writeChannel.resp.bits === AxiResp.exokay,AMO_SC_SUCCED, AMO_SC_FAILED)
                    io.commit.bits.reg := params.rd
                    state := State.statNormal
                }
            }.otherwise {
                opFired := true.B
                io.commit.valid := true.B
                io.commit.bits.data := AMO_SC_FAILED
                io.commit.bits.reg := params.rd
                state := State.statNormal
            }
        }.otherwise {
            invalidateReserved(addr)
            writeChannel.params.valid := true.B
            writeChannel.params.bits.size := size
            writeChannel.params.bits.addr := addr
            writeChannel.params.bits.data := data
            when(writeChannel.resp.valid) {
                opFired := true.B
                io.commit.valid := true.B
                io.commit.bits.data := amoDataReg
                io.commit.bits.reg := params.rd
                state := State.statNormal
            }
        }
    }

    when(!io.lsuInstr.valid) {
        io.lsuInstr.ready := io.commit.ready && state === State.statNormal
    }

    when(io.lsuInstr.valid && validFunct && io.commit.ready && state === State.statNormal) {
        when(LSUOpcode.isamo(opcode)) {
            // AMO
            state := State.statAmoCache
        }.otherwise {
            // Normal
            when(LSUOpcode.isload(opcode)) {
                val addr = params.source1.asUInt
                readChannel.params.valid := true.B
                readChannel.params.bits.size := size
                readChannel.params.bits.addr := addr
                readChannel.resp.ready := true.B
                when(readChannel.resp.valid) {
                    opFired := true.B
                    val raw = readChannel.resp.bits.data
                    val extended = MuxLookup(size, raw)(Seq(
                        0.U -> Mux(sign, raw(7,0).sextu(64), raw(7,0).zextu(64)),
                        1.U -> Mux(sign, raw(15,0).sextu(64), raw(15,0).zextu(64)),
                        2.U -> Mux(sign, raw(31,0).sextu(64), raw(31,0).zextu(64)),
                        3.U -> raw // full 64-bit load
                    ))
                    loadData := extended
                }
            }.otherwise {
                val data = params.source2.asUInt
                val addr = params.source1.asUInt
                invalidateReserved(addr)
                writeChannel.params.valid := true.B
                writeChannel.params.bits.size := size
                writeChannel.params.bits.addr := addr
                writeChannel.params.bits.data := data
                when(writeChannel.resp.valid) {
                    opFired := true.B
                }
            }
        }
    }

    when(opFired) {
        io.outfire := true.B
        io.lsuInstr.ready := true.B
        when(state === State.statNormal && LSUOpcode.needwb(opcode)) {
            io.commit.valid := true.B
            io.commit.bits.data := loadData
            io.commit.bits.reg := params.rd
        }
    }
}
