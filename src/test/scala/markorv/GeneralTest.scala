package markorv

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class GeneralTest extends AnyFreeSpec with Matchers {
  "Running test in memory" in {
    simulate(new MarkoRvCore()) { cpu =>
      for (i <- 0 until 32) {
        val instr_value = cpu.io.instr_now.peek().litValue
        val pc_value = cpu.io.pc.peek().litValue
        val peek_value = cpu.io.peek.peek().litValue
        println(s"Cycle $i: instr = $instr_value pc = $pc_value peek = $peek_value")
        cpu.clock.step()
      }
    }
  }
}