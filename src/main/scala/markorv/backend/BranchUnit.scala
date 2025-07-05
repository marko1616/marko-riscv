package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.frontend.DecodedParams
import markorv.manage.RegisterCommit
import markorv.manage.EXUParams
import markorv.manage.DisconEventEnum

class BranchUnit(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val branchInstr = Flipped(Decoupled(new Bundle {
            val branchOpcode = new BranchOpcode
            val predTaken = Bool()
            val predPc = UInt(64.W)
            val params = new EXUParams
        }))

        val commit = Decoupled(new BRUCommit)
        val outfire = Output(Bool())
    })

    val opcode    = io.branchInstr.bits.branchOpcode
    val params    = io.branchInstr.bits.params
    val predPc    = io.branchInstr.bits.predPc
    val predTaken = io.branchInstr.bits.predTaken
    val source1   = params.source1
    val source2   = params.source2
    val jalrPc    = ((source1 + source2) & ~(1.U(64.W)))

    io.outfire := false.B
    io.branchInstr.ready := io.commit.ready

    io.commit.valid := false.B
    io.commit.bits := new BRUCommit().zero
    io.commit.bits.robIndex := params.robIndex

    val funct = opcode.getFunct()
    val branchTaken = MuxLookup(funct, false.B)(Seq(
        BranchFunct.beq -> (source1 === source2),
        BranchFunct.bne -> (source1 =/= source2),
        BranchFunct.blt -> (source1.asSInt < source2.asSInt),
        BranchFunct.bge -> (source1.asSInt >= source2.asSInt),
        BranchFunct.bltu -> (source1 < source2),
        BranchFunct.bgeu -> (source1 >= source2),
    ))
    val recover = MuxLookup(funct, branchTaken =/= predTaken)(Seq(
        BranchFunct.jal -> (false.B),
        BranchFunct.jalr -> (jalrPc =/= predPc)
    ))
    val branchPc = Mux(branchTaken, params.pc + (opcode.offset ## 0.U(1.W)).sextu(64), params.pc + 4.U)

    io.commit.valid := io.branchInstr.valid
    io.commit.bits.data := Mux(funct.in(BranchFunct.jal, BranchFunct.jalr), params.pc + 4.U, 0.U)
    io.commit.bits.disconType := Mux(funct === BranchFunct.jalr, DisconEventEnum.instrRedirect, DisconEventEnum.branchMispred)
    io.commit.bits.recover := recover
    io.commit.bits.recoverPc := Mux(funct === BranchFunct.jalr, jalrPc, branchPc)
    io.outfire := io.branchInstr.valid
}
