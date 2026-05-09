package markorv.debug

import chisel3._
import chisel3.util._

import markorv.config._
import markorv.manage._

class InstrFetchUnitDebugIO extends Bundle {
    val pc = UInt(64.W)
    val fetchValid = Bool()
    val fetchingInstr = UInt(32.W)
}

class RegFileDebugIO(implicit val c: CoreConfig) extends Bundle {
    val regs = Vec(c.regFileSize, UInt(64.W))
    val states = Vec(c.regFileSize, new PhyRegState.Type)
}

class RenameTableDebugIO(implicit val c: CoreConfig) extends Bundle {
    private val phyRegWidth = log2Ceil(c.regFileSize)
    private val renameIndexWidth = log2Ceil(c.renameTableSize)

    val table = Vec(c.renameTableSize, Vec(31, UInt(phyRegWidth.W)))
    val enqPtr = UInt(renameIndexWidth.W)
    val deqPtr = UInt(renameIndexWidth.W)
    val tailIndex = UInt(renameIndexWidth.W)
}

class ReorderBufferDebugIO(implicit val c: CoreConfig) extends Bundle {
    private val robIndexWidth = log2Ceil(c.robSize)

    val buffer = Vec(c.robSize, new ROBEntry)
    val enqPtr = UInt(robIndexWidth.W)
    val deqPtr = UInt(robIndexWidth.W)
    val empty = Bool()
    val full = Bool()
}

class ReservationStationDebugIO(implicit val c: CoreConfig) extends Bundle {
    val buffer = Vec(c.rsSize, new ReservationStationEntry)
}

class CoreEventDebugIO(implicit val c: CoreConfig) extends Bundle {
    val issue = Valid(new IssueEvent)
    val commits = Vec(5, Valid(new CommitEvent))
    val discon = Valid(new DisconEvent)
    val retire = Valid(new RetireEvent)
}

class MarkoRvCoreDebugIO(implicit val c: CoreConfig) extends Bundle {
    val ifu = new InstrFetchUnitDebugIO
    val rf = new RegFileDebugIO
    val rt = new RenameTableDebugIO
    val rob = new ReorderBufferDebugIO
    val rs = new ReservationStationDebugIO
    val events = new CoreEventDebugIO
}