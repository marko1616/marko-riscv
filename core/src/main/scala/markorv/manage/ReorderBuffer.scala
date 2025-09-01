package markorv.manage

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import markorv.utils.ChiselUtils._
import markorv.backend.EXUEnum
import markorv.exception._
import markorv.config._

class ReorderBufferDebug extends DPIClockedVoidFunctionImport {
    val functionName = "update_rob"
    override val inputNames = Some(Seq("entry", "index"))
}

class ReorderBuffer(implicit val c: CoreConfig) extends Module {
    private val robIndexWidth = log2Ceil(c.robSize)
    private val renameIndexWidth = log2Ceil(c.renameTableSize)
    private val phyRegWidth = log2Ceil(c.regFileSize)

    val io = IO(new Bundle {
        // Allocation signals
        // ========================
        val allocReq  = Flipped(Valid(new ROBAllocReq))
        val allocResp = Valid(new ROBAllocResp)

        // Commit signals
        // ========================
        val commits   = Flipped(Vec(5, Valid(new ROBCommitReq)))
        val readIndices  = Input(Vec(5, UInt(robIndexWidth.W)))
        val readEntries = Output(Vec(5, new ROBEntry))

        // Retirement signals
        // ========================
        val retireEvent = Valid(new RetireEvent)
        val headIndex = Output(UInt(robIndexWidth.W))
        val robMayDison = Output(Bool())

        // Speculative control signals
        // ========================
        val flush       = Output(Bool())
        val flushPc     = Output(UInt(64.W))
        val disconEvent = Valid(new DisconEvent)

        // Rename table signals
        // ========================
        val rtRmLastCkpt = Output(Bool())
        val rtRestoreIndex = Valid(UInt(renameIndexWidth.W))

        // Exception signals
        // ========================
        val trap = Decoupled(new TrapInfo)
        val exceptionRet = Output(Bool())

        // Status signals
        // ========================
        val empty = Output(Bool())
        val full = Output(Bool())
    })

    val nextBuffer = Wire(Vec(c.robSize, new ROBEntry))
    val buffer = RegInit(VecInit.tabulate(c.robSize){(_) => (new ROBEntry().zero)})
    val lastRtCkptIndex = RegInit(0.U(renameIndexWidth.W))
    val enqPtr = RegInit(0.U(robIndexWidth.W))
    val deqPtr = RegInit(0.U(robIndexWidth.W))
    val mayFull = RegInit(false.B)
    val ptrMatch = enqPtr === deqPtr
    val full = ptrMatch && mayFull
    val empty = ptrMatch && !mayFull

    io.empty := empty
    io.full := full

    nextBuffer := buffer
    io.headIndex := deqPtr
    io.robMayDison := buffer.map(e => e.valid && (e.exu === EXUEnum.bru || e.exu === EXUEnum.lsu || e.exu === EXUEnum.misc)).reduce(_ || _)

    // Read Entry
    for ((readIndex, readEntry) <- io.readIndices.zip(io.readEntries)) {
        readEntry := buffer(readIndex)
    }

    // Commit logic
    for (commit <- io.commits) {
        when (commit.valid) {
            nextBuffer(commit.bits.robIndex).commited := true.B
            nextBuffer(commit.bits.robIndex).fCtrl := commit.bits.fCtrl
        }
    }

    // Retirement logic
    val retireValid = !empty && nextBuffer(deqPtr).commited
    io.retireEvent.valid := retireValid
    io.retireEvent.bits.isTrap := false.B
    io.retireEvent.bits.prdValid := nextBuffer(deqPtr).prdValid
    io.retireEvent.bits.prd := nextBuffer(deqPtr).prd
    io.retireEvent.bits.prevprd := nextBuffer(deqPtr).prevprd

    when (retireValid) {
        lastRtCkptIndex := nextBuffer(deqPtr).renameCkptIndex
        nextBuffer(deqPtr).valid := false.B
        deqPtr := deqPtr + 1.U
        mayFull := false.B
    }

    // Allocation logic
    val allocValid = io.allocReq.valid && !full
    io.allocResp.valid := allocValid
    io.allocResp.bits.index := enqPtr

    when (allocValid) {
        nextBuffer(enqPtr).valid := true.B
        nextBuffer(enqPtr).exu := io.allocReq.bits.exu

        nextBuffer(enqPtr).prdValid := io.allocReq.bits.prdValid
        nextBuffer(enqPtr).prd := io.allocReq.bits.prd
        nextBuffer(enqPtr).prevprd := io.allocReq.bits.prevprd
        nextBuffer(enqPtr).pc := io.allocReq.bits.pc
        nextBuffer(enqPtr).renameCkptIndex := io.allocReq.bits.renameCkptIndex

        nextBuffer(enqPtr).commited := false.B
        nextBuffer(enqPtr).fCtrl := new ROBDisconField().zero

        enqPtr := enqPtr + 1.U
        mayFull := true.B
    }

    // Branch & fence.i recover
    val recoverRequired = retireValid && nextBuffer(deqPtr).fCtrl.recover
    io.flush := recoverRequired
    io.flushPc := nextBuffer(deqPtr).fCtrl.recoverPc

    // Trap
    val trapRequired = retireValid && nextBuffer(deqPtr).fCtrl.trap
    io.trap.valid := trapRequired
    io.trap.bits.cause := nextBuffer(deqPtr).fCtrl.cause
    io.trap.bits.xepc := nextBuffer(deqPtr).pc
    when(trapRequired) {
        io.retireEvent.bits.isTrap := true.B
    }

    // Exception return
    val exceptionRetRequired = retireValid && nextBuffer(deqPtr).fCtrl.xret
    io.exceptionRet := exceptionRetRequired

    val disconEventValid = recoverRequired || trapRequired || exceptionRetRequired
    val disconType = nextBuffer(deqPtr).fCtrl.disconType
    io.disconEvent.valid := disconEventValid
    io.disconEvent.bits.disconType := disconType
    io.disconEvent.bits.prdValid := nextBuffer(deqPtr).prdValid
    io.disconEvent.bits.prd := nextBuffer(deqPtr).prd
    io.disconEvent.bits.prevprd := nextBuffer(deqPtr).prevprd
    io.disconEvent.bits.renameCkptIndex := nextBuffer(deqPtr).renameCkptIndex

    when (disconEventValid) {
        for(entry <- nextBuffer) {
            entry.valid := false.B
        }
        enqPtr := 0.U
        deqPtr := 0.U
        mayFull := false.B
        lastRtCkptIndex := 0.U
    }

    // Update Rename Table
    io.rtRestoreIndex.valid := false.B
    io.rtRestoreIndex.bits := 0.U
    io.rtRmLastCkpt := false.B
    when(retireValid) {
        when(disconEventValid) {
            io.rtRestoreIndex.valid := true.B
            io.rtRestoreIndex.bits := nextBuffer(deqPtr).renameCkptIndex
        } otherwise {
            io.rtRmLastCkpt := nextBuffer(deqPtr).renameCkptIndex =/= lastRtCkptIndex
        }
    }

    // Update buffer
    buffer := nextBuffer

    // Debug
    if(c.simulate) {
        val debugger = new ReorderBufferDebug
        for((e,i) <- buffer.zipWithIndex) {
            debugger.call(e, i.U(32.W))
        }
    }
}