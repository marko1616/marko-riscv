package markorv.manage

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.backend.ALUCommit
import markorv.backend.BRUCommit
import markorv.backend.LSUCommit
import markorv.backend.MDUCommit
import markorv.backend.MISCCommit

class CommitUnit(implicit val c: CoreConfig) extends Module {
    private val robIndexWidth = log2Ceil(c.robSize)

    val io = IO(new Bundle {
        val alu  = Flipped(Decoupled(new ALUCommit))
        val bru  = Flipped(Decoupled(new BRUCommit))
        val lsu  = Flipped(Decoupled(new LSUCommit))
        val mdu  = Flipped(Decoupled(new MDUCommit))
        val misc = Flipped(Decoupled(new MISCCommit))

        val robReadIndexs  = Output(Vec(5, UInt(robIndexWidth.W)))
        val robReadEntries = Input(Vec(5, new ROBEntry))

        val commitEvents = Vec(5, Valid(new CommitEvent))
        val regWrites    = Output(Vec(5, Valid(new RegWriteBundle)))
        val robCommits   = Output(Vec(5, Valid(new ROBCommitReq)))
        val outfires     = Output(Vec(5, Bool()))
    })

    // Helper zip
    def zip7[A, B, C, D, E, F, G](a: Seq[A], b: Seq[B], c: Seq[C], d: Seq[D], e: Seq[E], f: Seq[F], g: Seq[G]): Seq[(A, B, C, D, E, F, G)] = {
        a.indices.map(i => (a(i), b(i), c(i), d(i), e(i), f(i), g(i)))
    }

    val inputs = Seq(io.alu, io.bru, io.lsu, io.mdu, io.misc)

    val combined = zip7(inputs, io.outfires, io.regWrites, io.robCommits, io.commitEvents, io.robReadIndexs, io.robReadEntries)
    for ((in, outfire, regWrite, robCommit, commitEvent, robReadIndex, robReadEntry) <- combined) {
        in.ready := true.B
        outfire := in.valid

        robReadIndex       := in.bits.robIndex
        regWrite.valid     := in.valid && robReadEntry.prdValid
        regWrite.bits.addr := robReadEntry.prd
        regWrite.bits.data := in.bits.data

        commitEvent.valid           := in.valid
        commitEvent.bits.prdValid := robReadEntry.prdValid
        commitEvent.bits.prd      := robReadEntry.prd

        robCommit.valid         := in.valid
        robCommit.bits.robIndex := in.bits.robIndex
        robCommit.bits.fCtrl := {
            val fCtrl = Wire(new ROBDisconField)
            fCtrl := new ROBDisconField().zero
            in match {
                case i if i == io.misc =>
                    val bits = in.bits.asInstanceOf[MISCCommit]
                    fCtrl.disconType := bits.disconType
                    fCtrl.trap       := bits.trap
                    fCtrl.cause      := bits.cause
                    fCtrl.xtval      := bits.xtval
                    fCtrl.xret       := bits.xret
                    fCtrl.recover    := bits.recover
                    fCtrl.recoverPc  := bits.recoverPc
                case i if i == io.bru =>
                    val bits = in.bits.asInstanceOf[BRUCommit]
                    fCtrl.disconType := bits.disconType
                    fCtrl.recover    := bits.recover
                    fCtrl.recoverPc  := bits.recoverPc
                case i if i == io.lsu =>
                    val bits = in.bits.asInstanceOf[LSUCommit]
                    fCtrl.disconType := bits.disconType
                    fCtrl.trap       := bits.trap
                    fCtrl.cause      := bits.cause
                    fCtrl.xtval      := bits.xtval
                case _ =>
            }
            fCtrl
        }
    }
}