package markorv.math.divider

import chisel3._
import chisel3.util._
import spire.math.Rational
import spire.implicits._

final class QuotientSelectionTable(
    val base: Int,
    val width: Int,
    val remLeadBits: Int,
    val divisorLeadBits: Int
) {
    require(
      isPow2(base) && base >= 2,
      "QST base must be a power of 2 and >= 2"
    )

    val baseWidth: Int   = log2Ceil(base)
    val qMax: Int        = base / 2
    val qIndexWidth: Int = baseWidth + 1

    val totalBits: Int = width + baseWidth
    val remBits: Int   = totalBits + 1 // + 1 for sign bit
    val hashWidth: Int = remLeadBits + divisorLeadBits - 1

    require(width > 0, "width must be positive")
    require(remLeadBits >= 1 && remLeadBits < remBits)
    require(divisorLeadBits >= 1 && divisorLeadBits <= totalBits)

    private val rho: Rational     = Rational(qMax, base - 1)
    private val srtBand: Rational = Rational(qMax) + rho

    private val quotientIntervals: Map[Int, (Rational, Rational)] =
        (-qMax to qMax).map { q =>
            q -> (Rational(q) - rho, Rational(q) + rho)
        }.toMap

    // q in [-qMax, qMax] -> qIdx in [0, base]
    private def digitToIndex(q: Int): Int = {
        require(q >= -qMax && q <= qMax)
        q + qMax
    }

    // qIdx in [0, base] -> q in [-qMax, qMax]
    private def indexToDigit(qIdx: Int): Int = {
        require(qIdx >= 0 && qIdx <= base)
        qIdx - qMax
    }

    def quotientIndexToDigit(qIdx: UInt): SInt = {
        require(qIdx.getWidth == qIndexWidth)

        val raw  = qIdx.zext - qMax.S((qIndexWidth + 1).W)
        val bits = raw.asUInt
        bits(qIndexWidth - 1, 0).asSInt
    }

    def quotientDigitToIndex(q: SInt): UInt = {
        require(q.getWidth == qIndexWidth)

        val idxFull = (q + qMax.S(qIndexWidth.W)).asUInt
        idxFull(qIndexWidth - 1, 0)
    }

    def qstHash(rem: UInt, div: UInt): UInt = {
        require(rem.getWidth == remLeadBits)
        require(div.getWidth == divisorLeadBits)

        if (divisorLeadBits == 1) rem
        else rem ## div.tail(1)
    }

    private def unsignedBits(x: BigInt, bits: Int): BigInt =
        x & ((BigInt(1) << bits) - 1)

    private def qstHashBits(remBits: BigInt, divBits: BigInt): BigInt = {
        val divTailWidth = divisorLeadBits - 1
        val divTail =
            if (divTailWidth == 0) BigInt(0)
            else divBits & ((BigInt(1) << divTailWidth) - 1)

        (remBits << divTailWidth) | divTail
    }

    private def bigIntRange(
        start: BigInt,
        endExclusive: BigInt
    ): Iterator[BigInt] =
        Iterator.iterate(start)(_ + 1).takeWhile(_ < endExclusive)

    lazy val quotientSelectionTableRaw: Array[(BigInt, Int)] = {
        val remTailBits = remBits - remLeadBits
        val divTailBits = totalBits - divisorLeadBits

        val remTailMask = (BigInt(1) << remTailBits) - 1
        val divTailMask = (BigInt(1) << divTailBits) - 1

        val remStart = -(BigInt(1) << (remLeadBits - 1))
        val remEnd   = BigInt(1) << (remLeadBits - 1)

        val divStart = BigInt(1) << (divisorLeadBits - 1)
        val divEnd   = BigInt(1) << divisorLeadBits

        (for {
            rem <- bigIntRange(remStart, remEnd)
            div <- bigIntRange(divStart, divEnd)
        } yield {
            val remMin = rem << remTailBits
            val remMax = remMin | remTailMask

            val divMin = div << divTailBits
            val divMax = divMin | divTailMask

            val cornerValues = for {
                r <- Seq(remMin, remMax)
                d <- Seq(divMin, divMax)
            } yield Rational(BigInt(base) * r, d)

            val lb = cornerValues.min
            val ub = cornerValues.max

            val clippedLb = if (lb > -srtBand) lb else -srtBand
            val clippedUb = if (ub < srtBand) ub else srtBand

            if (clippedLb > clippedUb) {
                None
            } else {
                val candidates = (-qMax to qMax).filter { q =>
                    val (lo, hi) = quotientIntervals(q)
                    clippedLb >= lo && clippedUb <= hi
                }

                require(
                  candidates.nonEmpty,
                  s"No quotient digit candidate for lead bits($rem, $div), " +
                      s"clipped interval=[$clippedLb, $clippedUb]"
                )

                val mid = (clippedLb + clippedUb) / 2
                val q = candidates.reduceLeft { (best, cand) =>
                    val bestDist = (Rational(best) - mid).abs
                    val candDist = (Rational(cand) - mid).abs

                    if (candDist < bestDist) cand
                    else if (candDist > bestDist) best
                    else if (math.abs(cand) < math.abs(best)) cand
                    else best
                }

                val remBits = unsignedBits(rem, remLeadBits)
                val divBits = unsignedBits(div, divisorLeadBits)
                val hash    = qstHashBits(remBits, divBits)

                Some(hash -> digitToIndex(q))
            }
        }).flatten.toArray
    }

    def genQuotientSelectionTable(): Array[(UInt, UInt)] =
        quotientSelectionTableRaw.map { case (hash, qIdx) =>
            hash.U(hashWidth.W) -> qIdx.U(qIndexWidth.W)
        }

    def selectQuotientIndex(rem: UInt, div: UInt): UInt =
        // default index qMax means quotient digit 0.
        // Missing entries should correspond to unreachable / out-of-SRT states.
        MuxLookup(
          qstHash(rem, div),
          qMax.U(qIndexWidth.W)
        )(genQuotientSelectionTable().toIndexedSeq)

    def onTheFlyConvA(a: UInt, b: UInt, qIdx: UInt): UInt = {
        require(a.getWidth == b.getWidth, "A and B must have same width")
        require(qIdx.getWidth == qIndexWidth)

        val outWidth = a.getWidth + baseWidth

        val table = (0 to base).map { idx =>
            val q = indexToDigit(idx)

            val result =
                if (q >= 0) {
                    a ## q.U(baseWidth.W)
                } else {
                    b ## (base + q).U(baseWidth.W)
                }

            idx.U(qIndexWidth.W) -> result
        }

        MuxLookup(qIdx, 0.U(outWidth.W))(table)
    }

    def onTheFlyConvB(a: UInt, b: UInt, qIdx: UInt): UInt = {
        require(a.getWidth == b.getWidth, "A and B must have same width")
        require(qIdx.getWidth == qIndexWidth)

        val outWidth = a.getWidth + baseWidth

        val table = (0 to base).map { idx =>
            val q = indexToDigit(idx)

            val result =
                if (q > 0) {
                    a ## (q - 1).U(baseWidth.W)
                } else {
                    b ## (base + q - 1).U(baseWidth.W)
                }

            idx.U(qIndexWidth.W) -> result
        }

        MuxLookup(qIdx, 0.U(outWidth.W))(table)
    }

    def genNegQuotientDigitTimesDivisorTable(
        d: UInt,
        outBits: Int
    ): Seq[(UInt, UInt)] = {
        require(outBits > 0)
        require(d.getWidth <= outBits)

        val dExt = d.pad(outBits)

        // 0, d, 2d, ..., qMax*d
        val posHalf: IndexedSeq[UInt] =
            Vector.tabulate(qMax + 1) { q =>
                if (q == 0) {
                    0.U(outBits.W)
                } else {
                    // For constant multiplier compiler may optimize it using shift
                    (q.U * dExt)(outBits - 1, 0)
                }
            }

        // qIdx = q + qMax, negQd table
        (0 to base).map { idx =>
            val q = indexToDigit(idx)

            val value =
                if (q > 0) {
                    // Two's-complement -(q * d)
                    ((~posHalf(q)).asUInt +& 1.U)(outBits - 1, 0)
                } else {
                    posHalf(-q)
                }

            idx.U(qIndexWidth.W) -> value
        }
    }
}
