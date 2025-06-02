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

        val lsuOut = Decoupled(new Bundle {
            val lsuOpcode = new LoadStoreOpcode
            val params = new EXUParams
        })

        val aluOut = Decoupled(new Bundle {
            val aluOpcode = new ALUOpcode
            val params = new EXUParams
        })

        val miscOut = Decoupled(new Bundle {
            val miscOpcode = new MISCOpcode
            val params = new EXUParams
        })

        val mduOut = Decoupled(new Bundle {
            val mduOpcode = new MDUOpcode
            val params = new EXUParams
        })

        val bruOut = Decoupled(new Bundle {
            val branchOpcode = new BranchOpcode
            val predTaken = Bool()
            val predPc = UInt(64.W)
            val params = new EXUParams
        })

        val regRead1 = Output(UInt(5.W))
        val regRead2 = Output(UInt(5.W))
        val regData1 = Input(UInt(64.W))
        val regData2 = Input(UInt(64.W))

        val robReq = Valid(new ROBAllocReq)
        val robResp = Flipped(Valid(new ROBAllocResp))

        val regStates = Input(Vec(c.regFileSize, new PhyRegState.Type))
        val issueEvent = Valid(new IssueEvent)

        val renameTable = Input(Vec(31, UInt(log2Ceil(c.regFileSize).W)))
        val outfire = Output(Bool())
    })
    val finalParams = WireInit(new EXUParams().zero)
    val params = io.issueTask.bits.params
    val exuReady = io.lsuOut.ready && io.aluOut.ready && io.miscOut.ready && io.mduOut.ready && io.bruOut.ready

    // Rename
    val rs1 = io.issueTask.bits.regRequests.source1
    val rs2 = io.issueTask.bits.regRequests.source2
    val phyRdValid = params.rd =/= 0.U
    val phyRd = io.renameTable(params.rd-1.U)
    val rdOccupied = io.regStates(phyRd) =/= PhyRegState.Allocated && phyRdValid

    val phyRs1 = io.renameTable(rs1-1.U)
    val phyRs2 = io.renameTable(rs2-1.U)

    io.lsuOut.valid := false.B
    io.aluOut.valid := false.B
    io.miscOut.valid := false.B
    io.mduOut.valid := false.B
    io.bruOut.valid := false.B

    io.lsuOut.bits.lsuOpcode := new LoadStoreOpcode().zero
    io.lsuOut.bits.params := new EXUParams().zero
    io.aluOut.bits.aluOpcode := new ALUOpcode().zero
    io.aluOut.bits.params := new EXUParams().zero
    io.miscOut.bits.miscOpcode := new MISCOpcode().zero
    io.miscOut.bits.params := new EXUParams().zero
    io.mduOut.bits.mduOpcode := new MDUOpcode().zero
    io.mduOut.bits.params := new EXUParams().zero
    io.bruOut.bits.branchOpcode := new BranchOpcode().zero
    io.bruOut.bits.predTaken := false.B
    io.bruOut.bits.predPc := 0.U
    io.bruOut.bits.params := new EXUParams().zero

    io.issueTask.ready := false.B
    io.outfire := false.B

    finalParams.robIndex := io.robResp.bits.index
    finalParams.pc := io.issueTask.bits.params.pc

    val regData1 = Wire(UInt(64.W))
    val regData2 = Wire(UInt(64.W))
    io.regRead1 := phyRs1
    io.regRead2 := phyRs2
    regData1 := io.regData1
    regData2 := io.regData2
    finalParams.source1 := params.source1 + Mux(rs1 === 0.U, 0.U, regData1)
    finalParams.source2 := params.source2 + Mux(rs2 === 0.U, 0.U, regData2)
    val rs1Occupied = io.regStates(phyRs1) =/= PhyRegState.Allocated && rs1 =/= 0.U
    val rs2Occupied = io.regStates(phyRs2) =/= PhyRegState.Allocated && rs2 =/= 0.U
    val regOccupied = rs1Occupied || rs2Occupied || rdOccupied
    val robReqValid = io.issueTask.valid && exuReady && ~regOccupied
    val robRespValid = io.robResp.valid

    // No rename and ooo for now
    io.robReq.bits.prevPhyRd := phyRd
    io.robReq.bits.pc := io.issueTask.bits.params.pc

    io.issueEvent.valid := robReqValid
    io.issueEvent.bits.phyRdValid := phyRdValid
    io.issueEvent.bits.phyRd := phyRd

    when(robReqValid && robRespValid) {
        // Only try to acquire register when all other are prepared.
        when(io.issueTask.bits.operateUnit === EXUEnum.alu) {
            io.outfire := true.B
            io.aluOut.valid := true.B
        }.elsewhen(io.issueTask.bits.operateUnit === EXUEnum.bru) {
            io.outfire := true.B
            io.bruOut.valid := true.B
        }.elsewhen(io.issueTask.bits.operateUnit === EXUEnum.lsu) {
            io.outfire := true.B
            io.lsuOut.valid := true.B
        }.elsewhen(io.issueTask.bits.operateUnit === EXUEnum.mdu) {
            io.outfire := true.B
            io.mduOut.valid := true.B
        }.elsewhen(io.issueTask.bits.operateUnit === EXUEnum.misc) {
            io.outfire := true.B
            io.miscOut.valid := true.B
        }
    }

    // Dispatch
    io.aluOut.bits.aluOpcode := io.issueTask.bits.aluOpcode
    io.aluOut.bits.params := finalParams

    io.bruOut.bits.branchOpcode := io.issueTask.bits.branchOpcode
    io.bruOut.bits.predTaken := io.issueTask.bits.predTaken
    io.bruOut.bits.predPc := io.issueTask.bits.predPc
    io.bruOut.bits.params := finalParams

    io.lsuOut.bits.lsuOpcode := io.issueTask.bits.lsuOpcode
    io.lsuOut.bits.params := finalParams

    io.mduOut.bits.mduOpcode := io.issueTask.bits.mduOpcode
    io.mduOut.bits.params := finalParams

    io.miscOut.bits.miscOpcode := io.issueTask.bits.miscOpcode
    io.miscOut.bits.params := finalParams

    io.robReq.valid := robReqValid
    io.robReq.bits.phyRdValid := phyRdValid
    io.robReq.bits.phyRd := phyRd

    // Ready when dispatch is available.
    io.issueTask.ready := exuReady && ~regOccupied
}
