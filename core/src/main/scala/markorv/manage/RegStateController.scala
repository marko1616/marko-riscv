package markorv.manage

import chisel3._
import chisel3.util._

import markorv.config._

class RegStateController(implicit val c: CoreConfig) extends Module {
    private val renameIndexWidth = log2Ceil(c.renameTableSize)
    private val phyRegWidth = log2Ceil(c.regFileSize)

    val io = IO(new Bundle {
        // Events
        // ========================
        val issueEvent = Flipped(Valid(new IssueEvent))
        val commitEvents = Flipped(Vec(5, Valid(new CommitEvent)))
        val retireEvent = Flipped(Valid(new RetireEvent))
        val disconEvent = Flipped(Valid(new DisconEvent))

        // Rename checkpoint lookup
        // ========================
        val renameTableReadIndex = Output(UInt(renameIndexWidth.W))
        val renameTableReadEntry = Input(Vec(31, UInt(phyRegWidth.W)))

        // Physical register state interface
        // ========================
        val setStates = Valid(Vec(c.regFileSize, new PhyRegState.Type)) // updated state output
        val getStates = Input(Vec(c.regFileSize, new PhyRegState.Type)) // current state input
    })

    // Default assignment
    val nextStates = WireInit(io.getStates)
    var updateValid = false.B

    // Handle issue event
    when(io.issueEvent.valid && io.issueEvent.bits.prdValid) {
        val prd = io.issueEvent.bits.prd
        nextStates(prd) := PhyRegState.Occupied
        updateValid = true.B
    }

    // Handle commit events
    for (commitEvent <- io.commitEvents) {
        when(commitEvent.valid && commitEvent.bits.prdValid) {
            val prd = commitEvent.bits.prd
            nextStates(prd) := PhyRegState.Committed
            updateValid = true.B
        }
    }

    // Handle retire event
    when(io.retireEvent.valid && io.retireEvent.bits.prdValid) {
        val prd = io.retireEvent.bits.prd
        val prevprd = io.retireEvent.bits.prevprd
        nextStates(prd) := PhyRegState.Allocated
        when(prd =/= prevprd) {
            nextStates(prevprd) := PhyRegState.Free
        }
        updateValid = true.B
    }

    // Handle discontinue (rollback)
    io.renameTableReadIndex := io.disconEvent.bits.renameCkptIndex
    when(io.disconEvent.valid) {
        val disconStates = WireInit(VecInit.fill(c.regFileSize)(PhyRegState.Free))
        for (i <- 0 until 31) {
            disconStates(io.renameTableReadEntry(i)) := PhyRegState.Allocated
        }
        io.setStates.bits := disconStates
        io.setStates.valid := true.B
    }.otherwise {
        io.setStates.bits := nextStates
        io.setStates.valid := updateValid
    }
}
