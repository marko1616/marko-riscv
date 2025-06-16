package markorv.manage

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.frontend._
import markorv.backend._

class Issuer(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val issueTask = Flipped(Decoupled(new IssueTask))

        val robReq = Valid(new ROBAllocReq)
        val robResp = Flipped(Valid(new ROBAllocResp))
        val robHasBrc = Input(Bool())

        val rsReq = Decoupled(new ReservationStationEntry)
        val rsHasLdSt = Input(Bool())
        val rsHasMisc = Input(Bool())
        val rsRegReqBits = Input(UInt(c.regFileSize.W))

        val regStates = Input(Vec(c.regFileSize, new PhyRegState.Type))
        val renameTable = Input(Vec(31, UInt(log2Ceil(c.regFileSize).W)))

        val issueEvent = Valid(new IssueEvent)
        val outfire = Output(Bool())
    })
    val issueParams = WireInit(new EXUParams().zero)
    val exu = io.issueTask.bits.exu
    val predTaken = io.issueTask.bits.predTaken
    val predPc = io.issueTask.bits.predPc
    val opcodes = io.issueTask.bits.opcodes
    val params = io.issueTask.bits.params
    val lregReq = io.issueTask.bits.lregReq

    // Rename
    val lrs1 = lregReq.lrs1
    val lrs2 = lregReq.lrs2
    val prdValid = params.rd =/= 0.U
    val prd = io.renameTable(params.rd-1.U)

    val prs1 = io.renameTable(lrs1-1.U)
    val prs2 = io.renameTable(lrs2-1.U)

    val ldstOrderHlt = io.rsHasLdSt && exu === EXUEnum.lsu
    val miscOrderHlt = io.rsHasMisc && exu === EXUEnum.misc
    val warHlt = io.rsRegReqBits(prd)
    val wawHltReg = io.regStates(prd) =/= PhyRegState.Allocated && prdValid
    val wawHltBrc = io.robHasBrc
    val wawHlt = wawHltReg || wawHltBrc
    val issueValid = io.issueTask.valid && io.rsReq.ready && ~ldstOrderHlt && ~miscOrderHlt && ~wawHlt && ~warHlt
    val robRespValid = io.robResp.valid

    // No rename for now
    io.robReq.valid := issueValid
    io.robReq.bits.exu := exu
    io.robReq.bits.prdValid := prdValid
    io.robReq.bits.prd := prd
    io.robReq.bits.prevprd := prd
    io.robReq.bits.pc := params.pc

    issueParams.source1 := params.source1
    issueParams.source2 := params.source2
    issueParams.robIndex := io.robResp.bits.index
    issueParams.pc := params.pc

    // Issue
    io.rsReq.valid := robRespValid
    io.rsReq.bits.valid := true.B
    io.rsReq.bits.exu := exu
    io.rsReq.bits.opcodes := opcodes
    io.rsReq.bits.predTaken := predTaken
    io.rsReq.bits.predPc := predPc
    io.rsReq.bits.params := issueParams
    io.rsReq.bits.regReq.prs1Valid := lrs1 =/= 0.U
    io.rsReq.bits.regReq.prs2Valid := lrs2 =/= 0.U
    io.rsReq.bits.regReq.prs1IsRd := prs1 === prd
    io.rsReq.bits.regReq.prs2IsRd := prs2 === prd
    io.rsReq.bits.regReq.prs1 := prs1
    io.rsReq.bits.regReq.prs2 := prs2

    // Issue Boardcast
    io.issueTask.ready := (io.issueTask.valid && robRespValid) || (~io.issueTask.valid && io.rsReq.ready)
    io.outfire := robRespValid
    io.issueEvent.valid := robRespValid
    io.issueEvent.bits.prdValid := prdValid
    io.issueEvent.bits.prd := prd
}
