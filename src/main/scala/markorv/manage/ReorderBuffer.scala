package markorv.manage

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.exception._
import markorv.config._

class ReorderBuffer(implicit val c: CoreConfig) extends Module {
    private val robIndexWidth = log2Ceil(c.robSize)
    private val renameIndexWidth = log2Ceil(c.renameTableSize)
    private val phyRegWidth = log2Ceil(c.regFileSize)

    val io = IO(new Bundle {
        val allocReq  = Flipped(Valid(new ROBAllocReq))
        val allocResp = Valid(new ROBAllocResp)

        val headIndex = Output(UInt(robIndexWidth.W))
        val commits   = Flipped(Vec(5, Valid(new ROBCommitReq)))
        val readIndexs  = Input(Vec(5, UInt(robIndexWidth.W)))
        val readEntries = Output(Vec(5, new ROBEntry))

        val renameTailIndex = Input(UInt(renameIndexWidth.W))

        val flush       = Output(Bool())
        val flushPc     = Output(UInt(64.W))

        val retireEvent = Valid(new RetireEvent)
        val disconEvent = Valid(new DisconEvent)

        val trap = Decoupled(new TrapInfo)
        val exceptionRet = Output(Bool())
    })

    val nextBuffer = Wire(Vec(c.robSize, new ROBEntry))
    val buffer = RegInit(VecInit.tabulate(c.robSize){(_) => (new ROBEntry().zero)})
    val enqPtr = RegInit(0.U(robIndexWidth.W))
    val deqPtr = RegInit(0.U(robIndexWidth.W))
    val mayFull = RegInit(false.B)
    val ptrMatch = enqPtr === deqPtr
    val full = ptrMatch && mayFull
    val empty = ptrMatch && !mayFull

    nextBuffer := buffer
    io.headIndex := deqPtr

    // Read Entry
    for ((readIndex, readEntry) <- io.readIndexs.zip(io.readEntries)) {
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
    io.retireEvent.bits.phyRdValid := nextBuffer(deqPtr).phyRdValid
    io.retireEvent.bits.phyRd := nextBuffer(deqPtr).phyRd
    io.retireEvent.bits.prevPhyRd := nextBuffer(deqPtr).prevPhyRd

    when (retireValid) {
        deqPtr := deqPtr + 1.U
        mayFull := false.B
    }

    // Allocation logic
    val allocValid = io.allocReq.valid && !full
    io.allocResp.valid := allocValid
    io.allocResp.bits.index := enqPtr

    when (allocValid) {
        nextBuffer(enqPtr).phyRdValid := io.allocReq.bits.phyRdValid
        nextBuffer(enqPtr).phyRd := io.allocReq.bits.phyRd
        nextBuffer(enqPtr).prevPhyRd := io.allocReq.bits.prevPhyRd

        nextBuffer(enqPtr).commited := false.B
        nextBuffer(enqPtr).fCtrl := new ROBDisconField().zero
        nextBuffer(enqPtr).renameCkptIndex := io.renameTailIndex

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
    when (disconEventValid) {
        enqPtr := 0.U
        deqPtr := 0.U
        mayFull := false.B
    }

    io.disconEvent.valid := disconEventValid
    io.disconEvent.bits.disconType := nextBuffer(deqPtr).fCtrl.disconType
    io.disconEvent.bits.phyRdValid := nextBuffer(deqPtr).phyRdValid
    io.disconEvent.bits.phyRd := nextBuffer(deqPtr).phyRd
    io.disconEvent.bits.prevPhyRd := nextBuffer(deqPtr).prevPhyRd
    io.disconEvent.bits.renameCkptIndex := nextBuffer(deqPtr).renameCkptIndex

    // Update buffer
    buffer := nextBuffer
}