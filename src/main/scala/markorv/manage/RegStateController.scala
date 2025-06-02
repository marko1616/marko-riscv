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
    when(issueEvent.valid && issueEvent.bits.phyRdValid) {
        val phyRd = issueEvent.bits.phyRd
        io.setStates.bits(phyRd) := PhyRegState.Occupied
        io.setStates.valid := true.B
    }

    for(commitEvent <- io.commitEvents) {
        val phyRd = commitEvent.bits.phyRd
        when(commitEvent.valid && commitEvent.bits.phyRdValid) {
            io.setStates.bits(phyRd) := PhyRegState.Committed
            io.setStates.valid := true.B
        }
    }

    val retireEvent = io.retireEvent
    when(retireEvent.valid && retireEvent.bits.phyRdValid) {
        val phyRd = retireEvent.bits.phyRd
        val prevPhyRd = retireEvent.bits.prevPhyRd
        io.setStates.bits(phyRd) := PhyRegState.Allocated
        when(phyRd =/= prevPhyRd) {
            io.setStates.bits(prevPhyRd) := PhyRegState.Free
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
        when(disconEvent.bits.disconType === DisconEventEnum.instrRedirect && disconEvent.bits.phyRdValid) {
            // For jalr we still need rename rd
            disconStates(disconEvent.bits.prevPhyRd) := PhyRegState.Free
            disconStates(disconEvent.bits.phyRd) := PhyRegState.Allocated
        }
        io.setStates.bits := disconStates
        io.setStates.valid := true.B
    }
}