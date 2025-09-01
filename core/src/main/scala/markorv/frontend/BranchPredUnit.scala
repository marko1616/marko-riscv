package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._

class BranchPredUnit extends Module {
    val io = IO(new Bundle {
        val bpuInstr = Input(new Bundle {
            val instr = new Instruction
            val pc = UInt(64.W)
        })

        val bpuResult = Output(new Bundle {
            val predTaken = Bool()
            val predPc = UInt(64.W)
        })
    })

    // Helper case class for table-driven decoding
    case class DecodeEntry(
        opcode: UInt,
        handler: (Instruction, UInt, UInt, Bool) => Unit,
    )

    def predJal(rawInstr: Instruction, pc: UInt, predPc: UInt, predTaken: Bool) = {
        val instr = rawInstr.asTypeOf(new JTypeInstruction)
        predTaken := true.B
        predPc := pc + instr.imm.sextu(64)
    }

    def predJalr(rawInstr: Instruction, pc: UInt, predPc: UInt, predTaken: Bool) = {
        val instr = rawInstr.asTypeOf(new ITypeInstruction)
        // TODO
        predTaken := true.B
        predPc := pc + 4.U
    }

    def predBranch(rawInstr: Instruction, pc: UInt, predPc: UInt, predTaken: Bool) = {
        val instr = rawInstr.asTypeOf(new BTypeInstruction)
        predTaken := instr.imm.asSInt < 0.S
        predPc := Mux(predTaken, pc + instr.imm.sextu(64), pc + 4.U)
    }

    val OP_JAL      = "b1101111".U
    val OP_JALR     = "b1100111".U
    val OP_BRANCH   = "b1100011".U

    val decodeTable = Seq(
        DecodeEntry(OP_JAL, predJal),
        DecodeEntry(OP_JALR, predJalr),
        DecodeEntry(OP_BRANCH, predBranch),
    )

    io.bpuResult.predTaken := false.B
    io.bpuResult.predPc := io.bpuInstr.pc + 4.U

    val instr = io.bpuInstr.instr
    val instrPc = io.bpuInstr.pc
    val opcode = io.bpuInstr.instr.opcode
    val predTaken = io.bpuResult.predTaken
    val predPc = io.bpuResult.predPc

    predTaken := false.B
    predPc := io.bpuInstr.pc + 4.U

    for (entry <- decodeTable) {
        when(opcode === entry.opcode) {
            entry.handler(instr, instrPc, predPc, predTaken)
        }
    }
}
