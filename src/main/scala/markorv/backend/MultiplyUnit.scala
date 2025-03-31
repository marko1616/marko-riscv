package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend.DecoderOutParams

object MultiplyUnitFunct3Op64 extends ChiselEnum {
    val mul    = Value("b000".U)
    val mulh   = Value("b001".U)
    val mulhsu = Value("b010".U)
    val mulhu  = Value("b011".U)
    val div    = Value("b100".U)
    val divu   = Value("b101".U)
    val rem    = Value("b110".U)
    val remu   = Value("b111".U)
}

object MultiplyUnitFunct3Op32 extends ChiselEnum {
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

class DivideParams extends Bundle {
    val src1 = UInt(64.W)
    val src2 = UInt(64.W)
    val sign = Bool()
}

class DivideCacheParams extends Bundle {
    val src1 = UInt(64.W)
    val src2 = UInt(64.W)
    val sign = Bool()
    val op32 = Bool()
}

class DivideResult extends Bundle {
    val quotient = UInt(64.W)
    val remainder = UInt(64.W)
}

class DivideCache extends Bundle {
    // Zero init is not valid due to 0/0 != 0
    val valid = Bool()
    val params = new DivideCacheParams
    val result = new DivideResult
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
        state := 0.U
    }
}

class NonRestoringDivider extends Module {
    val io = IO(new Bundle {
        val src = Flipped(Decoupled(new DivideParams))
        val result = Decoupled(new DivideResult)
        val idle = Output(Bool())
    })
    object DividerState extends ChiselEnum {
        val stat_idle    = Value
        val stat_compute = Value
        val stat_finish  = Value
    }
    val sign = io.src.bits.sign
    val raw_dividend = io.src.bits.src1
    val raw_divisor = io.src.bits.src2
    val raw_dividend_sign = Mux(sign, raw_dividend.asSInt < 0.S, false.B)
    val raw_divisor_sign = Mux(sign, raw_divisor.asSInt < 0.S, false.B)
    val dividend = Mux(raw_dividend_sign, (-(raw_dividend.asSInt)).asUInt, raw_dividend)
    val divisor = Mux(raw_divisor_sign, (-(raw_divisor.asSInt)).asUInt, raw_divisor)
    val quotient = RegInit(0.U(64.W))
    val remainder = RegInit(0.S(65.W))
    val divisor_shift = RegInit(0.U(6.W))

    val state = RegInit(DividerState.stat_idle)
    val idle = state === DividerState.stat_idle
    io.idle := idle
    io.src.ready := idle
    io.result.valid := state === DividerState.stat_finish
    io.result.bits := new DivideResult().zero

    when(state === DividerState.stat_idle) {
        val division_by_zero = divisor === 0.U
        val divisor_larger_than_dividend = divisor > dividend
        when(io.src.valid) {
            when(division_by_zero) {
                io.result.valid := true.B
                io.result.bits.quotient := "hffffffffffffffff".U
                io.result.bits.remainder := raw_dividend
            }.elsewhen(divisor_larger_than_dividend) {
                io.result.valid := true.B
                io.result.bits.quotient := 0.U
                io.result.bits.remainder := raw_dividend
            }.otherwise {
                quotient := 0.U
                remainder := dividend.zexts(65)
                val dividend_leading_zero = PriorityEncoder(Reverse(dividend))
                val divisor_leading_zero = PriorityEncoder(Reverse(divisor))
                divisor_shift := divisor_leading_zero - dividend_leading_zero
                state := DividerState.stat_compute
            }
        }
    }.elsewhen(state === DividerState.stat_compute) {
        val shifted_divisor = divisor << divisor_shift
        val shifted_quotient = 1.U << divisor_shift
        val is_remainder_neg = remainder < 0.S
        remainder := Mux(is_remainder_neg, remainder+shifted_divisor.zexts(65), remainder-shifted_divisor.zexts(65))
        quotient := Mux(is_remainder_neg, quotient-shifted_quotient, quotient+shifted_quotient)
        when(divisor_shift === 0.U) {
            state := DividerState.stat_finish
        }.otherwise {
            divisor_shift := divisor_shift - 1.U
        }
    }.elsewhen(state === DividerState.stat_finish) {
        val neg_remainder = remainder < 0.S
        val adjusted_remainder = Mux(neg_remainder, remainder+divisor.zexts(65), remainder).asUInt
        val adjusted_quotient = Mux(neg_remainder, quotient-1.U, quotient)
        io.result.valid := true.B
        io.result.bits.quotient := Mux(raw_dividend_sign =/= raw_divisor_sign, adjusted_quotient.neg ,adjusted_quotient)
        io.result.bits.remainder := Mux(sign && raw_dividend_sign, adjusted_remainder.neg, adjusted_remainder)
        state := DividerState.stat_idle
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
    val divider = Module(new NonRestoringDivider)
    val mul_cache = RegInit((new MultiplyCache).zero)
    val div_cache = RegInit((new DivideCache).zero)
    val is_div = io.mu_instr.bits.mu_opcode.funct3(2)
    val op32 = io.mu_instr.bits.mu_opcode.op32
    val funct3 = io.mu_instr.bits.mu_opcode.funct3
    val (op64_funct3, op64_funct3_valid) = MultiplyUnitFunct3Op64.safe(funct3)
    val (op32_funct3, op32_funct3_valid) = MultiplyUnitFunct3Op32.safe(funct3)
    val funct3_valid = Mux(op32, op32_funct3_valid, op64_funct3_valid)

    io.outfire := false.B
    io.mu_instr.ready := io.register_commit.ready && booth4.io.idle && divider.io.idle
    io.register_commit.valid := false.B
    io.register_commit.bits := new RegisterCommit().zero

    booth4.io.src.valid := false.B
    booth4.io.src.bits := new MultiplyParams().zero
    booth4.io.result.ready := true.B

    divider.io.src.valid := false.B
    divider.io.src.bits := new DivideParams().zero
    divider.io.result.ready := true.B

    when(is_div && funct3_valid) {
        val sign = Mux(op32,
        MuxLookup(op32_funct3, false.B)(Seq(
            MultiplyUnitFunct3Op32.divw     -> true.B,
            MultiplyUnitFunct3Op32.remw     -> true.B,
        )),
        MuxLookup(op64_funct3, false.B)(Seq(
            MultiplyUnitFunct3Op64.div      -> true.B,
            MultiplyUnitFunct3Op64.rem      -> true.B,
        )))
        val div_cache_available = div_cache.params.src1 === io.mu_instr.bits.params.source1 &&
                            div_cache.params.src2 === io.mu_instr.bits.params.source2 &&
                            div_cache.params.sign === sign &&
                            div_cache.params.op32 === op32 &&
                            div_cache.valid

        when(io.mu_instr.valid) {
            when(!div_cache_available) {
                val src1 = Wire(UInt(64.W))
                val src2 = Wire(UInt(64.W))

                when(io.mu_instr.bits.mu_opcode.op32) {
                    src1 := Mux(sign,io.mu_instr.bits.params.source1(31, 0).sextu(64),io.mu_instr.bits.params.source1(31, 0).zextu(64))
                    src2 := Mux(sign,io.mu_instr.bits.params.source2(31, 0).sextu(64),io.mu_instr.bits.params.source2(31, 0).zextu(64))
                }.otherwise {
                    src1 := io.mu_instr.bits.params.source1
                    src2 := io.mu_instr.bits.params.source2
                }

                divider.io.src.valid := true.B
                divider.io.src.bits.src1 := src1
                divider.io.src.bits.src2 := src2
                divider.io.src.bits.sign := sign
            }
        }

        when(divider.io.result.valid || (io.mu_instr.valid && div_cache_available)) {
            io.outfire := true.B
            io.register_commit.valid := true.B
            io.register_commit.bits.reg := io.mu_instr.bits.params.rd
            val result = Mux(divider.io.result.valid, divider.io.result.bits, div_cache.result)
            val final_result = Mux(op32,
            MuxLookup(op32_funct3, result.remainder(31,0).sextu(64))(Seq(
                MultiplyUnitFunct3Op32.divw  -> result.quotient(31,0).sextu(64),
                MultiplyUnitFunct3Op32.divuw -> result.quotient(31,0).sextu(64)
            )),
            MuxLookup(op64_funct3, result.remainder)(Seq(
                MultiplyUnitFunct3Op64.div  -> result.quotient,
                MultiplyUnitFunct3Op64.divu -> result.quotient
            )))
            io.register_commit.bits.data := final_result
            when(divider.io.result.valid) {
                div_cache.params.src1 := io.mu_instr.bits.params.source1
                div_cache.params.src2 := io.mu_instr.bits.params.source2
                div_cache.params.sign := sign
                div_cache.params.op32 := op32
                div_cache.result := divider.io.result.bits
                div_cache.valid := true.B
            }
        }
    }.elsewhen(funct3_valid) {
        val sign = Mux(op32,
        MuxLookup(op32_funct3, "b00".U)(Seq(
            MultiplyUnitFunct3Op32.mulw     -> "b11".U
        )),
        MuxLookup(op64_funct3, "b00".U)(Seq(
            MultiplyUnitFunct3Op64.mul      -> "b11".U,
            MultiplyUnitFunct3Op64.mulh     -> "b11".U,
            MultiplyUnitFunct3Op64.mulhsu   -> "b01".U,
            MultiplyUnitFunct3Op64.mulhu    -> "b00".U
        )))
        val mul_cache_available = mul_cache.params.src1 === io.mu_instr.bits.params.source1 &&
                            mul_cache.params.src2 === io.mu_instr.bits.params.source2 &&
                            mul_cache.params.sign === sign &&
                            mul_cache.params.op32 === op32

        when(io.mu_instr.valid) {
            when(!mul_cache_available) {
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
        when(booth4.io.result.valid || (io.mu_instr.valid && mul_cache_available)) {
            io.outfire := true.B
            io.register_commit.valid := true.B
            io.register_commit.bits.reg := io.mu_instr.bits.params.rd
            val result = Mux(booth4.io.result.valid, booth4.io.result.bits, mul_cache.result)
            val final_result = Mux(op32,
            result(31, 0).sextu(64),
            Mux(op64_funct3 === MultiplyUnitFunct3Op64.mul, result(63, 0), result(127, 64)))
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
}
