package markorv.manage

import chisel3._
import chisel3.util._

import markorv.config._
import markorv.frontend.OpcodeBundle
import markorv.frontend.DecodedParams
import markorv.frontend.PhyRegRequests
import markorv.backend.EXUEnum

object DisconEventEnum extends ChiselEnum {
    val interrupt      = Value  // External Asynchronous Interrupt
    val instrException = Value  // Synchronous Instruction Exception (e.g. syscall, illegal instr)
    val instrRedirect  = Value  // Control-Flow Redirection by jalr
    val branchMispred  = Value  // Pipeline flush due to Branch Misprediction
    val instrSync      = Value  // Pipeline Synchronization/Flush due to Instruction Side-effects (e.g. fence.i)
    val excepReturn    = Value  // Return from Exception Handler by xret
}

object PhyRegState extends ChiselEnum {
    val Free      = Value // Not allocated, available for use
    val Allocated = Value // Holds a valid architectural value (Can't Flush this)
    val Occupied  = Value // Occupied by an issued instr but not yet written
    val Committed = Value // Written but not yet retired (speculative)
}

class RegWriteBundle(implicit val c: CoreConfig) extends Bundle {
    val addr = UInt(log2Ceil(c.regFileSize).W)
    val data = UInt(64.W)
}

class RegSetState(implicit val c: CoreConfig) extends Bundle {
    val addr = UInt(log2Ceil(c.regFileSize).W)
    val stats = new PhyRegState.Type
}

class RegisterCommit(implicit val c: CoreConfig) extends Bundle {
    val robIndex = UInt(log2Ceil(c.robSize).W)
    val data = UInt(64.W)
}

class ROBDisconField extends Bundle {
    val disconType = new DisconEventEnum.Type

    val trap  = Bool()
    val cause = UInt(16.W)
    val xtval = UInt(64.W)
    val xepc  = UInt(64.W)
    val xret  = Bool()

    val recover   = Bool()
    val recoverPc = UInt(64.W)
}

class ROBEntry(implicit val c: CoreConfig) extends Bundle {
    val valid = Bool()
    val exu = new EXUEnum.Type

    val prdValid = Bool()
    val prd      = UInt(log2Ceil(c.regFileSize).W)
    val prevprd  = UInt(log2Ceil(c.regFileSize).W)
    val pc       = UInt(64.W)

    val fCtrl = new ROBDisconField
    val commited = Bool()
    val renameCkptIndex = UInt(log2Ceil(c.renameTableSize).W)
}

class ROBAllocReq(implicit val c: CoreConfig) extends Bundle {
    val exu = new EXUEnum.Type
    val prdValid = Bool()
    val prd = UInt(log2Ceil(c.regFileSize).W)
    val prevprd = UInt(log2Ceil(c.regFileSize).W)
    val pc = UInt(64.W)
    val renameCkptIndex = UInt(log2Ceil(c.renameTableSize).W)
}

class ROBAllocResp(implicit val c: CoreConfig) extends Bundle {
    val index = UInt(log2Ceil(c.robSize).W)
}

class ROBCommitReq(implicit val c: CoreConfig) extends Bundle {
    val robIndex = UInt(log2Ceil(c.robSize).W)
    val fCtrl    = new ROBDisconField
}

class EXUParams(implicit val c: CoreConfig) extends Bundle {
    val robIndex = UInt(log2Ceil(c.robSize).W)
    val pc = UInt(64.W)
    val source1 = UInt(64.W)
    val source2 = UInt(64.W)
}

class IssueEvent(implicit val c: CoreConfig) extends Bundle {
    val prdValid = Bool()
    val prd = UInt(log2Ceil(c.regFileSize).W)
}

class CommitEvent(implicit val c: CoreConfig) extends Bundle {
    val prdValid = Bool()
    val prd = UInt(log2Ceil(c.regFileSize).W)
}

class DisconEvent(implicit val c: CoreConfig) extends Bundle {
    val disconType  = new DisconEventEnum.Type

    val prdValid = Bool()
    val prd = UInt(log2Ceil(c.regFileSize).W)
    val prevprd = UInt(log2Ceil(c.regFileSize).W)

    val renameCkptIndex = UInt(log2Ceil(c.renameTableSize).W)
}

class RetireEvent(implicit val c: CoreConfig) extends Bundle {
    // Indicates whether the instruction caused a trap.
    // In such cases, the xinstret register should not be incremented,
    // but we still generate this event to update internal states.
    // Refer to the RISC-V Privileged Spec, section 3.3.1.
    val isTrap = Bool()
    val prdValid = Bool()
    val prd = UInt(log2Ceil(c.regFileSize).W)
    val prevprd = UInt(log2Ceil(c.regFileSize).W)
}

class ReservationStationEntry(implicit val c: CoreConfig) extends Bundle {
    val valid = Bool()
    val exu = new EXUEnum.Type
    val opcodes = new OpcodeBundle
    val predTaken = Bool()
    val predPc = UInt(64.W)
    val params = new EXUParams
    val regReq = new PhyRegRequests

    def psrcValid(regStates: Vec[PhyRegState.Type]): Bool = {
        val prs1Valid = this.regReq.prs1Valid
        val prs2Valid = this.regReq.prs2Valid
        val prs1IsRd  = this.regReq.prs1IsRd
        val prs2IsRd  = this.regReq.prs2IsRd
        val prs1State = regStates(this.regReq.prs1)
        val prs2State = regStates(this.regReq.prs2)

        val prs1Ready = (prs1State === PhyRegState.Allocated || prs1State === PhyRegState.Committed) || !prs1Valid || prs1IsRd
        val prs2Ready = (prs2State === PhyRegState.Allocated || prs2State === PhyRegState.Committed) || !prs2Valid || prs2IsRd

        prs1Ready && prs2Ready
    }

    def sideEffectReady(robHeadIndex: UInt): Bool = {
        val isSideEffectExu = this.exu === EXUEnum.lsu || this.exu === EXUEnum.misc
        ~isSideEffectExu || this.params.robIndex === robHeadIndex
    }

    def ready(regStates: Vec[PhyRegState.Type], robHeadIndex: UInt): Bool = {
        this.valid && this.psrcValid(regStates) && sideEffectReady(robHeadIndex)
    }
}

abstract class BaseCommitBundle(implicit val c: CoreConfig) extends Bundle {
    val data = UInt(64.W)
    val robIndex = UInt(log2Ceil(c.robSize).W)
}

trait CommitWithDiscon extends Bundle {
    val disconType = new DisconEventEnum.Type
}

trait CommitWithTrap extends Bundle {
    val trap = Bool()
    val cause = UInt(16.W)
    val xtval = UInt(64.W)
}

trait CommitWithRecover extends Bundle {
    val recover = Bool()
    val recoverPc = UInt(64.W)
}

trait CommitWithXret extends Bundle {
    val xret = Bool()
}