package markorv.utils

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._

object CountLeadingZeros {
    def apply(value: UInt): UInt = {
        if (value.getWidth == 1) {
            return ~value
        }

        val inputWidth = value.getWidth
        val maxDepth = log2Ceil(inputWidth)

        def countZeros(depth: Int, value: UInt): (UInt, Bool) = {
            val width = value.getWidth
            // Base case when width == 2
            if(depth == maxDepth - 1) {
                // For 2-bit input:
                // 01 => 1 leading zero
                // 10 or 11 => 0 leading zeros
                // 00 => 0 leading zeros, but no '1'
                return (Mux(value(1) === 0.U && value(0) === 1.U, 1.U(1.W) ,0.U(1.W)), value(1) || value(0))
            } else {
                // Split the input into upper and lower halves
                val halfWidth = width / 2
                val upperHalf = value(width - 1, halfWidth)
                val lowerHalf = value(halfWidth - 1, 0)

                // Recursively calculate for both halves
                val (upperLeadingZeros, upperHaveOnes) = countZeros(depth + 1, upperHalf)
                val (lowerLeadingZeros, lowerHaveOnes) = countZeros(depth + 1, lowerHalf)

                // Combine results:
                //   - If upper has 1s: use upperLeadingZeros
                //   - If upper is all 0s and lower has 1s: add offset + use lowerLeadingZeros
                val msbBit = (upperHaveOnes ^ (upperHaveOnes || lowerHaveOnes)).asUInt
                val lzBits = Mux(
                    upperHaveOnes === 0.U && lowerHaveOnes === 1.U,
                    lowerLeadingZeros,
                    upperLeadingZeros
                )
                val leadingZeros = msbBit ## lzBits
                return (leadingZeros, upperHaveOnes || lowerHaveOnes)
            }
        }

        val paddedValue = value.zextu(1 << maxDepth)
        val (leadingZeros, _) = countZeros(0, paddedValue)

        // Adjust for total width and ensure 0 input gives full count
        val maxLzCount = (1 << maxDepth).U
        val lzResult = Mux(value === 0.U, maxLzCount, leadingZeros)
        lzResult - (inputWidth - (1 << log2Floor(inputWidth))).U
    }
}
