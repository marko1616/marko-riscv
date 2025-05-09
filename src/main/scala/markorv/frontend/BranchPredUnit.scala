package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.frontend._
import markorv.utils.ChiselUtils._

class BranchPredUnit extends Module {
    val io = IO(new Bundle {
        val bpuInstr = Input(new Bundle {
            val instr = UInt(32.W)
            val pc = UInt(64.W)
        })

        val bpuResult = Output(new Bundle {
            val isBranch = Bool()
            val predTaken = Bool()
            val predPc = UInt(64.W)
            val recoverPc = UInt(64.W)
        })

        val regRead = Output(UInt(5.W))
        val regData = Input(UInt(64.W))
    })

    io.regRead := 0.U

    io.bpuResult.isBranch := false.B
    io.bpuResult.predTaken := false.B
    io.bpuResult.predPc := io.bpuInstr.pc + 4.U
    io.bpuResult.recoverPc := io.bpuInstr.pc + 4.U

    switch(io.bpuInstr.instr(6, 0)) {
        is("b1101111".U) {
            // jal
            io.bpuResult.isBranch := true.B
            io.bpuResult.predTaken := true.B
            io.bpuResult.predPc := io.bpuInstr.pc + Cat(
              io.bpuInstr.instr(31),
              io.bpuInstr.instr(19, 12),
              io.bpuInstr.instr(20),
              io.bpuInstr.instr(30, 21),
              0.U(1.W)
            ).sextu(64)
            io.bpuResult.recoverPc := io.bpuInstr.pc + 4.U
        }
        is("b1100111".U) {
            // jalr
            io.regRead := io.bpuInstr.instr(19, 15)

            io.bpuResult.isBranch := true.B
            io.bpuResult.predTaken := true.B
            io.bpuResult.predPc := (io.regData + io.bpuInstr.instr(31, 20).sextu(64)) & ~(1.U(64.W))
            io.bpuResult.recoverPc := io.bpuInstr.pc + 4.U
        }
        is("b1100011".U) {
            // branch
            io.bpuResult.isBranch := true.B
            io.bpuResult.predPc := 0.U

            // static prediction for backward branches
            val imm = Wire(SInt(64.W))
            imm := Cat(
              io.bpuInstr.instr(31),
              io.bpuInstr.instr(7),
              io.bpuInstr.instr(30, 25),
              io.bpuInstr.instr(11, 8),
              0.U(1.W)
            ).sexts(64)
            when(imm < 0.S) {
                io.bpuResult.predTaken := true.B
                io.bpuResult.predPc := io.bpuInstr.pc + imm.asUInt
                io.bpuResult.recoverPc := io.bpuInstr.pc + 4.U
            }.otherwise {
                io.bpuResult.predTaken := false.B
                io.bpuResult.predPc := io.bpuInstr.pc + 4.U
                io.bpuResult.recoverPc := io.bpuInstr.pc + imm.asUInt
            }
        }
    }
}
