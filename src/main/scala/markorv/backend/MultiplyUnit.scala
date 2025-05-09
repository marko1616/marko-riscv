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

class Booth4 extends Module {
    val io = IO(new Bundle {
        val src = Flipped(Decoupled(new MultiplyParams))
        val result = Decoupled(UInt(128.W))
        val idle = Output(Bool())
    })
    val state = RegInit(0.U(5.W))
    val returnFlag = RegInit(false.B)
    val stepFlag = WireInit(false.B)
    val partialProduct = WireInit(0.S(128.W))
    val accumulator = RegInit(0.S(128.W))
    val paddedSrc1Reg = RegInit(0.S(67.W))
    val paddedSrc2Reg = RegInit(0.S(66.W))
    val paddedSrc1 = WireInit(0.S(67.W))
    val paddedSrc2 = WireInit(0.S(66.W))
    val idle = state === 0.U && ~returnFlag

    val sign = io.src.bits.sign
    val src1 = io.src.bits.src1
    val src2 = io.src.bits.src2

    io.src.ready := idle
    io.result.valid := false.B
    io.result.bits := 0.U
    io.idle := idle

    when(idle) {
        when(sign(0)) {
            paddedSrc1 := (src1.sexts(67) << 1.U)
        }.otherwise {
            paddedSrc1 := (src1.zexts(67) << 1.U).asSInt
        }

        when(sign(1)) {
            paddedSrc2 := src2.sexts(66)
        }.otherwise {
            paddedSrc2 := src2.zexts(66)
        }
    }.otherwise {
        paddedSrc1 := paddedSrc1Reg
        paddedSrc2 := paddedSrc2Reg
    }

    when(io.src.valid | state =/= 0.U) {
        stepFlag := true.B
    }

    when(stepFlag | returnFlag) {
        partialProduct := MuxLookup(paddedSrc1(2, 0), 0.S(128.W))(Seq(
            1.U -> (paddedSrc2),
            2.U -> (paddedSrc2),
            3.U -> (paddedSrc2 << 1.U),
            4.U -> -(paddedSrc2 << 1.U),
            5.U -> -(paddedSrc2),
            6.U -> -(paddedSrc2),
        ))
    }

    when(stepFlag) {
        accumulator := accumulator + (partialProduct << (state << 1.U))
        paddedSrc1Reg := paddedSrc1 >> 2.U
        paddedSrc2Reg := paddedSrc2
        state := state + 1.U
        when(state === 31.U) {
            returnFlag := true.B
        }
        when(paddedSrc1Reg === 0.S && ~idle) {
            returnFlag := true.B
            state := 0.U
        }
    }

    when(returnFlag) {
        returnFlag := false.B
        io.result.valid := true.B
        io.result.bits := (accumulator + (partialProduct << 64.U)).asUInt
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
        val statIdle    = Value
        val statCompute = Value
        val statFinish  = Value
    }
    val sign = io.src.bits.sign
    val rawDividend = io.src.bits.src1
    val rawDivisor = io.src.bits.src2
    val rawDividendSign = Mux(sign, rawDividend.asSInt < 0.S, false.B)
    val rawDivisorSign = Mux(sign, rawDivisor.asSInt < 0.S, false.B)
    val dividend = Mux(rawDividendSign, (-(rawDividend.asSInt)).asUInt, rawDividend)
    val divisor = Mux(rawDivisorSign, (-(rawDivisor.asSInt)).asUInt, rawDivisor)
    val quotient = RegInit(0.U(64.W))
    val remainder = RegInit(0.S(65.W))
    val divisorShift = RegInit(0.U(6.W))

    val state = RegInit(DividerState.statIdle)
    val idle = state === DividerState.statIdle
    io.idle := idle
    io.src.ready := idle
    io.result.valid := state === DividerState.statFinish
    io.result.bits := new DivideResult().zero

    when(state === DividerState.statIdle) {
        val divisionByZero = divisor === 0.U
        val divisorLargerThanDividend = divisor > dividend
        when(io.src.valid) {
            when(divisionByZero) {
                io.result.valid := true.B
                io.result.bits.quotient := "hffffffffffffffff".U
                io.result.bits.remainder := rawDividend
            }.elsewhen(divisorLargerThanDividend) {
                io.result.valid := true.B
                io.result.bits.quotient := 0.U
                io.result.bits.remainder := rawDividend
            }.otherwise {
                quotient := 0.U
                remainder := dividend.zexts(65)
                val dividendLeadingZero = PriorityEncoder(Reverse(dividend))
                val divisorLeadingZero = PriorityEncoder(Reverse(divisor))
                divisorShift := divisorLeadingZero - dividendLeadingZero
                state := DividerState.statCompute
            }
        }
    }.elsewhen(state === DividerState.statCompute) {
        val shiftedDivisor = divisor << divisorShift
        val shiftedQuotient = 1.U << divisorShift
        val isRemainderNeg = remainder < 0.S
        remainder := Mux(isRemainderNeg, remainder+shiftedDivisor.zexts(65), remainder-shiftedDivisor.zexts(65))
        quotient := Mux(isRemainderNeg, quotient-shiftedQuotient, quotient+shiftedQuotient)
        when(divisorShift === 0.U) {
            state := DividerState.statFinish
        }.otherwise {
            divisorShift := divisorShift - 1.U
        }
    }.elsewhen(state === DividerState.statFinish) {
        val negRemainder = remainder < 0.S
        val adjustedRemainder = Mux(negRemainder, remainder+divisor.zexts(65), remainder).asUInt
        val adjustedQuotient = Mux(negRemainder, quotient-1.U, quotient)
        io.result.valid := true.B
        io.result.bits.quotient := Mux(rawDividendSign =/= rawDivisorSign, adjustedQuotient.neg ,adjustedQuotient)
        io.result.bits.remainder := Mux(sign && rawDividendSign, adjustedRemainder.neg, adjustedRemainder)
        state := DividerState.statIdle
    }
}

class MultiplyUnit extends Module {
    val io = IO(new Bundle {
        val muInstr = Flipped(Decoupled(new Bundle {
            val muOpcode = new MUOpcode
            val params = new DecoderOutParams
        }))
        val commit = Decoupled(new CommitBundle)
        val outfire = Output(Bool())
    })
    val booth4 = Module(new Booth4)
    val divider = Module(new NonRestoringDivider)
    val mulCache = RegInit((new MultiplyCache).zero)
    val divCache = RegInit((new DivideCache).zero)
    val isDiv = io.muInstr.bits.muOpcode.funct3(2)
    val op32 = io.muInstr.bits.muOpcode.op32
    val funct3 = io.muInstr.bits.muOpcode.funct3
    val (op64Funct3, op64Funct3Valid) = MultiplyUnitFunct3Op64.safe(funct3)
    val (op32Funct3, op32Funct3Valid) = MultiplyUnitFunct3Op32.safe(funct3)
    val funct3Valid = Mux(op32, op32Funct3Valid, op64Funct3Valid)

    io.outfire := false.B
    io.muInstr.ready := io.commit.ready && booth4.io.idle && divider.io.idle
    io.commit.valid := false.B
    io.commit.bits := new CommitBundle().zero

    booth4.io.src.valid := false.B
    booth4.io.src.bits := new MultiplyParams().zero
    booth4.io.result.ready := true.B

    divider.io.src.valid := false.B
    divider.io.src.bits := new DivideParams().zero
    divider.io.result.ready := true.B

    when(isDiv && funct3Valid) {
        val sign = Mux(op32,
        MuxLookup(op32Funct3, false.B)(Seq(
            MultiplyUnitFunct3Op32.divw     -> true.B,
            MultiplyUnitFunct3Op32.remw     -> true.B,
        )),
        MuxLookup(op64Funct3, false.B)(Seq(
            MultiplyUnitFunct3Op64.div      -> true.B,
            MultiplyUnitFunct3Op64.rem      -> true.B,
        )))
        val divCacheAvailable = divCache.params.src1 === io.muInstr.bits.params.source1 &&
                            divCache.params.src2 === io.muInstr.bits.params.source2 &&
                            divCache.params.sign === sign &&
                            divCache.params.op32 === op32 &&
                            divCache.valid

        when(io.muInstr.valid) {
            when(!divCacheAvailable) {
                val src1 = Wire(UInt(64.W))
                val src2 = Wire(UInt(64.W))

                when(io.muInstr.bits.muOpcode.op32) {
                    src1 := Mux(sign,io.muInstr.bits.params.source1(31, 0).sextu(64),io.muInstr.bits.params.source1(31, 0).zextu(64))
                    src2 := Mux(sign,io.muInstr.bits.params.source2(31, 0).sextu(64),io.muInstr.bits.params.source2(31, 0).zextu(64))
                }.otherwise {
                    src1 := io.muInstr.bits.params.source1
                    src2 := io.muInstr.bits.params.source2
                }

                divider.io.src.valid := true.B
                divider.io.src.bits.src1 := src1
                divider.io.src.bits.src2 := src2
                divider.io.src.bits.sign := sign
            }
        }

        when(divider.io.result.valid || (io.muInstr.valid && divCacheAvailable)) {
            io.outfire := true.B
            io.commit.valid := true.B
            io.commit.bits.reg := io.muInstr.bits.params.rd
            val result = Mux(divider.io.result.valid, divider.io.result.bits, divCache.result)
            val finalResult = Mux(op32,
            MuxLookup(op32Funct3, result.remainder(31,0).sextu(64))(Seq(
                MultiplyUnitFunct3Op32.divw  -> result.quotient(31,0).sextu(64),
                MultiplyUnitFunct3Op32.divuw -> result.quotient(31,0).sextu(64)
            )),
            MuxLookup(op64Funct3, result.remainder)(Seq(
                MultiplyUnitFunct3Op64.div  -> result.quotient,
                MultiplyUnitFunct3Op64.divu -> result.quotient
            )))
            io.commit.bits.data := finalResult
            when(divider.io.result.valid) {
                divCache.params.src1 := io.muInstr.bits.params.source1
                divCache.params.src2 := io.muInstr.bits.params.source2
                divCache.params.sign := sign
                divCache.params.op32 := op32
                divCache.result := divider.io.result.bits
                divCache.valid := true.B
            }
        }
    }.elsewhen(funct3Valid) {
        val sign = Mux(op32,
        MuxLookup(op32Funct3, "b00".U)(Seq(
            MultiplyUnitFunct3Op32.mulw     -> "b11".U
        )),
        MuxLookup(op64Funct3, "b00".U)(Seq(
            MultiplyUnitFunct3Op64.mul      -> "b11".U,
            MultiplyUnitFunct3Op64.mulh     -> "b11".U,
            MultiplyUnitFunct3Op64.mulhsu   -> "b01".U,
            MultiplyUnitFunct3Op64.mulhu    -> "b00".U
        )))
        val mulCacheAvailable = mulCache.params.src1 === io.muInstr.bits.params.source1 &&
                            mulCache.params.src2 === io.muInstr.bits.params.source2 &&
                            mulCache.params.sign === sign &&
                            mulCache.params.op32 === op32

        when(io.muInstr.valid) {
            when(!mulCacheAvailable) {
                booth4.io.src.valid := true.B
                booth4.io.src.bits.sign := sign
                val src1 = Wire(UInt(64.W))
                val src2 = Wire(UInt(64.W))

                when(io.muInstr.bits.muOpcode.op32) {
                    src1 := io.muInstr.bits.params.source1(31, 0).sextu(64)
                    src2 := io.muInstr.bits.params.source2(31, 0).sextu(64)
                }.otherwise {
                    src1 := io.muInstr.bits.params.source1
                    src2 := io.muInstr.bits.params.source2
                }

                booth4.io.src.bits.src1 := src1
                booth4.io.src.bits.src2 := src2
            }
        }
        when(booth4.io.result.valid || (io.muInstr.valid && mulCacheAvailable)) {
            io.outfire := true.B
            io.commit.valid := true.B
            io.commit.bits.reg := io.muInstr.bits.params.rd
            val result = Mux(booth4.io.result.valid, booth4.io.result.bits, mulCache.result)
            val finalResult = Mux(op32,
            result(31, 0).sextu(64),
            Mux(op64Funct3 === MultiplyUnitFunct3Op64.mul, result(63, 0), result(127, 64)))
            io.commit.bits.data := finalResult
            when(booth4.io.result.valid) {
                mulCache.params.src1 := io.muInstr.bits.params.source1
                mulCache.params.src2 := io.muInstr.bits.params.source2
                mulCache.params.sign := sign
                mulCache.params.op32 := op32
                mulCache.result := booth4.io.result.bits
            }
        }
    }
}
