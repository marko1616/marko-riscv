package markorv.math.compressor

import chisel3._
import chisel3.util._

class FourToTwoComp extends Module {
    val io = IO(new Bundle {
        val cin = Input(Bool())
        val x0  = Input(Bool())
        val x1  = Input(Bool())
        val x2  = Input(Bool())
        val x3  = Input(Bool())

        val cout  = Output(Bool())
        val sum   = Output(Bool())
        val carry = Output(Bool())
    })

    val fa1HalfSum1 = io.x0 ^ io.x1
    val fa1HalfSum2 = fa1HalfSum1 ^ io.x2
    val fa1Carry1   = io.x0 & io.x1
    val fa1Carry2   = fa1HalfSum1 & io.x2
    val fa1Carry    = fa1Carry1 | fa1Carry2

    val fa2HalfSum1 = fa1HalfSum2 ^ io.x3
    val fa2HalfSum2 = fa2HalfSum1 ^ io.cin
    val fa2Carry1   = fa1HalfSum2 & io.x3
    val fa2Carry2   = fa2HalfSum1 & io.cin
    val fa2Carry    = fa2Carry1 | fa2Carry2

    io.cout  := fa1Carry
    io.sum   := fa2HalfSum2
    io.carry := fa2Carry
}

class ThreeToTwoComp extends Module {
    val io = IO(new Bundle {
        val x0    = Input(Bool())
        val x1    = Input(Bool())
        val x2    = Input(Bool())
        val sum   = Output(Bool())
        val carry = Output(Bool())
    })

    val halfSum = io.x0 ^ io.x1
    io.sum   := halfSum ^ io.x2
    io.carry := (io.x0 & io.x1) | (halfSum & io.x2)
}

class FourToTwoCompGroup(width: Int) extends Module {
    require(width > 0, s"width must be positive, got $width")

    val io = IO(new Bundle {
        val x0    = Input(UInt(width.W))
        val x1    = Input(UInt(width.W))
        val x2    = Input(UInt(width.W))
        val x3    = Input(UInt(width.W))
        val sum   = Output(UInt((width + 1).W))
        val carry = Output(UInt(width.W))
    })

    val sumBits   = Wire(Vec(width + 1, Bool()))
    val carryBits = Wire(Vec(width, Bool()))

    var cin = false.B
    for (i <- 0 until width) {
        val comp = Module(new FourToTwoComp)
        comp.io.cin := cin
        comp.io.x0  := io.x0(i)
        comp.io.x1  := io.x1(i)
        comp.io.x2  := io.x2(i)
        comp.io.x3  := io.x3(i)

        sumBits(i)   := comp.io.sum
        carryBits(i) := comp.io.carry
        cin = comp.io.cout
    }
    sumBits(width) := cin

    io.sum   := sumBits.asUInt
    io.carry := carryBits.asUInt
}

class ThreeToTwoCompGroup(width: Int) extends Module {
    require(width > 0, s"width must be positive, got $width")

    val io = IO(new Bundle {
        val x0    = Input(UInt(width.W))
        val x1    = Input(UInt(width.W))
        val x2    = Input(UInt(width.W))
        val sum   = Output(UInt(width.W))
        val carry = Output(UInt(width.W))
    })

    val sumBits   = Wire(Vec(width, Bool()))
    val carryBits = Wire(Vec(width, Bool()))

    for (i <- 0 until width) {
        val comp = Module(new ThreeToTwoComp)
        comp.io.x0 := io.x0(i)
        comp.io.x1 := io.x1(i)
        comp.io.x2 := io.x2(i)

        sumBits(i)   := comp.io.sum
        carryBits(i) := comp.io.carry
    }

    io.sum   := sumBits.asUInt
    io.carry := carryBits.asUInt
}
