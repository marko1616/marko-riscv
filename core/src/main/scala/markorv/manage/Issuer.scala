package markorv.manage

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.utils._
import markorv.config._
import markorv.frontend._
import markorv.backend._

class Issuer(implicit val c: CoreConfig) extends Module {
    private val renameIndexWidth = log2Ceil(c.renameTableSize)
    private val phyRegWidth = log2Ceil(c.regFileSize)

    val io = IO(new Bundle {
        // Issue signals
        // ========================
        val issueTask = Flipped(Decoupled(new IssueTask))

        // ROB signals
        // ========================
        val robReq = Valid(new ROBAllocReq)
        val robResp = Flipped(Valid(new ROBAllocResp))
        val robMayDison = Input(Bool())
        val robFull = Input(Bool())

        // Reservation Station signals
        // ========================
        val rsReq = Decoupled(new ReservationStationEntry)
        val rsRegReqBits = Input(UInt(c.regFileSize.W))

        // Register signals
        // ========================
        val regStates = Input(Vec(c.regFileSize, new PhyRegState.Type))

        // Rename Table signals
        // ========================
        val createRtCkpt = Decoupled(Vec(31,UInt(phyRegWidth.W)))
        val renameTable = Input(Vec(31, UInt(phyRegWidth.W)))
        val renameTailIndex = Input(UInt(renameIndexWidth.W))

        // Event signals
        // ========================
        val interruptHlt = Input(Bool())
        val issueEvent = Valid(new IssueEvent)
        val outfire = Output(Bool())
    })
    // Input Extraction
    val task = io.issueTask.bits
    val exu = task.exu
    val params = task.params
    val rd = params.rd
    val prdValid = rd =/= 0.U
    val origPrd = io.renameTable(rd - 1.U)

    val lrs1 = task.lregReq.lrs1
    val lrs2 = task.lregReq.lrs2
    val prs1 = io.renameTable(lrs1 - 1.U)
    val prs2 = io.renameTable(lrs2 - 1.U)

    // Free Register Logic
    val freeRegsVec = io.regStates.map(_ === PhyRegState.Free)
    val freeRegsBits = freeRegsVec.map(_.asUInt).reduce(_ ## _)
    val leadingZeros = CountLeadingZeros(freeRegsBits)
    val hasFreeReg = ~leadingZeros(phyRegWidth)
    val freeRegIndex = leadingZeros(phyRegWidth - 1, 0)

    val renameReady = hasFreeReg && io.createRtCkpt.ready
    io.createRtCkpt.valid := false.B
    io.createRtCkpt.bits := io.renameTable

    // Hazard Detection
    val warHazard = io.rsRegReqBits(origPrd)
    val wawHazard = (io.regStates(origPrd) =/= PhyRegState.Allocated && prdValid) || io.robMayDison
    val hasHazard = warHazard || wawHazard
    val noHazard = !hasHazard

    // Rename Logic
    val needRename = io.issueTask.valid && renameReady && hasHazard && prdValid && !io.robFull
    val hazardResolved = WireDefault(false.B)
    val prd = WireDefault(origPrd)

    when(needRename) {
        prd := freeRegIndex
        io.createRtCkpt.valid := true.B
        io.createRtCkpt.bits(rd - 1.U) := freeRegIndex
        hazardResolved := true.B
    }

    // ROB Request
    val robReqValid = io.issueTask.valid && io.rsReq.ready && (noHazard || hazardResolved) && ~io.interruptHlt
    io.robReq.valid := robReqValid
    io.robReq.bits := 0.U.asTypeOf(new ROBAllocReq)
    io.robReq.bits.exu := exu
    io.robReq.bits.prdValid := prdValid
    io.robReq.bits.prd := prd
    io.robReq.bits.prevprd := origPrd
    io.robReq.bits.pc := params.pc
    io.robReq.bits.renameCkptIndex := Mux(hazardResolved, io.renameTailIndex + 1.U, io.renameTailIndex)

    // RS Request
    val issueParams = Wire(new EXUParams)
    issueParams.robIndex := io.robResp.bits.index
    issueParams.pc := params.pc
    issueParams.source1 := params.source1
    issueParams.source2 := params.source2

    io.rsReq.valid := io.robResp.valid
    io.rsReq.bits := 0.U.asTypeOf(new ReservationStationEntry)
    io.rsReq.bits.valid := true.B
    io.rsReq.bits.exu := exu
    io.rsReq.bits.opcodes := task.opcodes
    io.rsReq.bits.predTaken := task.predTaken
    io.rsReq.bits.predPc := task.predPc
    io.rsReq.bits.params := issueParams
    io.rsReq.bits.regReq.prs1Valid := lrs1 =/= 0.U
    io.rsReq.bits.regReq.prs2Valid := lrs2 =/= 0.U
    io.rsReq.bits.regReq.prs1IsRd := prs1 === prd && prdValid
    io.rsReq.bits.regReq.prs2IsRd := prs2 === prd && prdValid
    io.rsReq.bits.regReq.prs1 := prs1
    io.rsReq.bits.regReq.prs2 := prs2

    // Control
    io.issueTask.ready := (io.issueTask.valid && io.robResp.valid) || (!io.issueTask.valid && io.rsReq.ready)
    io.outfire := io.robResp.valid

    // Event
    io.issueEvent.valid := io.robResp.valid
    io.issueEvent.bits := 0.U.asTypeOf(new IssueEvent)
    io.issueEvent.bits.prdValid := prdValid
    io.issueEvent.bits.prd := prd
}
