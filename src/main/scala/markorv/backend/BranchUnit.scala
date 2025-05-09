package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend.DecoderOutParams
import markorv.backend._

object BranchFunct extends ChiselEnum {
    val beq  = Value("b0000".U)
    val bne  = Value("b0001".U)
    val blt  = Value("b0100".U)
    val bge  = Value("b0101".U)
    val bltu = Value("b0110".U)
    val bgeu = Value("b0111".U)

    val jal  = Value("b1000".U)
    val jalr = Value("b1001".U)
}

class BranchUnit extends Module {
    val io = IO(new Bundle {
        val branchInstr = Flipped(Decoupled(new Bundle {
            val branchOpcode = new BranchOpcode
            val predTaken = Bool()
            val predPc = UInt(64.W)
            val recoverPc = UInt(64.W)
            val params = new DecoderOutParams
        }))

        val commit = Decoupled(new Bundle {
            val reg = Input(UInt(5.W))
            val data = Input(UInt(64.W))
        })

        val flush = Bool()
        val setPc = UInt(64.W)
        val outfire = Output(Bool())
    })

    io.outfire := false.B
    io.branchInstr.ready := true.B

    io.commit.valid := false.B
    io.commit.bits.reg := 0.U
    io.commit.bits.data := 0.U
    io.flush := false.B
    io.setPc := 0.U

    val params     = io.branchInstr.bits.params
    val predPc    = io.branchInstr.bits.predPc
    val predTaken = io.branchInstr.bits.predTaken
    val recoverPc = io.branchInstr.bits.recoverPc
    val source1    = params.source1
    val source2    = params.source2
    val jalrPc    = ((source1 + source2) & ~(1.U(64.W)))

    val (funct, validFunct) = BranchFunct.safe(io.branchInstr.bits.branchOpcode.funct)
    val branch_taken = MuxLookup(funct, false.B)(Seq(
        BranchFunct.beq -> (source1 === source2),
        BranchFunct.bne -> (source1 =/= source2),
        BranchFunct.blt -> (source1.asSInt < source2.asSInt),
        BranchFunct.bge -> (source1.asSInt >= source2.asSInt),
        BranchFunct.bltu -> (source1 < source2),
        BranchFunct.bgeu -> (source1 >= source2),
    ))
    val recover = MuxLookup(funct, branch_taken =/= predTaken)(Seq(
        BranchFunct.jal -> (false.B),
        BranchFunct.jalr -> (jalrPc =/= predPc)
    ))

    when(validFunct) {
        io.commit.valid := io.branchInstr.valid
        io.commit.bits.reg := Mux(funct.in(BranchFunct.jal, BranchFunct.jalr), params.rd, 0.U)
        io.commit.bits.data := Mux(funct.in(BranchFunct.jal, BranchFunct.jalr), params.pc + 4.U, 0.U)
        io.flush := io.branchInstr.valid && recover
        io.setPc := Mux(funct === BranchFunct.jalr, jalrPc, recoverPc)
        io.outfire := io.branchInstr.valid
    }
}
