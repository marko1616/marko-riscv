package markorv.math.divider

import chisel3._
import chisel3.util._

import markorv.math.CountLeadingZeros
import markorv.math.OverflowChecker
import markorv.math.compressor.ThreeToTwoCompGroup
import markorv.utils.ChiselUtils.{
    DataOperationExtension,
    SIntOperationExtension,
    UIntOperationExtension
}

object SRTDivider {
    def latency(
        base: Int,
        width: Int,
        remLeadBits: Int,
        divisorLeadBits: Int,
        maxStage: Int
    ): Int = {
        require(
          isPow2(base) && base >= 2,
          "QST base must be a power of 2 and >= 2"
        )
        require(width > 0, "width must be positive")
        require(maxStage > 0, "maxStage must be positive")

        val baseWidth  = log2Ceil(base)
        val totalBits  = width + baseWidth
        val remBits    = totalBits + 1
        val digits     = (width + baseWidth - 1) / baseWidth
        val iterations = digits + 2

        require(remLeadBits >= 1 && remLeadBits < remBits)
        require(divisorLeadBits >= 1 && divisorLeadBits <= totalBits)

        2 + iterations / maxStage
    }
}

class SRTDivider(
    base: Int,
    width: Int,
    remLeadBits: Int,
    divisorLeadBits: Int,
    maxStage: Int
) extends Module {
    require(maxStage > 0, "maxStage must be positive")

    val io = IO(new Bundle {
        val in = Input(new Bundle {
            val dividend = SInt(width.W)
            val divisor  = SInt(width.W)
        })
        val out = Output(new Bundle {
            val quotient  = SInt(width.W)
            val remainder = SInt(width.W)
        })
    })

    private def ceilDiv(n: BigInt, d: BigInt): BigInt =
        (n + d - 1) / d

    private val qst =
        new QuotientSelectionTable(base, width, remLeadBits, divisorLeadBits)

    private val baseWidth  = qst.baseWidth
    private val totalBits  = qst.totalBits
    private val remBits    = qst.remBits
    private val digits     = (width + baseWidth - 1) / baseWidth
    private val iterations = digits + 2
    private val nDiffWidth = log2Floor(width) + 2
    private val minInt     = -(BigInt(1) << (width - 1))

    val dividend = io.in.dividend
    val divisor  = io.in.divisor

    val dividendSign = dividend(width - 1)
    val divisorSign  = divisor(width - 1)
    val quotientSign = dividendSign ^ divisorSign
    // For REM, the sign of a nonzero result equals the sign of the dividend.
    val remainderSign = dividendSign

    val dividendBits = dividend.asUInt
    val divisorBits  = divisor.asUInt

    val absDividend = Mux(
      dividendSign,
      (~dividendBits.asUInt + 1.U)(width - 1, 0),
      dividendBits
    )

    val absDivisor = Mux(
      divisorSign,
      (~divisorBits.asUInt + 1.U)(width - 1, 0),
      divisorBits
    )

    // Normalize
    val divisorLeadingZero  = CountLeadingZeros(absDivisor)
    val nDivisor            = (absDivisor << divisorLeadingZero)(width - 1, 0)
    // Maybe it is ok for now since baseWidth is hard coded so there may won't be lot's of delay here.
    val iterNum             = divisorLeadingZero / baseWidth.U // Ensure there won't be a greater rem accuracy in to correction phase
    val residualShift       = divisorLeadingZero % baseWidth.U
    val residualExtraBits = baseWidth - 1
    val hasResidualShift    = residualShift =/= 0.U

    // recurrence divisor: MSB aligned to totalBits-1, matches QST div encoding
    val d       = (nDivisor << baseWidth).asUInt
    val divLead = d(totalBits - 1, totalBits - divisorLeadBits)

    // SRT iterations + on-the-fly conversion
    val negQTimesDTable = qst.genNegQuotientDigitTimesDivisorTable(d, remBits)

    val divisorLeadingZeroWidth = divisorLeadingZero.getWidth
    val iterNumWidth            = iterNum.getWidth
    val residualShiftWidth      = residualShift.getWidth
    val nDivisorWidth           = nDivisor.getWidth
    val divLeadWidth            = divLead.getWidth

    class PipeBundle(qWidth: Int) extends Bundle {
        val remS               = UInt(remBits.W)
        val remC               = UInt(remBits.W)
        val qA                 = UInt(qWidth.W)
        val qB                 = UInt(qWidth.W)
        val iterNum            = UInt(iterNumWidth.W)
        val residualShift      = UInt(residualShiftWidth.W)
        val hasResidualShift   = Bool()
        val divisorLeadingZero = UInt(divisorLeadingZeroWidth.W)
        val nDivisor           = UInt(nDivisorWidth.W)
        val divLead            = UInt(divLeadWidth.W)
        val neqQTimesDTable       = Vec(base + 1, UInt(remBits.W))
        val dividend           = SInt(width.W)
        val quotientSign       = Bool()
        val remainderSign      = Bool()
        val divByZero          = Bool()
        val overflow           = Bool()
    }

    def pipeStage(
        msg: String,
        remSIn: UInt,
        remCIn: UInt,
        qAIn: UInt,
        qBIn: UInt,
        iterNumIn: UInt,
        residualShiftIn: UInt,
        hasResidualShiftIn: Bool,
        divisorLeadingZeroIn: UInt,
        nDivisorIn: UInt,
        divLeadIn: UInt,
        neqQTimesDTableIn: Vec[UInt],
        dividendIn: SInt,
        quotientSignIn: Bool,
        remainderSignIn: Bool,
        divByZeroIn: Bool,
        overflowIn: Bool
    ): PipeBundle = {
        println(s"[SRTDivider width=$width maxStage=$maxStage] $msg")

        val pipeIn = Wire(new PipeBundle(qAIn.getWidth))
        pipeIn.remS               := remSIn
        pipeIn.remC               := remCIn
        pipeIn.qA                 := qAIn
        pipeIn.qB                 := qBIn
        pipeIn.iterNum            := iterNumIn
        pipeIn.residualShift      := residualShiftIn
        pipeIn.hasResidualShift   := hasResidualShiftIn
        pipeIn.divisorLeadingZero := divisorLeadingZeroIn
        pipeIn.nDivisor           := nDivisorIn
        pipeIn.divLead            := divLeadIn
        pipeIn.neqQTimesDTable    := neqQTimesDTableIn
        pipeIn.dividend           := dividendIn
        pipeIn.quotientSign       := quotientSignIn
        pipeIn.remainderSign      := remainderSignIn
        pipeIn.divByZero          := divByZeroIn
        pipeIn.overflow           := overflowIn

        val pipeOut = Reg(chiselTypeOf(pipeIn))
        pipeOut := pipeIn
        pipeOut
    }

    val negQTimesDTableVec = Wire(Vec(base + 1, UInt(remBits.W)))
    for (idx <- 0 to base) {
        negQTimesDTableVec(idx) := negQTimesDTable(idx)._2
    }

    // RISC-V corner cases
    val divByZero = divisor === 0.S
    val overflow =
        (dividend === minInt.S(width.W)) && (divisor === -1.S(width.W))

    val prePipe = pipeStage(
      "register after preprocess",
      0.U((remBits - width).W) ## absDividend,
      0.U(remBits.W),
      0.U(1.W),
      1.U(1.W),
      iterNum,
      residualShift,
      hasResidualShift,
      divisorLeadingZero,
      nDivisor,
      divLead,
      negQTimesDTableVec,
      dividend,
      quotientSign,
      remainderSign,
      divByZero,
      overflow
    )

    var remS                    = prePipe.remS
    var remC                    = prePipe.remC
    var qA                      = prePipe.qA
    var qB                      = prePipe.qB
    var iterNumPipe             = prePipe.iterNum
    var residualShiftPipe       = prePipe.residualShift
    var hasResidualShiftPipe    = prePipe.hasResidualShift
    var divisorLeadingZeroPipe  = prePipe.divisorLeadingZero
    var nDivisorPipe            = prePipe.nDivisor
    var divLeadPipe             = prePipe.divLead
    var negQTimesDTablePipe     = prePipe.neqQTimesDTable
    var dividendPipe            = prePipe.dividend
    var quotientSignPipe        = prePipe.quotientSign
    var remainderSignPipe       = prePipe.remainderSign
    var divByZeroPipe           = prePipe.divByZero
    var overflowPipe            = prePipe.overflow

    for (i <- 0 until iterations) {
        val remSLsb = remS(remBits - remLeadBits - 1, 0)
        val remCLsb = remC(remBits - remLeadBits - 1, 0)
        val remSMsb = remS(remBits - 1, remBits - remLeadBits)
        val remCMsb = remC(remBits - 1, remBits - remLeadBits)
        val remLeadP0 = remSMsb + remCMsb
        val remLeadP1 = remSMsb + remCMsb + 1.U
        val remLsbOverflow = OverflowChecker(remSLsb, remCLsb)
        val remLead = Mux(remLsbOverflow, remLeadP1, remLeadP0)

        val qIdx    = qst.selectQuotientIndex(remLead, divLeadPipe)
        val aNext = qst.onTheFlyConvA(qA, qB, qIdx)
        val bNext = qst.onTheFlyConvB(qA, qB, qIdx)

        val remSShift = (remS << baseWidth)(remBits - 1, 0)
        val remCShift = (remC << baseWidth)(remBits - 1, 0)
        val negQd = MuxLookup(qIdx, 0.U(remBits.W))(
            (0 to base).map { idx =>
                idx.U(qst.qIndexWidth.W) -> negQTimesDTablePipe(idx)
            }
        )

        val c32 = Module(new ThreeToTwoCompGroup(remBits))
        c32.io.x0 := remSShift
        c32.io.x1 := remCShift
        c32.io.x2 := negQd

        remS = Mux(i.U >= iterNumPipe, remS, c32.io.sum)
        remC = Mux(i.U >= iterNumPipe, remC, (c32.io.carry << 1)(remBits - 1, 0))
        qA = Mux(i.U >= iterNumPipe, qA, aNext)
        qB = Mux(i.U >= iterNumPipe, qB, bNext)

        if ((i + 1) % maxStage == 0) {
            val iterPipe = pipeStage(
              s"register after iteration ${i + 1}",
              remS,
              remC,
              qA,
              qB,
              iterNumPipe,
              residualShiftPipe,
              hasResidualShiftPipe,
              divisorLeadingZeroPipe,
              nDivisorPipe,
              divLeadPipe,
              negQTimesDTablePipe,
              dividendPipe,
              quotientSignPipe,
              remainderSignPipe,
              divByZeroPipe,
              overflowPipe
            )

            remS                   = iterPipe.remS
            remC                   = iterPipe.remC
            qA                     = iterPipe.qA
            qB                     = iterPipe.qB
            iterNumPipe            = iterPipe.iterNum
            residualShiftPipe      = iterPipe.residualShift
            hasResidualShiftPipe   = iterPipe.hasResidualShift
            divisorLeadingZeroPipe = iterPipe.divisorLeadingZero
            nDivisorPipe           = iterPipe.nDivisor
            divLeadPipe            = iterPipe.divLead
            negQTimesDTablePipe    = iterPipe.neqQTimesDTable
            dividendPipe           = iterPipe.dividend
            quotientSignPipe       = iterPipe.quotientSign
            remainderSignPipe      = iterPipe.remainderSign
            divByZeroPipe          = iterPipe.divByZero
            overflowPipe           = iterPipe.overflow
        }
    }

    val finalPipe = pipeStage(
      "register before final correction",
      remS,
      remC,
      qA,
      qB,
      iterNumPipe,
      residualShiftPipe,
      hasResidualShiftPipe,
      divisorLeadingZeroPipe,
      nDivisorPipe,
      divLeadPipe,
      negQTimesDTablePipe,
      dividendPipe,
      quotientSignPipe,
      remainderSignPipe,
      divByZeroPipe,
      overflowPipe
    )

    // Final correction
    val finalRemBits = remBits + residualExtraBits
    val sRem = (finalPipe.remS + finalPipe.remC)(remBits - 1, 0)
    val sQuo = finalPipe.qA << baseWidth
    val finalRem = Mux(finalPipe.hasResidualShift, (sRem.sexts(finalRemBits) << finalPipe.residualShift)(finalRemBits - 1, 0).asSInt, sRem.sexts(finalRemBits))
    val finalQuo = Mux(finalPipe.hasResidualShift, sQuo << finalPipe.residualShift, sQuo)
    // Goal: 0 <= finalRem < divisor if dividend >= 0
    // Goal: -divisor < finalRem <= 0 if dividend < 0
    val beta = BigInt(base)
    val a    = BigInt(qst.qMax)
    val gammaMax = BigInt(1) << (baseWidth - 1)
    val K = ceilDiv(gammaMax * beta * a, beta - 1).toInt
    val N = 2 * K + 1
    val cw = finalRemBits + log2Ceil(N) + 2

    val frExt = finalRem.sexts(cw)
    val nDivS = finalPipe.nDivisor.zexts(cw)

    val offSeq = for (j <- 0 until N) yield {
        val c   = -K + j
        val off = (c.S((log2Ceil(K) + 2).W) * nDivS).asSInt
        off
    }

    val signSeq = for (j <- 0 until N) yield {
        val off = offSeq(j)

        val carry   = OverflowChecker(frExt.asUInt(cw - 2, 0), off.asUInt(cw - 2, 0))
        val signBit = frExt(cw - 1) ^ off(cw - 1) ^ carry

        signBit
    }
    val negBits = Cat(signSeq)

    val m     = CountLeadingZeros(~negBits)
    val cStar = m.zext - K.S
    val jStar = m

    val offSelected = MuxLookup(jStar, 0.S(cw.W))(
        (0 until N).map { j =>
            j.U(jStar.getWidth.W) -> offSeq(j)
        }
    )

    val corr = (frExt + offSelected).asUInt(cw - 1, 0)
    val magR = (corr >> finalPipe.divisorLeadingZero)(width - 1, 0)
    val magQ = finalQuo.zext - cStar

    val quotientNorm  = Mux(finalPipe.quotientSign,  (-magQ)(width - 1, 0).asSInt, magQ(width - 1, 0).asSInt)
    val remainderNorm = Mux(finalPipe.remainderSign, (-magR.zext).asSInt,          magR.zext.asSInt)

    io.out.quotient := Mux(
      finalPipe.divByZero,
      -1.S(width.W),
      Mux(finalPipe.overflow, minInt.S(width.W), quotientNorm)
    )
    io.out.remainder := Mux(
      finalPipe.divByZero,
      finalPipe.dividend,
      Mux(finalPipe.overflow, 0.S(width.W), remainderNorm)
    )
}
