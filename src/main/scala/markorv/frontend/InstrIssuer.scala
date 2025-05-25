package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend._
import markorv.backend._

class InstrIssueUnit extends Module {
    val io = IO(new Bundle {
        val issueTask = Flipped(Decoupled(new IssueTask))

        val lsuOut = Decoupled(new Bundle {
            val lsuOpcode = new LoadStoreOpcode
            val params = new DecoderOutParams
        })

        val aluOut = Decoupled(new Bundle {
            val aluOpcode = new ALUOpcode
            val params = new DecoderOutParams
        })

        val miscOut = Decoupled(new Bundle {
            val miscOpcode = new MiscOpcode
            val params = new DecoderOutParams
        })

        val muOut = Decoupled(new Bundle {
            val muOpcode = new MUOpcode
            val params = new DecoderOutParams
        })

        val branchOut = Decoupled(new Bundle {
            val branchOpcode = new BranchOpcode
            val predTaken = Bool()
            val predPc = UInt(64.W)
            val params = new DecoderOutParams
        })

        val acquireReg = Output(UInt(5.W))
        val acquired = Input(Bool())

        val regRead1 = Output(UInt(5.W))
        val regRead2 = Output(UInt(5.W))
        val regData1 = Input(UInt(64.W))
        val regData2 = Input(UInt(64.W))

        val occupiedRegs = Input(UInt(32.W))
        val outfire = Output(Bool())
    })
    val finalParams = Wire(new DecoderOutParams)
    val params = io.issueTask.bits.params
    finalParams := params

    val occupiedReg = Wire(Bool())
    val execUnitReady = Wire(Bool())
    occupiedReg := false.B
    // Force exec order.
    execUnitReady := io.lsuOut.ready && io.aluOut.ready && io.miscOut.ready && io.muOut.ready && io.branchOut.ready

    io.lsuOut.valid := false.B
    io.aluOut.valid := false.B
    io.miscOut.valid := false.B
    io.muOut.valid := false.B
    io.branchOut.valid := false.B

    io.lsuOut.bits.lsuOpcode := new LoadStoreOpcode().zero
    io.lsuOut.bits.params := new DecoderOutParams().zero
    io.aluOut.bits.aluOpcode := new ALUOpcode().zero
    io.aluOut.bits.params := new DecoderOutParams().zero
    io.miscOut.bits.miscOpcode := new MiscOpcode().zero
    io.miscOut.bits.params := new DecoderOutParams().zero
    io.muOut.bits.muOpcode := new MUOpcode().zero
    io.muOut.bits.params := new DecoderOutParams().zero
    io.branchOut.bits.branchOpcode := new BranchOpcode().zero
    io.branchOut.bits.predTaken := false.B
    io.branchOut.bits.predPc := 0.U
    io.branchOut.bits.params := new DecoderOutParams().zero

    io.acquireReg := 0.U
    io.issueTask.ready := false.B
    io.outfire := false.B

    val regData1 = Wire(UInt(64.W))
    val regData2 = Wire(UInt(64.W))
    io.regRead1 := io.issueTask.bits.regRequests.source1
    io.regRead2 := io.issueTask.bits.regRequests.source2
    regData1 := io.regData1
    regData2 := io.regData2
    when(io.issueTask.bits.regRequests.source1 =/= 0.U) {
        finalParams.source1 := params.source1 + regData1
    }
    when(io.issueTask.bits.regRequests.source2 =/= 0.U) {
        finalParams.source2 := params.source2 + regData2
    }
    occupiedReg := io.occupiedRegs(
      io.issueTask.bits.regRequests.source1
    ) || io.occupiedRegs(io.issueTask.bits.regRequests.source2)

    when(io.issueTask.valid && execUnitReady && ~occupiedReg) {
        // Only try to acquire register when all other are prepared.
        io.acquireReg := finalParams.rd
        when(io.issueTask.bits.operateUnit === 0.U && io.acquired) {
            io.outfire := true.B
            io.aluOut.valid := true.B
            io.aluOut.bits.aluOpcode := io.issueTask.bits.aluOpcode
            io.aluOut.bits.params := finalParams
        }.elsewhen(io.issueTask.bits.operateUnit === 1.U && io.acquired) {
            io.outfire := true.B
            io.lsuOut.valid := true.B
            io.lsuOut.bits.lsuOpcode := io.issueTask.bits.lsuOpcode
            io.lsuOut.bits.params := finalParams
        }.elsewhen(io.issueTask.bits.operateUnit === 2.U && io.acquired) {
            io.outfire := true.B
            io.miscOut.valid := true.B
            io.miscOut.bits.miscOpcode := io.issueTask.bits.miscOpcode
            io.miscOut.bits.params := finalParams
        }.elsewhen(io.issueTask.bits.operateUnit === 3.U && io.acquired) {
            io.outfire := true.B
            io.muOut.valid := true.B
            io.muOut.bits.muOpcode := io.issueTask.bits.muOpcode
            io.muOut.bits.params := finalParams
        }.elsewhen(io.issueTask.bits.operateUnit === 4.U && io.acquired) {
            io.outfire := true.B
            io.branchOut.valid := true.B
            io.branchOut.bits.branchOpcode := io.issueTask.bits.branchOpcode
            io.branchOut.bits.predTaken := io.issueTask.bits.predTaken
            io.branchOut.bits.predPc := io.issueTask.bits.predPc
            io.branchOut.bits.params := finalParams
        }
    }

    // Ready when dispatch is available.
    io.issueTask.ready := execUnitReady && ~occupiedReg && io.acquired
}
