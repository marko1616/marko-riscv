package markorv.utils

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.util.Random

class CLZTest extends Module {
    val io = IO(new Bundle {
        val in = Input(UInt(32.W))
        val out = Output(UInt(6.W))
        val reference = Output(UInt(6.W))
    })
    io.out := CountLeadingZeros(io.in)
    val reversed = Reverse(io.in)
    val hasOne = reversed.orR
    io.reference := Mux(hasOne, PriorityEncoder(reversed), 32.U)
}

class CountLeadingZerosRandomTest extends AnyFreeSpec with Matchers {
    "It should match reference implementation with random inputs" in {
        simulate(new CLZTest) { dut =>
            val random = new Random(42)

            dut.io.in.poke(0.U)
            dut.clock.step(1)
            dut.io.out.expect(dut.io.reference.peek())

            for (i <- 0 until 32) {
                val value = BigInt(1) << i
                dut.io.in.poke(value.U)
                dut.clock.step(1)
                dut.io.out.expect(dut.io.reference.peek())
            }

            val numRandomTests = 1000
            println(s"\nRunning $numRandomTests random tests...")

            for (i <- 0 until numRandomTests) {
                val randomValue = BigInt(32, random)
                dut.io.in.poke(randomValue.U)
                dut.clock.step(1)

                val ourResult = dut.io.out.peek().litValue
                val refResult = dut.io.reference.peek().litValue

                dut.io.out.expect(dut.io.reference.peek())
            }

            val specialPatterns = Seq(
                BigInt("10101010101010101010101010101010", 2),
                BigInt("01010101010101010101010101010101", 2),
                BigInt("11111111111111110000000000000000", 2),
                BigInt("00000000000000001111111111111111", 2),
                BigInt("10000000000000000000000000000000", 2),
                BigInt("11111111111111111111111111111111", 2)
            )

            println("\nTesting special patterns...")
            for (pattern <- specialPatterns) {
                dut.io.in.poke(pattern.U)
                dut.clock.step(1)
                dut.io.out.expect(dut.io.reference.peek())
            }
        }
    }
}
