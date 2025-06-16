package markorv.manage

import chisel3._
import chisel3.util._

import markorv.config._

class RegStateController(implicit val c: CoreConfig) extends Module {
    private val renameIndexWidth = log2Ceil(c.renameTableSize)
    private val phyRegWidth = log2Ceil(c.regFileSize)

    val io = IO(new Bundle {
        val issueEvent = Flipped(Valid(new IssueEvent))
        val commitEvents = Flipped(Vec(5, Valid(new CommitEvent)))
        val retireEvent = Flipped(Valid(new RetireEvent))
        val disconEvent = Flipped(Valid(new DisconEvent))

        val renameTableReadIndex = Output(UInt(renameIndexWidth.W))
        val renameTableReadEntry = Input(Vec(31,UInt(phyRegWidth.W)))

        val setStates = Valid(Vec(c.regFileSize,new PhyRegState.Type))
        val getStates = Input(Vec(c.regFileSize,new PhyRegState.Type))
    })
    io.setStates.valid := false.B
    io.setStates.bits := io.getStates

    val issueEvent = io.issueEvent
    when(issueEvent.valid && issueEvent.bits.prdValid) {
        val prd = issueEvent.bits.prd
        io.setStates.bits(prd) := PhyRegState.Occupied
        io.setStates.valid := true.B
    }

    for(commitEvent <- io.commitEvents) {
        val prd = commitEvent.bits.prd
        when(commitEvent.valid && commitEvent.bits.prdValid) {
            io.setStates.bits(prd) := PhyRegState.Committed
            io.setStates.valid := true.B
        }
    }

    val retireEvent = io.retireEvent
    when(retireEvent.valid && retireEvent.bits.prdValid) {
        val prd = retireEvent.bits.prd
        val prevprd = retireEvent.bits.prevprd
        io.setStates.bits(prd) := PhyRegState.Allocated
        when(prd =/= prevprd) {
            io.setStates.bits(prevprd) := PhyRegState.Free
        }
        io.setStates.valid := true.B
    }

    val disconEvent = io.disconEvent
    io.renameTableReadIndex := disconEvent.bits.renameCkptIndex
    when(disconEvent.valid) {
        val disconStates = WireInit(VecInit.fill(c.regFileSize)(PhyRegState.Free))
        for (i <- 0 until 31) {
            disconStates(io.renameTableReadEntry(i)) := PhyRegState.Allocated
        }
        when(disconEvent.bits.disconType === DisconEventEnum.instrRedirect && disconEvent.bits.prdValid) {
            // For jalr we still need rename rd
            disconStates(disconEvent.bits.prevprd) := PhyRegState.Free
            disconStates(disconEvent.bits.prd) := PhyRegState.Allocated
        }
        io.setStates.bits := disconStates
        io.setStates.valid := true.B
    }
}