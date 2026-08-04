package markorv.math

import chisel3._
import chisel3.util._

object OverflowChecker {
    def apply(a: UInt, b: UInt): Bool = {
        require(a.getWidth == b.getWidth, "A and B must have same width")
        require(a.getWidth > 0, "Width must be positive")

        def impl(a: UInt, b: UInt): (Bool, Bool) = {
            require(a.getWidth == b.getWidth, "A and B must have same width")

            val width = a.getWidth

            if (width == 1) {
                val bitA = a(0).asBool
                val bitB = b(0).asBool

                // or gate may faster than xor
                val prop = bitA | bitB
                val gen  = bitA & bitB

                (prop, gen)
            } else {
                val widthSubA = width / 2

                val aLow = a(widthSubA - 1, 0)
                val bLow = b(widthSubA - 1, 0)

                val aHigh = a(width - 1, widthSubA)
                val bHigh = b(width - 1, widthSubA)

                val (pLow, gLow)   = impl(aLow, bLow)
                val (pHigh, gHigh) = impl(aHigh, bHigh)

                val prop = pHigh & pLow
                val gen  = gHigh | (pHigh & gLow)

                (prop, gen)
            }
        }

        val (_, carryOut) = impl(a, b)
        carryOut
    }
}
