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

        val robReadIndices  = Output(Vec(5, UInt(robIndexWidth.W)))
        val robReadEntries = Input(Vec(5, new ROBEntry))

        val commitEvents = Vec(5, Valid(new CommitEvent))
        val regWrites    = Output(Vec(5, Valid(new RegWriteBundle)))
        val robCommits   = Output(Vec(5, Valid(new ROBCommitReq)))
        val outfires     = Output(Vec(5, Bool()))
    })

    val inputs = Seq(io.alu, io.bru, io.lsu, io.mdu, io.misc)

    for (i <- inputs.indices) {
        val in           = inputs(i)
        val outfire      = io.outfires(i)
        val regWrite     = io.regWrites(i)
        val robCommit    = io.robCommits(i)
        val commitEvent  = io.commitEvents(i)
        val robReadIndex = io.robReadIndices(i)
        val robReadEntry = io.robReadEntries(i)

        in.ready := true.B
        outfire := in.valid

        robReadIndex       := in.bits.robIndex
        regWrite.valid     := in.valid && robReadEntry.prdValid
        regWrite.bits.addr := robReadEntry.prd
        regWrite.bits.data := in.bits.data

        commitEvent.valid := in.valid
        commitEvent.bits.prdValid := robReadEntry.prdValid
        commitEvent.bits.prd      := robReadEntry.prd

        robCommit.valid         := in.valid
        robCommit.bits.robIndex := in.bits.robIndex

        val fCtrl = robCommit.bits.fCtrl
        fCtrl := new ROBDisconField().zero

        if (in.bits.isInstanceOf[CommitWithDiscon]) {
            val d = in.bits.asInstanceOf[CommitWithDiscon]
            fCtrl.discon := d.discon
            fCtrl.disconType := d.disconType
        }
        if (in.bits.isInstanceOf[CommitWithException]) {
            val t = in.bits.asInstanceOf[CommitWithException]
            fCtrl.cause := t.cause
            fCtrl.xtval := t.xtval
        }
        if (in.bits.isInstanceOf[CommitWithRecover]) {
            val r = in.bits.asInstanceOf[CommitWithRecover]
            fCtrl.eventPc := r.eventPc
        }
        if (in.bits.isInstanceOf[CommitWithXret]) {
            val x = in.bits.asInstanceOf[CommitWithXret]
            fCtrl.xretType := x.xretType
        }
    }
}