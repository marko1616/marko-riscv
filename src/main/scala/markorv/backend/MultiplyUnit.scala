package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend.DecoderOutParams

object MUFunct3Norm extends ChiselEnum {
    val mul    = Value("b000".U)
    val mulh   = Value("b001".U)
    val mulhsu = Value("b010".U)
    val mulhu  = Value("b011".U)
    val div    = Value("b100".U)
    val divu   = Value("b101".U)
    val rem    = Value("b110".U)
    val remu   = Value("b111".U)
}

object MUFunct3Op32 extends ChiselEnum {
    val mulw   = Value("b000".U)
	val divw   = Value("b100".U)
	val divuw  = Value("b101".U)
	val remw   = Value("b110".U)
	val remuw  = Value("b111".U)
}

class MultiplyParams extends Bundle {
    val src1 = UInt(64.W)
    val src2 = UInt(64.W)
    val sign = UInt(2.W)
}

class MultiplyCacheParams extends Bundle {
    val src1 = UInt(64.W)
    val src2 = UInt(64.W)
    val sign = UInt(2.W)
    val op32 = Bool()
}

class MultiplyCache extends Bundle {
    val params = new MultiplyCacheParams
    val result = UInt(128.W)
}

class MUOpcode extends Bundle {
    val op32 = Bool()
    val funct3  = UInt(3.W)
}

class Booth4 extends Module {
    val io = IO(new Bundle {
        val src = Flipped(Decoupled(new MultiplyParams))
        val result = Decoupled(UInt(128.W))
        val idle = Output(Bool())
    })
    val state = RegInit(0.U(5.W))
    val return_flag = RegInit(false.B)
    val step_flag = WireInit(false.B)
    val partial_product = WireInit(0.S(128.W))
    val accumulator = RegInit(0.S(128.W))
    val padded_src1_reg = RegInit(0.S(67.W))
    val padded_src2_reg = RegInit(0.S(66.W))
    val padded_src1 = WireInit(0.S(67.W))
    val padded_src2 = WireInit(0.S(66.W))
    val idle = state === 0.U && ~return_flag

    val sign = io.src.bits.sign
    val src1 = io.src.bits.src1
    val src2 = io.src.bits.src2

    io.src.ready := idle
    io.result.valid := false.B
    io.result.bits := 0.U
    io.idle := idle

    when(idle) {
        when(sign(0)) {
            padded_src1 := (src1.sexts(67) << 1.U)
        }.otherwise {
            padded_src1 := (src1.zexts(67) << 1.U).asSInt
        }

        when(sign(1)) {
            padded_src2 := src2.sexts(66)
        }.otherwise {
            padded_src2 := src2.zexts(66)
        }
    }.otherwise {
        padded_src1 := padded_src1_reg
        padded_src2 := padded_src2_reg
    }

    when(io.src.valid | state =/= 0.U) {
        step_flag := true.B
    }

    when(step_flag | return_flag) {
        partial_product := MuxLookup(padded_src1(2, 0), 0.S(128.W))(Seq(
            1.U -> (padded_src2),
            2.U -> (padded_src2),
            3.U -> (padded_src2 << 1.U),
            4.U -> -(padded_src2 << 1.U),
            5.U -> -(padded_src2),
            6.U -> -(padded_src2),
        ))
    }

    when(step_flag) {
        accumulator := accumulator + (partial_product << (state << 1.U))
        padded_src1_reg := padded_src1 >> 2.U
        padded_src2_reg := padded_src2
        state := state + 1.U
        when(state === 31.U) {
            return_flag := true.B
        }
        when(padded_src1_reg === 0.S && ~idle) {
            return_flag := true.B
            state := 0.U
        }
    }

    when(return_flag) {
        return_flag := false.B
        io.result.valid := true.B
        io.result.bits := (accumulator + (partial_product << 64.U)).asUInt
        accumulator := 0.S
    }
}

class MultiplyUnit extends Module {
    val io = IO(new Bundle {
        val mu_instr = Flipped(Decoupled(new Bundle {
            val mu_opcode = new MUOpcode
            val params = new DecoderOutParams(64)
        }))
        val register_commit = Decoupled(new RegisterCommit)
        val outfire = Output(Bool())
    })
    val booth4 = Module(new Booth4)
    val mul_cache = RegInit((new MultiplyCache).zero)
    val mul_type = RegInit(0.U(3.W))
    val op32 = RegInit(false.B)
    val sign = Wire(UInt(2.W))
    sign := MuxLookup(io.mu_instr.bits.mu_opcode.funct3, "b00".U)(Seq(
        MUFunct3Norm.mul.litValue.U    -> "b11".U,
        MUFunct3Norm.mulh.litValue.U   -> "b11".U,
        MUFunct3Norm.mulhsu.litValue.U -> "b01".U,
        MUFunct3Norm.mulhu.litValue.U  -> "b00".U
    ))
    when(io.mu_instr.bits.mu_opcode.op32 &&
            io.mu_instr.bits.mu_opcode.funct3 === MUFunct3Op32.mulw.litValue.U) {
        sign := "b11".U
    }
    val can_use_cache = mul_cache.params.src1 === io.mu_instr.bits.params.source1 &&
                        mul_cache.params.src2 === io.mu_instr.bits.params.source2 &&
                        mul_cache.params.sign === sign &&
                        mul_cache.params.op32 === op32

    io.outfire := false.B
    io.mu_instr.ready := io.register_commit.ready && booth4.io.idle
    io.register_commit.valid := false.B
    io.register_commit.bits := new RegisterCommit().zero

    booth4.io.src.valid := false.B
    booth4.io.src.bits.sign := 0.U
    booth4.io.src.bits.src1 := 0.U
    booth4.io.src.bits.src2 := 0.U
    booth4.io.result.ready := true.B

    when(io.mu_instr.valid && booth4.io.src.ready) {
        mul_type := io.mu_instr.bits.mu_opcode.funct3
        op32 := io.mu_instr.bits.mu_opcode.op32
        when(!can_use_cache) {
            booth4.io.src.valid := true.B
            booth4.io.src.bits.sign := sign
            val src1 = Wire(UInt(64.W))
            val src2 = Wire(UInt(64.W))

            when(io.mu_instr.bits.mu_opcode.op32) {
                src1 := io.mu_instr.bits.params.source1(31, 0).sextu(64)
                src2 := io.mu_instr.bits.params.source2(31, 0).sextu(64)
            }.otherwise {
                src1 := io.mu_instr.bits.params.source1
                src2 := io.mu_instr.bits.params.source2
            }

            booth4.io.src.bits.src1 := src1
            booth4.io.src.bits.src2 := src2
        }
    }
    when(booth4.io.result.valid || (io.mu_instr.valid && can_use_cache)) {
        io.outfire := true.B
        io.register_commit.valid := true.B
        io.register_commit.bits.reg := io.mu_instr.bits.params.rd
        val result = Mux(booth4.io.result.valid, booth4.io.result.bits, mul_cache.result)
        val final_result = Wire(UInt(64.W))

        final_result := MuxLookup(mul_type, result(63, 0))(Seq(
            MUFunct3Norm.mul.litValue.U    -> result(63, 0),
            MUFunct3Norm.mulh.litValue.U   -> result(127, 64),
            MUFunct3Norm.mulhsu.litValue.U -> result(127, 64),
            MUFunct3Norm.mulhu.litValue.U  -> result(127, 64)
        ))
        when(op32 && mul_type === MUFunct3Op32.mulw.litValue.U) {
            final_result := result(31, 0).sextu(64)
        }
        io.register_commit.bits.data := final_result
        when(booth4.io.result.valid) {
            mul_cache.params.src1 := io.mu_instr.bits.params.source1
            mul_cache.params.src2 := io.mu_instr.bits.params.source2
            mul_cache.params.sign := sign
            mul_cache.params.op32 := op32
            mul_cache.result := booth4.io.result.bits
        }
    }
}
