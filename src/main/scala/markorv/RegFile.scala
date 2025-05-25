package markorv

import chisel3._
import chisel3.util._

class RegFile extends Module {
    val io = IO(new Bundle {
        val readAddrs = Vec(3, Input(UInt(5.W)))
        val readDatas = Vec(3, Output(UInt(64.W)))

        val writeAddr = Input(UInt(5.W))
        val writeData = Input(UInt(64.W))

        val acquireReg = Input(UInt(5.W))
        val acquired = Output(Bool())

        val getOccupied = Output(UInt(32.W))
        val flush = Input(Bool())
    })
    // While read and write a same register simultaneously will output write data.
    val regAcquireFlags = RegInit(0.U(32.W))
    val regAcquireFlagsNext = Wire(UInt(32.W))

    val regs = RegInit(VecInit(Seq.fill(32)(0.U(64.W))))

    io.acquired := false.B

    when(io.writeAddr =/= 0.U) {
        regs(io.writeAddr) := io.writeData
        regAcquireFlagsNext := regAcquireFlags & ~(1.U << io.writeAddr)
    }.otherwise {
        regAcquireFlagsNext := regAcquireFlags
    }

    for (i <- 0 until io.readAddrs.length) {
        io.readDatas(i) := Mux(
          io.writeAddr === io.readAddrs(i),
          io.writeData,
          Mux(io.readAddrs(i) === 0.U, 0.U, regs(io.readAddrs(i)))
        )
    }
    io.getOccupied := regAcquireFlagsNext

    // Acquire regiser lastly to avoid allow release and acquire simultaneously.
    when(io.acquireReg =/= 0.U && ~regAcquireFlags(io.acquireReg)) {
        regAcquireFlags := regAcquireFlagsNext | (1.U << io.acquireReg)
        io.acquired := true.B
    }.elsewhen(io.acquireReg === 0.U) {
        regAcquireFlags := regAcquireFlagsNext
        io.acquired := true.B
    }.otherwise {
        regAcquireFlags := regAcquireFlagsNext
    }

    when(io.flush) {
        regAcquireFlags := 0.U
    }
}
