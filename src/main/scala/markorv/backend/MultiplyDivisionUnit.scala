package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils._
import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.frontend.DecodedParams
import markorv.manage.RegisterCommit
import markorv.manage.EXUParams

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
        val result = Valid(UInt(128.W))
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
        val result = Valid(new DivideResult)
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
                val dividendLeadingZero = CountLeadingZeros(dividend)
                val divisorLeadingZero = CountLeadingZeros(divisor)
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

class MultiplyDivisionUnit(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val muInstr = Flipped(Decoupled(new Bundle {
            val mduOpcode = new MDUOpcode
            val params = new EXUParams
        }))
        val commit = Decoupled(new MDUCommit)
        val outfire = Output(Bool())
    })

    def cacheHit(cacheSrc1: UInt, cacheSrc2: UInt, cacheSign: Data, cacheOp32: Bool,
                reqSrc1: UInt, reqSrc2: UInt, reqSign: Data, reqOp32: Bool): Bool = {
        cacheSrc1 === reqSrc1 &&
        cacheSrc2 === reqSrc2 &&
        cacheSign === reqSign &&
        cacheOp32 === reqOp32
    }

    val opcode = io.muInstr.bits.mduOpcode
    val params = io.muInstr.bits.params
    val booth4 = Module(new Booth4)
    val divider = Module(new NonRestoringDivider)
    val mulCache = RegInit((new MultiplyCache).zero)
    val divCache = RegInit((new DivideCache).zero)
    val isDiv = opcode.funct3(2)
    val op32 = opcode.op32

    val op64Funct3 = opcode.getFunct3Op64()
    val op32Funct3 = opcode.getFunct3Op32()

    io.outfire := false.B
    io.muInstr.ready := io.commit.ready && booth4.io.idle && divider.io.idle
    io.commit.valid := false.B
    io.commit.bits := new MDUCommit().zero
    io.commit.bits.robIndex := params.robIndex

    booth4.io.src.valid := false.B
    booth4.io.src.bits := new MultiplyParams().zero

    divider.io.src.valid := false.B
    divider.io.src.bits := new DivideParams().zero

    when(isDiv) {
        val sign = Mux(op32,
        MuxLookup(op32Funct3, false.B)(Seq(
            MultiplyDivisionUnitFunct3Op32.divw     -> true.B,
            MultiplyDivisionUnitFunct3Op32.remw     -> true.B,
        )),
        MuxLookup(op64Funct3, false.B)(Seq(
            MultiplyDivisionUnitFunct3Op64.div      -> true.B,
            MultiplyDivisionUnitFunct3Op64.rem      -> true.B,
        )))
        val divCacheAvailable = cacheHit(
            divCache.params.src1, divCache.params.src2, divCache.params.sign, divCache.params.op32,
            params.source1, params.source2, sign, op32
        )

        when(io.muInstr.valid) {
            when(!divCacheAvailable) {
                val src1 = Wire(UInt(64.W))
                val src2 = Wire(UInt(64.W))

                when(opcode.op32) {
                    src1 := Mux(sign,params.source1(31, 0).sextu(64),params.source1(31, 0).zextu(64))
                    src2 := Mux(sign,params.source2(31, 0).sextu(64),params.source2(31, 0).zextu(64))
                }.otherwise {
                    src1 := params.source1
                    src2 := params.source2
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
            val result = Mux(divider.io.result.valid, divider.io.result.bits, divCache.result)
            val finalResult = Mux(op32,
            MuxLookup(op32Funct3, result.remainder(31,0).sextu(64))(Seq(
                MultiplyDivisionUnitFunct3Op32.divw  -> result.quotient(31,0).sextu(64),
                MultiplyDivisionUnitFunct3Op32.divuw -> result.quotient(31,0).sextu(64)
            )),
            MuxLookup(op64Funct3, result.remainder)(Seq(
                MultiplyDivisionUnitFunct3Op64.div  -> result.quotient,
                MultiplyDivisionUnitFunct3Op64.divu -> result.quotient
            )))
            io.commit.bits.data := finalResult
            when(divider.io.result.valid) {
                divCache.params.src1 := params.source1
                divCache.params.src2 := params.source2
                divCache.params.sign := sign
                divCache.params.op32 := op32
                divCache.result := divider.io.result.bits
                divCache.valid := true.B
            }
        }
    }.otherwise {
        val sign = Mux(op32,
        MuxLookup(op32Funct3, "b00".U)(Seq(
            MultiplyDivisionUnitFunct3Op32.mulw   -> "b11".U
        )),
        MuxLookup(op64Funct3, "b00".U)(Seq(
            MultiplyDivisionUnitFunct3Op64.mul    -> "b11".U,
            MultiplyDivisionUnitFunct3Op64.mulh   -> "b11".U,
            MultiplyDivisionUnitFunct3Op64.mulhsu -> "b01".U,
            MultiplyDivisionUnitFunct3Op64.mulhu  -> "b00".U
        )))
        val mulCacheAvailable = cacheHit(
            mulCache.params.src1, mulCache.params.src2, mulCache.params.sign, mulCache.params.op32,
            params.source1, params.source2, sign, op32
        )
        when(io.muInstr.valid) {
            when(!mulCacheAvailable) {
                booth4.io.src.valid := true.B
                booth4.io.src.bits.sign := sign
                val src1 = Wire(UInt(64.W))
                val src2 = Wire(UInt(64.W))

                when(opcode.op32) {
                    src1 := params.source1(31, 0).sextu(64)
                    src2 := params.source2(31, 0).sextu(64)
                }.otherwise {
                    src1 := params.source1
                    src2 := params.source2
                }

                booth4.io.src.bits.src1 := src1
                booth4.io.src.bits.src2 := src2
            }
        }
        when(booth4.io.result.valid || (io.muInstr.valid && mulCacheAvailable)) {
            io.outfire := true.B
            io.commit.valid := true.B
            val result = Mux(booth4.io.result.valid, booth4.io.result.bits, mulCache.result)
            val finalResult = Mux(op32,
            result(31, 0).sextu(64),
            Mux(op64Funct3 === MultiplyDivisionUnitFunct3Op64.mul, result(63, 0), result(127, 64)))
            io.commit.bits.data := finalResult
            when(booth4.io.result.valid) {
                mulCache.params.src1 := params.source1
                mulCache.params.src2 := params.source2
                mulCache.params.sign := sign
                mulCache.params.op32 := op32
                mulCache.result := booth4.io.result.bits
            }
        }
    }
}
