package markorv.manage

import chisel3._
import chisel3.util._

import markorv.backend.EXUEnum
import markorv.backend.EXUEnum.EXUEnumOps
import markorv.config.CoreConfig
import markorv.debug.ReorderBufferDebugIO
import markorv.trap.{ExceptionInfo, TrapReturnType}
import markorv.utils.ChiselUtils.{
    DataOperationExtension,
    UIntOperationExtension
}

class ReorderBuffer(implicit val c: CoreConfig) extends Module {
    private val robIndexWidth    = log2Ceil(c.robSize)
    private val renameIndexWidth = log2Ceil(c.renameTableSize)
    private val phyRegWidth      = log2Ceil(c.regFileSize)

    val io = IO(new Bundle {
        // Allocation signals
        // ========================
        val allocReq  = Flipped(Valid(new ROBAllocReq))
        val allocResp = Valid(new ROBAllocResp)

        // Commit signals
        // ========================
        val commits     = Flipped(Vec(5, Valid(new ROBCommitReq)))
        val readIndices = Input(Vec(5, UInt(robIndexWidth.W)))
        val readEntries = Output(Vec(5, new ROBEntry))

        // Retirement signals
        // ========================
        val retireEvent = Valid(new RetireEvent)
        val headIndex   = Output(UInt(robIndexWidth.W))
        val robMayDison = Output(Bool())

        // Speculative control signals
        // ========================
        val flush       = Output(Bool())
        val flushPc     = Output(UInt(64.W))
        val disconEvent = Valid(new DisconEvent)

        // Rename table signals
        // ========================
        val rtRmLastCkpt   = Output(Bool())
        val rtRestoreIndex = Valid(UInt(renameIndexWidth.W))

        // Trap signals
        // ========================
        val exception = Valid(new ExceptionInfo)
        val trapRet   = Valid(new TrapReturnType.Type)

        // Status signals
        // ========================
        val empty = Output(Bool())
        val full  = Output(Bool())

        // Interrupt signal
        // ========================
        val interruptHlt  = Input(Bool())
        val interruptXepc = Valid(UInt(64.W))
    })
    val dbgIo =
        if (c.simulate) Some(IO(Output(new ReorderBufferDebugIO))) else None

    val nextBuffer = Wire(Vec(c.robSize, new ROBEntry))
    val buffer = RegInit(VecInit.tabulate(c.robSize) { _ =>
        new ROBEntry().zero
    })
    val lastRtCkptIndex   = RegInit(0.U(renameIndexWidth.W))
    val enqPtr            = RegInit(0.U(robIndexWidth.W))
    val deqPtr            = RegInit(0.U(robIndexWidth.W))
    val mayFull           = RegInit(false.B)
    val ptrMatch          = enqPtr === deqPtr
    val full              = ptrMatch && mayFull
    val empty             = ptrMatch && !mayFull

    // Locks the nextPc for one instruction for interrupt's xepc
    val interruptResumePc = RegInit(0.U(64.W))

    io.empty                := empty
    io.full                 := full
    io.interruptXepc.valid  := false.B
    io.interruptXepc.bits   := 0.U
    io.exception.bits.xepc  := 0.U
    io.exception.bits.xtval := 0.U

    nextBuffer   := buffer
    io.headIndex := deqPtr
    io.robMayDison := buffer
        .map(e => e.valid && e.exu.mayDison())
        .reduce(_ || _)

    // Read Entry
    for ((readIndex, readEntry) <- io.readIndices.zip(io.readEntries))
        readEntry := buffer(readIndex)

    // Commit default
    for (commit <- io.commits)
        when(commit.valid) {
            nextBuffer(commit.bits.robIndex).commited := true.B
            when(commit.bits.fCtrl.discon) {
                nextBuffer(commit.bits.robIndex).fCtrl := commit.bits.fCtrl
            }
        }

    // Retirement default
    val retireValid = !empty && nextBuffer(deqPtr).commited
    io.retireEvent.valid            := retireValid
    io.retireEvent.bits.isException := false.B
    io.retireEvent.bits.incInstRet  := true.B
    io.retireEvent.bits.prdValid    := nextBuffer(deqPtr).prdValid
    io.retireEvent.bits.prd         := nextBuffer(deqPtr).prd
    io.retireEvent.bits.prevprd     := nextBuffer(deqPtr).prevprd

    when(retireValid) {
        lastRtCkptIndex          := nextBuffer(deqPtr).renameCkptIndex
        nextBuffer(deqPtr).valid := false.B
        deqPtr                   := deqPtr + 1.U
        mayFull                  := false.B
    }

    // Allocation default
    val allocValid = io.allocReq.valid && !full
    io.allocResp.valid      := allocValid
    io.allocResp.bits.index := enqPtr

    when(allocValid) {
        nextBuffer(enqPtr).valid := true.B
        nextBuffer(enqPtr).exu   := io.allocReq.bits.exu

        nextBuffer(enqPtr).prdValid        := io.allocReq.bits.prdValid
        nextBuffer(enqPtr).prd             := io.allocReq.bits.prd
        nextBuffer(enqPtr).prevprd         := io.allocReq.bits.prevprd
        nextBuffer(enqPtr).renameCkptIndex := io.allocReq.bits.renameCkptIndex

        nextBuffer(enqPtr).commited      := false.B
        nextBuffer(enqPtr).fCtrl         := new ROBDisconField().zero
        nextBuffer(enqPtr).fCtrl.nextPc := io.allocReq.bits.nextPc

        enqPtr  := enqPtr + 1.U
        mayFull := true.B
    }

    // Branch & fence.i recover
    val recoverRequired = retireValid && nextBuffer(
      deqPtr
    ).fCtrl.discon && (nextBuffer(deqPtr).fCtrl.disconType
        .in(DisconEventType.instrSyncNoRet, DisconEventType.instrSync))
    io.flush   := recoverRequired
    io.flushPc := nextBuffer(deqPtr).fCtrl.nextPc
    when(recoverRequired) {
        io.retireEvent.bits.incInstRet := nextBuffer(
          deqPtr
        ).fCtrl.disconType =/= DisconEventType.instrSyncNoRet
    }

    // Exception handling
    val exceptionRequired =
        retireValid && nextBuffer(deqPtr).fCtrl.discon && nextBuffer(
          deqPtr
        ).fCtrl.disconType === DisconEventType.instrException
    io.exception.valid      := exceptionRequired
    io.exception.bits.cause := nextBuffer(deqPtr).fCtrl.cause
    when(exceptionRequired) {
        io.exception.bits.xepc          := nextBuffer(deqPtr).fCtrl.xepc
        io.exception.bits.xtval         := nextBuffer(deqPtr).fCtrl.xtval
        io.retireEvent.bits.isException := true.B
        io.retireEvent.bits.incInstRet  := false.B
    }

    // Exception return
    val trapRetRequired =
        retireValid && nextBuffer(deqPtr).fCtrl.discon && nextBuffer(
          deqPtr
        ).fCtrl.disconType === DisconEventType.excepReturn
    io.trapRet.valid := trapRetRequired
    io.trapRet.bits  := nextBuffer(deqPtr).fCtrl.xretType

    val disconEventValid =
        recoverRequired || exceptionRequired || trapRetRequired
    val disconType = nextBuffer(deqPtr).fCtrl.disconType
    val disconRollBackPrevRt =
        disconType === DisconEventType.instrException && nextBuffer(
          deqPtr
        ).prdValid
    val disconRecoverRtIndex = Mux(
      disconRollBackPrevRt,
      lastRtCkptIndex,
      nextBuffer(deqPtr).renameCkptIndex
    )
    io.disconEvent.valid                := disconEventValid
    io.disconEvent.bits.disconType      := disconType
    io.disconEvent.bits.prdValid        := nextBuffer(deqPtr).prdValid
    io.disconEvent.bits.prd             := nextBuffer(deqPtr).prd
    io.disconEvent.bits.prevprd         := nextBuffer(deqPtr).prevprd
    io.disconEvent.bits.renameCkptIndex := disconRecoverRtIndex

    when(disconEventValid) {
        for (entry <- nextBuffer)
            entry.valid := false.B
        enqPtr          := 0.U
        deqPtr          := 0.U
        mayFull         := false.B
        lastRtCkptIndex := 0.U
    }

    // Update Rename Table
    io.rtRestoreIndex.valid := false.B
    io.rtRestoreIndex.bits  := 0.U
    io.rtRmLastCkpt         := false.B
    when(retireValid) {
        interruptResumePc := nextBuffer(deqPtr).fCtrl.nextPc
        when(disconEventValid) {
            io.rtRestoreIndex.valid := true.B
            io.rtRestoreIndex.bits  := disconRecoverRtIndex
        } otherwise {
            io.rtRmLastCkpt := nextBuffer(
              deqPtr
            ).renameCkptIndex =/= lastRtCkptIndex
        }
    }

    when(io.interruptHlt && empty) {
        io.interruptXepc.valid := true.B
        io.interruptXepc.bits  := interruptResumePc
    }

    // Update buffer
    buffer := nextBuffer

    // Debug
    dbgIo.foreach { dbg =>
        dbg.buffer := buffer
        dbg.enqPtr := enqPtr
        dbg.deqPtr := deqPtr
        dbg.empty  := empty
        dbg.full   := full
    }
}
