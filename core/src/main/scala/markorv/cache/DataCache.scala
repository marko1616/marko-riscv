package markorv.cache

import chisel3._
import chisel3.util._

import markorv.bus.{
    AxiResp,
    IOInterface,
    MmuMode,
    MmuReq,
    MmuResp,
    ReadParams,
    WriteParams
}
import markorv.bus.AxiResp.AxiRespOps
import markorv.config.CacheConfig
import markorv.utils.ChiselUtils.DataOperationExtension
import markorv.utils.ConfigUtils.getCacheIoConfig

class DataCache(implicit val c: CacheConfig) extends Module {
    // Transaction priority:
    // paRead > read > write > clean > invalidate > amoFlush > invalidateAll > cleanAll
    // Error priority:
    // pmaWalkErr > pmaLoadErr = pmaStorErr > pageLoadErr = pageStorErr
    // Assert paRead should only fire when the main FSM is in sIdle,
    // or stalled in sRead waiting MMU response (sRead && mmuHlt).
    val io = IO(new Bundle {
        val cacheInterface = new DcacheInterface()(c)
        val ioInterface =
            new IOInterface()(getCacheIoConfig(c, CacheType.Dcache), true)

        val privilege       = Input(UInt(2.W))
        val satpModeField   = Input(UInt(4.W))
        val statusMppField  = Input(UInt(2.W))
        val statusMprvField = Input(Bool())
        val statusSumField  = Input(Bool())
        val statusMxrField  = Input(Bool())

        val mmuReq  = Decoupled(new MmuReq)
        val mmuResp = Flipped(Valid(new MmuResp))

        val invalidateAll        = Input(Bool())
        val invalidateAllOutfire = Output(Bool())
        val cleanAll             = Input(Bool())
        val cleanAllOutfire      = Output(Bool())
    })

    // TODO: handle bus write-back error more explicitly.

    object State extends ChiselEnum {
        val sIdle, sRead, sWrite, sReplace, sVictimWriteBack, sCleanWriteBack,
            sInvalidateAll, sCleanAll = Value
    }

    object PaReadState extends ChiselEnum {
        val sIdle, sLookup, sMiss = Value
    }

    object TransactionType extends ChiselEnum {
        val read, write, clean, invalidate, amoFlush = Value
    }

    private def mergeWriteData(
        oldData: UInt,
        newData: UInt,
        mask: UInt
    ): UInt = {
        val mergedBytes = Wire(Vec(c.dataBytes, UInt(8.W)))
        for (i <- 0 until c.dataBytes) {
            val msb = (i + 1) * 8 - 1
            val lsb = i * 8
            mergedBytes(i) := Mux(mask(i), newData(msb, lsb), oldData(msb, lsb))
        }
        mergedBytes.asUInt
    }

    private def shiftLineData(data: UInt, byteOffset: UInt): UInt =
        data >> (byteOffset << 3)

    private def shiftWriteData(data: UInt, byteOffset: UInt): UInt = {
        val shifted = data << (byteOffset << 3)
        shifted((8 * c.dataBytes) - 1, 0)
    }

    private def shiftWriteMask(mask: UInt, byteOffset: UInt): UInt = {
        val shifted = mask << byteOffset
        shifted(c.dataBytes - 1, 0)
    }

    private def isLastWay(way: UInt): Bool =
        if (c.wayNum > 1) {
            way === (c.wayNum - 1).U
        } else {
            true.B
        }

    private def nextWay(way: UInt): UInt =
        if (c.wayNum > 1) {
            way + 1.U
        } else {
            0.U(c.wayBits.W)
        }

    // SRAM arrays

    val tagVArray  = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheTagValid))
    val dataArray  = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheData))
    val dirtyArray = SyncReadMem(c.setNum, Vec(c.wayNum, new CacheDirty))

    // current transaction
    val amoFlushReadLike     = RegInit(false.B)
    val transactionType      = RegInit(TransactionType.read)
    val transactionAddrLow   = RegInit(0.U(12.W))
    val transactionPaHigh    = RegInit(0.U((c.addrWidth - 12).W))
    val transactionWriteData = RegInit(0.U(64.W))
    val transactionWriteMask = RegInit(0.U(8.W))
    val transactionWriteCode = RegInit(DCacheCode.cacheHitOk)
    val transactionHitWay    = RegInit(0.U(c.wayBits.W))
    val transactionReadData  = RegInit(0.U((8 * c.dataBytes).W))
    val transactionPaLow     = transactionAddrLow(11, 0)
    val transactionPa        = Cat(transactionPaHigh, transactionPaLow)
    val transactionLineBasePa =
        Cat(transactionPa(c.addrWidth - 1, c.offsetBits), 0.U(c.offsetBits.W))
    val transactionByteOffset = transactionPa(c.offsetBits - 1, 0)
    val mmuRespLineBasePa = Cat(
      io.mmuResp.bits.pa(c.addrWidth - 1, c.offsetBits),
      0.U(c.offsetBits.W)
    )

    // paRead side-band transaction
    val paReadState   = RegInit(PaReadState.sIdle)
    val paReadAddrReg = RegInit(0.U(c.addrWidth.W))

    val paReadSet =
        if (c.setNum == 1) 0.U else paReadAddrReg(c.setEnd, c.setStart)
    val paReadTag = paReadAddrReg(c.tagEnd, c.tagStart)
    val paReadLineBasePa =
        Cat(paReadAddrReg(c.addrWidth - 1, c.offsetBits), 0.U(c.offsetBits.W))
    val paReadByteOffset = paReadAddrReg(c.offsetBits - 1, 0)

    // replacement / working-set registers

    val victimPtr = RegInit(0.U(c.wayBits.W))

    val workSetTagVReg = RegInit(
      VecInit(Seq.fill(c.wayNum)(new CacheTagValid().zero))
    )
    val workSetDataReg = RegInit(
      VecInit(Seq.fill(c.wayNum)(new CacheData().zero))
    )
    val workSetDirtyReg = RegInit(
      VecInit(Seq.fill(c.wayNum)(new CacheDirty().zero))
    )

    // clean-writeback context

    val cleanWriteBackIsCleanAll = RegInit(false.B)
    val cleanWriteBackSet        = RegInit(0.U(c.setBits.W))
    val cleanWriteBackWay        = RegInit(0.U(c.wayBits.W))
    val cleanWriteBackPa         = RegInit(0.U(c.addrWidth.W))
    val cleanWriteBackData       = RegInit(0.U((8 * c.dataBytes).W))

    // global invalidate / clean-all state

    val state               = RegInit(State.sInvalidateAll)
    val invalidateAllSetIdx = RegInit(0.U(c.setBits.W))

    val cleanAllSetIdx    = RegInit(0.U(c.setBits.W))
    val cleanAllWayPtr    = RegInit(0.U(c.wayBits.W))
    val cleanAllSetLoaded = RegInit(false.B)

    val cleanAllSetTagVReg = RegInit(
      VecInit(Seq.fill(c.wayNum)(new CacheTagValid().zero))
    )
    val cleanAllSetDataReg = RegInit(
      VecInit(Seq.fill(c.wayNum)(new CacheData().zero))
    )
    val cleanAllSetDirtyReg = RegInit(
      VecInit(Seq.fill(c.wayNum)(new CacheDirty().zero))
    )

    // SRAM read wires

    val sramReadTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sramReadData  = Wire(Vec(c.wayNum, new CacheData))
    val sramReadDirty = Wire(Vec(c.wayNum, new CacheDirty))

    val latchedValid     = RegInit(false.B)
    val latchedReadTagV  = Reg(Vec(c.wayNum, new CacheTagValid))
    val latchedReadData  = Reg(Vec(c.wayNum, new CacheData))
    val latchedReadDirty = Reg(Vec(c.wayNum, new CacheDirty))

    val finalTagV  = Mux(latchedValid, latchedReadTagV, sramReadTagV)
    val finalData  = Mux(latchedValid, latchedReadData, sramReadData)
    val finalDirty = Mux(latchedValid, latchedReadDirty, sramReadDirty)

    val reqReadVa  = io.cacheInterface.readReq.bits.vaddr
    val reqReadSet = if (c.setNum == 1) 0.U else reqReadVa(c.setEnd, c.setStart)

    val reqWriteVa = io.cacheInterface.writeReq.bits.vaddr
    val reqWriteSet =
        if (c.setNum == 1) 0.U else reqWriteVa(c.setEnd, c.setStart)

    val reqCleanVa = io.cacheInterface.cleanReq.bits.vaddr
    val reqCleanSet =
        if (c.setNum == 1) 0.U else reqCleanVa(c.setEnd, c.setStart)

    val reqInvalidateVa = io.cacheInterface.invalidateReq.bits.vaddr
    val reqInvalidateSet =
        if (c.setNum == 1) 0.U else reqInvalidateVa(c.setEnd, c.setStart)

    val reqAmoFlushVa = io.cacheInterface.amoFlushReq.bits.vaddr
    val reqAmoFlushSet =
        if (c.setNum == 1) 0.U else reqAmoFlushVa(c.setEnd, c.setStart)

    val transactionSet =
        if (c.setNum == 1) 0.U else transactionPa(c.setEnd, c.setStart)
    val transactionTag = transactionPa(c.tagEnd, c.tagStart)

    val reqPaReadPa = io.cacheInterface.paReadReq.bits.paddr
    val reqPaReadSet =
        if (c.setNum == 1) 0.U else reqPaReadPa(c.setEnd, c.setStart)

    val paReadSramReadTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val paReadSramReadData  = Wire(Vec(c.wayNum, new CacheData))
    val paReadSramReadDirty = Wire(Vec(c.wayNum, new CacheDirty))

    val paReadReadSramValid = WireDefault(false.B)
    val paReadReadSramSet   = WireDefault(0.U(c.setBits.W))

    // state local actions

    // sIdle -> latch transaction / start SRAM read
    val sIdleSetTransactionValid     = WireDefault(false.B)
    val sIdleSetTransactionType      = WireDefault(TransactionType.read)
    val sIdleSettransactionAddrLow   = WireDefault(0.U(12.W))
    val sIdleSetTransactionWriteData = WireDefault(0.U(64.W))
    val sIdleSetTransactionWriteMask = WireDefault(0.U(8.W))

    val sIdleSetTransactionPaHighValid = WireDefault(false.B)
    val sIdleSetTransactionPaHigh      = WireDefault(0.U((c.addrWidth - 12).W))

    val sIdleReadSramValid = WireDefault(false.B)
    val sIdleReadSramSet   = WireDefault(0.U(c.setBits.W))

    // sRead -> latch next transaction / start next SRAM read
    val sReadSetTransactionValid     = WireDefault(false.B)
    val sReadSetTransactionType      = WireDefault(TransactionType.read)
    val sReadSettransactionAddrLow   = WireDefault(0.U(12.W))
    val sReadSetTransactionWriteData = WireDefault(0.U(64.W))
    val sReadSetTransactionWriteMask = WireDefault(0.U(8.W))

    val sReadSetTransactionPaHighValid = WireDefault(false.B)
    val sReadSetTransactionPaHigh      = WireDefault(0.U((c.addrWidth - 12).W))

    val sReadReadSramValid = WireDefault(false.B)
    val sReadReadSramSet   = WireDefault(0.U(c.setBits.W))

    // sRead -> snapshot current set for later states
    val sReadSetWorkSetValid = WireDefault(false.B)
    val sReadSetWorkSetTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReadSetWorkSetData  = Wire(Vec(c.wayNum, new CacheData))
    val sReadSetWorkSetDirty = Wire(Vec(c.wayNum, new CacheDirty))
    sReadSetWorkSetTagV  := finalTagV
    sReadSetWorkSetData  := finalData
    sReadSetWorkSetDirty := finalDirty

    // sRead -> invalidate one line in place
    val sReadInvalidateWriteValid = WireDefault(false.B)
    val sReadInvalidateWriteSet   = WireDefault(transactionSet)
    val sReadInvalidateWriteTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReadInvalidateWriteDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sReadInvalidateWriteTagV(i)  := finalTagV(i)
        sReadInvalidateWriteDirty(i) := finalDirty(i)
    }

    // sRead / sCleanAll -> prepare clean write-back context
    val sReadSetCleanWriteBackValid      = WireDefault(false.B)
    val sReadSetCleanWriteBackIsCleanAll = WireDefault(false.B)
    val sReadSetCleanWriteBackSet        = WireDefault(0.U(c.setBits.W))
    val sReadSetCleanWriteBackWay        = WireDefault(0.U(c.wayBits.W))
    val sReadSetCleanWriteBackPa         = WireDefault(0.U(c.addrWidth.W))
    val sReadSetCleanWriteBackData       = WireDefault(0.U((8 * c.dataBytes).W))

    val sCleanAllSetCleanWriteBackValid      = WireDefault(false.B)
    val sCleanAllSetCleanWriteBackIsCleanAll = WireDefault(true.B)
    val sCleanAllSetCleanWriteBackSet        = WireDefault(0.U(c.setBits.W))
    val sCleanAllSetCleanWriteBackWay        = WireDefault(0.U(c.wayBits.W))
    val sCleanAllSetCleanWriteBackPa         = WireDefault(0.U(c.addrWidth.W))
    val sCleanAllSetCleanWriteBackData = WireDefault(0.U((8 * c.dataBytes).W))

    // sWrite -> merge a store into the working-set snapshot
    val sWriteWriteValid = WireDefault(false.B)
    val sWriteWriteSet   = WireDefault(transactionSet)
    val sWriteWriteTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sWriteWriteData  = Wire(Vec(c.wayNum, new CacheData))
    val sWriteWriteDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sWriteWriteTagV(i)  := workSetTagVReg(i)
        sWriteWriteData(i)  := workSetDataReg(i)
        sWriteWriteDirty(i) := workSetDirtyReg(i)
    }

    // sReplace -> write refill result back to SRAM
    val sReplaceWriteValid = WireDefault(false.B)
    val sReplaceWriteSet   = WireDefault(transactionSet)
    val sReplaceWriteTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReplaceWriteData  = Wire(Vec(c.wayNum, new CacheData))
    val sReplaceWriteDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sReplaceWriteTagV(i)  := workSetTagVReg(i)
        sReplaceWriteData(i)  := workSetDataReg(i)
        sReplaceWriteDirty(i) := workSetDirtyReg(i)
    }

    val sReplaceSetWorkSetValid = WireDefault(false.B)
    val sReplaceSetWorkSetTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sReplaceSetWorkSetData  = Wire(Vec(c.wayNum, new CacheData))
    val sReplaceSetWorkSetDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum) {
        sReplaceSetWorkSetTagV(i)  := sReplaceWriteTagV(i)
        sReplaceSetWorkSetData(i)  := sReplaceWriteData(i)
        sReplaceSetWorkSetDirty(i) := sReplaceWriteDirty(i)
    }

    // sCleanWriteBack -> clear dirty bit after line write-back
    val sCleanWriteBackDirtyWriteValid = WireDefault(false.B)
    val sCleanWriteBackDirtyWriteSet   = WireDefault(cleanWriteBackSet)
    val sCleanWriteBackDirtyWrite      = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum)
        sCleanWriteBackDirtyWrite(i) := new CacheDirty().zero

    val sCleanWriteBackSetCleanAllDirtyValid = WireDefault(false.B)
    val sCleanWriteBackSetCleanAllDirty = Wire(Vec(c.wayNum, new CacheDirty))
    for (i <- 0 until c.wayNum)
        sCleanWriteBackSetCleanAllDirty(i) := cleanAllSetDirtyReg(i)

    val sCleanWriteBackTagVWriteValid = WireDefault(false.B)
    val sCleanWriteBackTagVWriteSet   = WireDefault(cleanWriteBackSet)
    val sCleanWriteBackTagVWrite      = Wire(Vec(c.wayNum, new CacheTagValid))
    for (i <- 0 until c.wayNum)
        sCleanWriteBackTagVWrite(i) := workSetTagVReg(i)

    // sInvalidateAll -> clear valid + dirty for the whole set
    val sInvalidateAllWriteValid = WireDefault(false.B)
    val sInvalidateAllWriteSet   = WireDefault(invalidateAllSetIdx)
    val sInvalidateAllWriteTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sInvalidateAllWriteDirty = Wire(Vec(c.wayNum, new CacheDirty))
    sInvalidateAllWriteTagV := VecInit(
      Seq.fill(c.wayNum)(new CacheTagValid().zero)
    )
    sInvalidateAllWriteDirty := VecInit(
      Seq.fill(c.wayNum)(new CacheDirty().zero)
    )

    // sCleanAll -> snapshot one whole set, and optionally start reading the next set
    val sCleanAllSetSnapshotValid = WireDefault(false.B)
    val sCleanAllSetSnapshotTagV  = Wire(Vec(c.wayNum, new CacheTagValid))
    val sCleanAllSetSnapshotData  = Wire(Vec(c.wayNum, new CacheData))
    val sCleanAllSetSnapshotDirty = Wire(Vec(c.wayNum, new CacheDirty))
    sCleanAllSetSnapshotTagV  := sramReadTagV
    sCleanAllSetSnapshotData  := sramReadData
    sCleanAllSetSnapshotDirty := sramReadDirty

    val sCleanAllReadSramValid = WireDefault(false.B)
    val sCleanAllReadSramSet   = WireDefault(0.U(c.setBits.W))

    // defaults
    val mmuHlt    = !io.mmuReq.ready
    val mmuPaHigh = io.mmuResp.bits.pa(c.addrWidth - 1, 12)

    io.cacheInterface.paReadReq.ready     := false.B
    io.cacheInterface.readReq.ready       := false.B
    io.cacheInterface.writeReq.ready      := false.B
    io.cacheInterface.cleanReq.ready      := false.B
    io.cacheInterface.invalidateReq.ready := false.B
    io.cacheInterface.amoFlushReq.ready   := false.B

    io.cacheInterface.paReadResp.valid     := false.B
    io.cacheInterface.paReadResp.bits      := new DCachePaReadResp().zero
    io.cacheInterface.readResp.valid       := false.B
    io.cacheInterface.readResp.bits        := new DCacheReadResp().zero
    io.cacheInterface.writeResp.valid      := false.B
    io.cacheInterface.writeResp.bits       := new DCacheWriteResp().zero
    io.cacheInterface.cleanResp.valid      := false.B
    io.cacheInterface.cleanResp.bits       := new DCacheCleanResp().zero
    io.cacheInterface.invalidateResp.valid := false.B
    io.cacheInterface.invalidateResp.bits  := new DCacheInvalidateResp().zero
    io.cacheInterface.amoFlushResp.valid   := false.B
    io.cacheInterface.amoFlushResp.bits    := new DCacheAmoFlushResp().zero

    // No available for paRead
    io.cacheInterface.paddr := Mux(
      state === State.sRead,
      Cat(mmuPaHigh, transactionPaLow),
      transactionPa
    )

    io.ioInterface.read.get.params.valid := false.B
    io.ioInterface.read.get.params.bits := new ReadParams()(
      using getCacheIoConfig(c, CacheType.Dcache)
    ).zero

    io.ioInterface.write.get.params.valid := false.B
    io.ioInterface.write.get.params.bits := new WriteParams()(
      using getCacheIoConfig(c, CacheType.Dcache)
    ).zero

    io.invalidateAllOutfire := false.B
    io.cleanAllOutfire      := false.B

    io.mmuReq.valid := false.B
    io.mmuReq.bits  := new MmuReq().zero

    // Priv check && MMU Mode selection
    // Caution! SV39 supportion only and assert io.satpModeField in {0, 8}. All CSR instruction Side effect must be visible to next dcache op.
    val loadStorPriv = Mux(io.statusMprvField, io.statusMppField, io.privilege)
    val useProt      = io.satpModeField === 8.U && loadStorPriv =/= 3.U
    val mmuMode      = Mux(useProt, MmuMode.sv39, MmuMode.bare)

    val makeExcReadable = io.statusMxrField

    val mmuRespPteValid = io.mmuResp.bits.valid
    val mmuPrivValidM   = true.B
    val mmuPrivValidS   = Mux(io.statusSumField, true.B, !io.mmuResp.bits.user)
    val mmuPrivValidU   = io.mmuResp.bits.user
    val mmuPrivValid = MuxLookup(loadStorPriv, false.B)(
      Seq(
        "b00".U -> mmuPrivValidU,
        "b01".U -> mmuPrivValidS,
        "b11".U -> mmuPrivValidM
      )
    ) || !useProt
    val mmuWalkPmaFault = io.mmuResp.bits.walkPmaFault
    val mmuCommonValid  = mmuRespPteValid && mmuPrivValid
    val readPteValid =
        io.mmuResp.bits.accessed && (io.mmuResp.bits.pteRead || (io.statusMxrField && io.mmuResp.bits.pteExec))
    val writePteValid =
        io.mmuResp.bits.accessed && io.mmuResp.bits.dirty && io.mmuResp.bits.pteWrite
    val readTransactionPteValid       = readPteValid
    val writeTransactionPteValid      = writePteValid
    val cleanTransactionPteValid      = readPteValid
    val invalidateTransactionPteValid = readPteValid
    val amoFlushTransactionPteValid =
        Mux(amoFlushReadLike, readPteValid, writePteValid)

    // Caution! Assert pma.w -> pma.r and pma.a -> pma.w
    val transactionPmaCacheable       = io.mmuResp.bits.cache
    val readTransactionPmaValid       = io.mmuResp.bits.pmaRead
    val writeTransactionPmaValid      = io.mmuResp.bits.pmaWrite
    val cleanTransactionPmaValid      = io.mmuResp.bits.pmaWrite
    val invalidateTransactionPmaValid = io.mmuResp.bits.pmaWrite
    val amoFlushTransactionValid =
        io.mmuResp.bits.pmaWrite && io.mmuResp.bits.atomic

    // hit detect for current transaction
    val mmuTag    = io.mmuResp.bits.pa(c.tagEnd, c.tagStart)
    val lookupTag = mmuTag

    val readHits = finalTagV.map { e =>
        e.valid && (e.tag === lookupTag)
    }

    val readHit     = Wire(Bool())
    val hitWay      = Wire(UInt(c.wayBits.W))
    val hitData     = Wire(UInt((8 * c.dataBytes).W))
    val hitDirty    = Wire(Bool())
    val victimDirty = Wire(Bool())

    readHit  := readHits.reduce(_ || _)
    hitWay   := OHToUInt(readHits)
    hitData  := Mux(readHit, Mux1H(readHits, finalData.map(_.data)), 0.U)
    hitDirty := Mux(readHit, Mux1H(readHits, finalDirty.map(_.dirty)), false.B)
    victimDirty := finalDirty(victimPtr).dirty

    val transactionShiftedWriteData =
        shiftWriteData(transactionWriteData, transactionByteOffset)
    val transactionShiftedWriteMask =
        shiftWriteMask(transactionWriteMask, transactionByteOffset)
    val mergedWriteData = mergeWriteData(
      transactionReadData,
      transactionShiftedWriteData,
      transactionShiftedWriteMask
    )

    // request arbitration / ready
    val lookupCompletesThisCycle =
        io.mmuResp.valid && (
          (transactionType === TransactionType.read && readHit) ||
              (transactionType === TransactionType.clean && (!readHit || !hitDirty)) ||
              (transactionType === TransactionType.amoFlush && (!readHit || !hitDirty))
        )
    val paReadSafe =
        (state === State.sIdle) || ((state === State.sRead) && !io.mmuResp.valid)
    val paReadIdle = paReadState === PaReadState.sIdle
    val paReadBusy = !paReadIdle || io.cacheInterface.paReadReq.fire

    val canAcceptLocalReq =
        ((state === State.sIdle) || ((state === State.sRead) && lookupCompletesThisCycle)) &&
            !paReadBusy

    val paReadValid        = io.cacheInterface.paReadReq.valid
    val readReqValid       = io.cacheInterface.readReq.valid
    val writeReqValid      = io.cacheInterface.writeReq.valid
    val cleanReqValid      = io.cacheInterface.cleanReq.valid
    val invalidateReqValid = io.cacheInterface.invalidateReq.valid
    val amoFlushReqValid   = io.cacheInterface.amoFlushReq.valid
    val readReqReadyNoMmu =
        canAcceptLocalReq && !io.cacheInterface.paReadReq.valid
    val writeReqReadyNoMmu =
        canAcceptLocalReq && !io.cacheInterface.paReadReq.valid && !io.cacheInterface.readReq.valid
    val cleanReqReadyNoMmu = (state === State.sIdle) &&
        !paReadBusy &&
        !io.cacheInterface.paReadReq.valid &&
        !io.cacheInterface.readReq.valid &&
        !io.cacheInterface.writeReq.valid
    val invalidateReqReadyNoMmu = (state === State.sIdle) &&
        !paReadBusy &&
        !io.cacheInterface.paReadReq.valid &&
        !io.cacheInterface.readReq.valid &&
        !io.cacheInterface.writeReq.valid &&
        !io.cacheInterface.cleanReq.valid
    val amoFlushReqReadyNoMmu = (state === State.sIdle) &&
        !paReadBusy &&
        !io.cacheInterface.paReadReq.valid &&
        !io.cacheInterface.readReq.valid &&
        !io.cacheInterface.writeReq.valid &&
        !io.cacheInterface.cleanReq.valid &&
        !io.cacheInterface.invalidateReq.valid
    val paReadFire        = io.cacheInterface.paReadReq.fire
    val readReqFire       = io.cacheInterface.readReq.fire
    val writeReqFire      = io.cacheInterface.writeReq.fire
    val cleanReqFire      = io.cacheInterface.cleanReq.fire
    val invalidateReqFire = io.cacheInterface.invalidateReq.fire
    val amoFlushReqFire   = io.cacheInterface.amoFlushReq.fire

    io.cacheInterface.paReadReq.ready := paReadSafe && paReadIdle

    io.cacheInterface.readReq.ready       := readReqReadyNoMmu && !mmuHlt
    io.cacheInterface.writeReq.ready      := writeReqReadyNoMmu && !mmuHlt
    io.cacheInterface.cleanReq.ready      := cleanReqReadyNoMmu && !mmuHlt
    io.cacheInterface.invalidateReq.ready := invalidateReqReadyNoMmu && !mmuHlt
    io.cacheInterface.amoFlushReq.ready   := amoFlushReqReadyNoMmu && !mmuHlt

    // FSM
    switch(state) {
        is(State.sIdle) {
            val startInvalidateAll = io.invalidateAll && !paReadBusy
            val startCleanAll = io.cleanAll && !io.invalidateAll && !paReadBusy

            val transactionMmuReqVaValid = WireInit(false.B)
            val transactionMmuReqVa      = WireInit(0.U(64.W))
            val transactionReqVaFired    = WireInit(false.B)
            when(readReqValid && readReqReadyNoMmu) {
                transactionMmuReqVaValid := true.B
                transactionMmuReqVa      := reqReadVa
            }.elsewhen(writeReqValid && writeReqReadyNoMmu) {
                transactionMmuReqVaValid := true.B
                transactionMmuReqVa      := reqWriteVa
            }.elsewhen(cleanReqValid && cleanReqReadyNoMmu) {
                transactionMmuReqVaValid := true.B
                transactionMmuReqVa      := reqCleanVa
            }.elsewhen(invalidateReqValid && invalidateReqReadyNoMmu) {
                transactionMmuReqVaValid := true.B
                transactionMmuReqVa      := reqInvalidateVa
            }.elsewhen(amoFlushReqValid && amoFlushReqReadyNoMmu) {
                transactionMmuReqVaValid := true.B
                transactionMmuReqVa      := reqAmoFlushVa
            }

            when(readReqFire) {
                sIdleSetTransactionValid   := true.B
                sIdleSetTransactionType    := TransactionType.read
                sIdleSettransactionAddrLow := reqReadVa(11, 0)
                sIdleReadSramValid         := true.B
                sIdleReadSramSet           := reqReadSet
                transactionReqVaFired      := true.B
            }.elsewhen(writeReqFire) {
                sIdleSetTransactionValid   := true.B
                sIdleSetTransactionType    := TransactionType.write
                sIdleSettransactionAddrLow := reqWriteVa(11, 0)
                sIdleSetTransactionWriteData := io.cacheInterface.writeReq.bits.data
                sIdleSetTransactionWriteMask := io.cacheInterface.writeReq.bits.mask
                sIdleReadSramValid    := true.B
                sIdleReadSramSet      := reqWriteSet
                transactionReqVaFired := true.B
            }.elsewhen(cleanReqFire) {
                sIdleSetTransactionValid   := true.B
                sIdleSetTransactionType    := TransactionType.clean
                sIdleSettransactionAddrLow := reqCleanVa(11, 0)
                sIdleReadSramValid         := true.B
                sIdleReadSramSet           := reqCleanSet
                transactionReqVaFired      := true.B
            }.elsewhen(invalidateReqFire) {
                sIdleSetTransactionValid   := true.B
                sIdleSetTransactionType    := TransactionType.invalidate
                sIdleSettransactionAddrLow := reqInvalidateVa(11, 0)
                sIdleReadSramValid         := true.B
                sIdleReadSramSet           := reqInvalidateSet
                transactionReqVaFired      := true.B
            }.elsewhen(amoFlushReqFire) {
                sIdleSetTransactionValid   := true.B
                sIdleSetTransactionType    := TransactionType.amoFlush
                sIdleSettransactionAddrLow := reqAmoFlushVa(11, 0)
                sIdleReadSramValid         := true.B
                sIdleReadSramSet           := reqAmoFlushSet
                transactionReqVaFired      := true.B
                amoFlushReadLike := io.cacheInterface.amoFlushReq.bits.readLike
            }.elsewhen(startInvalidateAll) {
                invalidateAllSetIdx := 0.U
            }.elsewhen(startCleanAll) {
                cleanAllSetIdx         := 0.U
                cleanAllWayPtr         := 0.U
                cleanAllSetLoaded      := false.B
                sCleanAllReadSramValid := true.B
                sCleanAllReadSramSet   := 0.U
            }

            when(transactionMmuReqVaValid) {
                io.mmuReq.valid     := true.B
                io.mmuReq.bits.va   := transactionMmuReqVa
                io.mmuReq.bits.mode := mmuMode
            }

            val reqWithAddrNextState =
                Mux(transactionReqVaFired, State.sRead, State.sIdle)
            val reqForAllNextState = MuxCase(
              State.sIdle,
              Seq(
                startInvalidateAll -> State.sInvalidateAll,
                startCleanAll      -> State.sCleanAll
              )
            )
            val finalNextState = Mux(
              transactionReqVaFired,
              reqWithAddrNextState,
              reqForAllNextState
            )
            state := finalNextState
        }

        is(State.sRead) {
            val lookupTxnDoneHere        = WireDefault(false.B)
            val transactionMmuReqVaValid = WireInit(false.B)
            val transactionMmuReqVa      = WireInit(0.U(64.W))
            val transactionReqVaFired    = WireInit(false.B)
            val normalNextState          = WireDefault(State.sRead)

            val isRead       = transactionType === TransactionType.read
            val isWrite      = transactionType === TransactionType.write
            val isClean      = transactionType === TransactionType.clean
            val isInvalidate = transactionType === TransactionType.invalidate
            val isAmoFlush   = transactionType === TransactionType.amoFlush

            when(!latchedValid) {
                latchedValid     := true.B
                latchedReadTagV  := sramReadTagV
                latchedReadData  := sramReadData
                latchedReadDirty := sramReadDirty
            }

            when(io.mmuResp.valid) {
                latchedValid := false.B
                // PMA fault per transaction type
                val pmaFault = MuxLookup(transactionType, false.B)(
                  Seq(
                    TransactionType.read  -> !readTransactionPmaValid,
                    TransactionType.write -> !writeTransactionPmaValid,
                    TransactionType.clean -> !cleanTransactionPmaValid,
                    TransactionType.invalidate -> !invalidateTransactionPmaValid,
                    TransactionType.amoFlush -> !amoFlushTransactionValid
                  )
                )

                // PTE fault per transaction type
                val pteFault = MuxLookup(transactionType, false.B)(
                  Seq(
                    TransactionType.read  -> !readTransactionPteValid,
                    TransactionType.write -> !writeTransactionPteValid,
                    TransactionType.clean -> !cleanTransactionPteValid,
                    TransactionType.invalidate -> !invalidateTransactionPteValid,
                    TransactionType.amoFlush -> !amoFlushTransactionPteValid
                  )
                )

                // Error priority: pmaWalkErr > page{Load,Stor}Err(A,D,U field invalid or pte invalid) > pma{Load,Stor}Err > page{Load,Stor}Err(access invalid) > pmaCacheErr
                val transactionFault =
                    mmuWalkPmaFault || !mmuCommonValid || pmaFault || pteFault || !transactionPmaCacheable

                val pmaFaultCode =
                    MuxLookup(transactionType, DCacheCode.pmaLoadErr)(
                      Seq(
                        TransactionType.read       -> DCacheCode.pmaLoadErr,
                        TransactionType.write      -> DCacheCode.pmaStorErr,
                        TransactionType.clean      -> DCacheCode.pmaStorErr,
                        TransactionType.invalidate -> DCacheCode.pmaStorErr,
                        TransactionType.amoFlush -> Mux(
                          amoFlushReadLike,
                          DCacheCode.pmaLoadErr,
                          DCacheCode.pmaStorErr
                        )
                      )
                    )
                val pteFaultCode =
                    MuxLookup(transactionType, DCacheCode.pageLoadErr)(
                      Seq(
                        TransactionType.read       -> DCacheCode.pageLoadErr,
                        TransactionType.write      -> DCacheCode.pageStorErr,
                        TransactionType.clean      -> DCacheCode.pageStorErr,
                        TransactionType.invalidate -> DCacheCode.pageStorErr,
                        TransactionType.amoFlush -> Mux(
                          amoFlushReadLike,
                          DCacheCode.pageLoadErr,
                          DCacheCode.pageStorErr
                        )
                      )
                    )
                // First match wins in MuxCase -> walk > pma > pte
                val faultCode = MuxCase(
                  DCacheCode.pmaMmuWalkErr,
                  Seq(
                    mmuWalkPmaFault          -> DCacheCode.pmaMmuWalkErr,
                    !mmuCommonValid          -> pteFaultCode,
                    pmaFault                 -> pmaFaultCode,
                    pteFault                 -> pteFaultCode,
                    !transactionPmaCacheable -> DCacheCode.pmaCacheErr
                  )
                )
                val noFaultRespValid = !transactionFault && (
                  (isRead && readHit) ||
                      (isClean && (!readHit || !hitDirty)) ||
                      (isAmoFlush && (!readHit || !hitDirty)) ||
                      isInvalidate
                )
                val respValid = transactionFault || noFaultRespValid

                val respCode = Mux(
                  transactionFault,
                  faultCode,
                  Mux(readHit, DCacheCode.cacheHitOk, DCacheCode.cacheMissOk)
                )

                val respData = Mux(
                  !transactionFault && readHit && isRead,
                  shiftLineData(hitData, transactionByteOffset)(63, 0),
                  0.U
                )

                io.cacheInterface.readResp.valid     := isRead && respValid
                io.cacheInterface.readResp.bits.code := respCode
                io.cacheInterface.readResp.bits.data := respData

                io.cacheInterface.writeResp.valid     := isWrite && respValid
                io.cacheInterface.writeResp.bits.code := respCode

                io.cacheInterface.cleanResp.valid     := isClean && respValid
                io.cacheInterface.cleanResp.bits.code := respCode

                io.cacheInterface.invalidateResp.valid := isInvalidate && respValid
                io.cacheInterface.invalidateResp.bits.code := respCode

                io.cacheInterface.amoFlushResp.valid := isAmoFlush && respValid
                io.cacheInterface.amoFlushResp.bits.code := respCode

                lookupTxnDoneHere := respValid

                when(!transactionFault) {
                    sReadSetTransactionPaHighValid := true.B
                    sReadSetTransactionPaHigh      := mmuPaHigh

                    // Working-set snapshot: needed for write-hit, clean-hit-dirty,
                    // and read/write-miss (replacement path)
                    val needWorkSet =
                        (readHit && (isWrite || ((isClean || isAmoFlush) && hitDirty))) ||
                            (!readHit && (isRead || isWrite))
                    sReadSetWorkSetValid := needWorkSet

                    // Write-hit: latch hit metadata for sWrite
                    when(readHit && isWrite) {
                        transactionHitWay    := hitWay
                        transactionReadData  := hitData
                        transactionWriteCode := DCacheCode.cacheHitOk
                    }

                    // Clean-hit-dirty: set up write-back context
                    when(readHit && (isClean || isAmoFlush) && hitDirty) {
                        sReadSetCleanWriteBackValid      := true.B
                        sReadSetCleanWriteBackIsCleanAll := false.B
                        sReadSetCleanWriteBackSet        := transactionSet
                        sReadSetCleanWriteBackWay        := hitWay
                        sReadSetCleanWriteBackPa         := mmuRespLineBasePa
                        sReadSetCleanWriteBackData       := hitData
                    }

                    // Invalidate-hit: mark line invalid in place
                    when(
                      readHit && (isInvalidate || (isAmoFlush && !hitDirty))
                    ) {
                        sReadInvalidateWriteValid := true.B
                        for (i <- 0 until c.wayNum)
                            when(i.U === hitWay) {
                                sReadInvalidateWriteTagV(i).valid  := false.B
                                sReadInvalidateWriteDirty(i).dirty := false.B
                            }
                    }
                }

                normalNextState := MuxCase(
                  State.sRead,
                  Seq(
                    transactionFault                          -> State.sRead,
                    (!transactionFault && readHit && isWrite) -> State.sWrite,
                    (!transactionFault && readHit && (isClean || isAmoFlush) && hitDirty) -> State.sCleanWriteBack,
                    (!transactionFault && !readHit && (isRead || isWrite)) -> Mux(
                      victimDirty,
                      State.sVictimWriteBack,
                      State.sReplace
                    )
                  )
                )
            }

            val startInvalidateAll = io.invalidateAll && !paReadBusy
            val startCleanAll = io.cleanAll && !io.invalidateAll && !paReadBusy

            when(lookupTxnDoneHere) {
                when(readReqValid && readReqReadyNoMmu) {
                    transactionMmuReqVaValid := true.B
                    transactionMmuReqVa      := reqReadVa
                }.elsewhen(writeReqValid && writeReqReadyNoMmu) {
                    transactionMmuReqVaValid := true.B
                    transactionMmuReqVa      := reqWriteVa
                }

                when(readReqFire) {
                    sReadSetTransactionValid   := true.B
                    sReadSetTransactionType    := TransactionType.read
                    sReadSettransactionAddrLow := reqReadVa(11, 0)
                    sReadReadSramValid         := true.B
                    sReadReadSramSet           := reqReadSet
                    transactionReqVaFired      := true.B
                }.elsewhen(writeReqFire) {
                    sReadSetTransactionValid   := true.B
                    sReadSetTransactionType    := TransactionType.write
                    sReadSettransactionAddrLow := reqWriteVa(11, 0)
                    sReadSetTransactionWriteData := io.cacheInterface.writeReq.bits.data
                    sReadSetTransactionWriteMask := io.cacheInterface.writeReq.bits.mask
                    sReadReadSramValid    := true.B
                    sReadReadSramSet      := reqWriteSet
                    transactionReqVaFired := true.B
                }.elsewhen(startInvalidateAll) {
                    invalidateAllSetIdx := 0.U
                }.elsewhen(startCleanAll) {
                    cleanAllSetIdx         := 0.U
                    cleanAllWayPtr         := 0.U
                    cleanAllSetLoaded      := false.B
                    sCleanAllReadSramValid := true.B
                    sCleanAllReadSramSet   := 0.U
                }
            }

            when(transactionMmuReqVaValid) {
                io.mmuReq.valid     := true.B
                io.mmuReq.bits.va   := transactionMmuReqVa
                io.mmuReq.bits.mode := mmuMode
            }

            val reqWithAddrNextState =
                Mux(transactionReqVaFired, State.sRead, State.sIdle)
            val reqForAllNextState = MuxCase(
              State.sIdle,
              Seq(
                startInvalidateAll -> State.sInvalidateAll,
                startCleanAll      -> State.sCleanAll
              )
            )
            val pipelineNextState = Mux(
              transactionReqVaFired,
              reqWithAddrNextState,
              reqForAllNextState
            )
            val finalNextState =
                Mux(lookupTxnDoneHere, pipelineNextState, normalNextState)
            state := finalNextState
        }

        is(State.sWrite) {
            sWriteWriteValid := true.B
            for (i <- 0 until c.wayNum)
                when(i.U === transactionHitWay) {
                    sWriteWriteTagV(i).valid  := transactionWriteCode.isOk()
                    sWriteWriteData(i).data   := mergedWriteData
                    sWriteWriteDirty(i).dirty := transactionWriteCode.isOk()
                }

            io.cacheInterface.writeResp.valid     := true.B
            io.cacheInterface.writeResp.bits.code := transactionWriteCode

            state := State.sIdle
        }

        is(State.sReplace) {
            io.ioInterface.read.get.params.valid     := true.B
            io.ioInterface.read.get.params.bits.addr := transactionLineBasePa
            io.ioInterface.read.get.params.bits.size := c.offsetBits.U

            when(io.ioInterface.read.get.resp.valid) {
                val refillOk = io.ioInterface.read.get.resp.bits.resp.isOk()
                val refillRespData = shiftLineData(
                  io.ioInterface.read.get.resp.bits.data,
                  transactionByteOffset
                )(63, 0)

                sReplaceWriteValid := true.B
                for (i <- 0 until c.wayNum)
                    when(i.U === victimPtr) {
                        sReplaceWriteTagV(i).tag   := transactionTag
                        sReplaceWriteTagV(i).valid := refillOk
                        sReplaceWriteData(
                          i
                        ).data := io.ioInterface.read.get.resp.bits.data
                        sReplaceWriteDirty(i).dirty := false.B
                    }

                if (c.wayNum > 1) {
                    victimPtr := victimPtr + 1.U
                }

                when(transactionType === TransactionType.read) {
                    io.cacheInterface.readResp.valid := true.B
                    io.cacheInterface.readResp.bits.code.fromAxiResp(
                      io.ioInterface.read.get.resp.bits.resp,
                      false.B
                    )
                    io.cacheInterface.readResp.bits.data := refillRespData
                    state                                := State.sIdle

                }.otherwise {
                    when(refillOk) {
                        sReplaceSetWorkSetValid := true.B
                        transactionHitWay       := victimPtr
                        transactionReadData := io.ioInterface.read.get.resp.bits.data
                        transactionWriteCode := DCacheCode.cacheMissOk
                        state                := State.sWrite
                    }.otherwise {
                        io.cacheInterface.writeResp.valid := true.B
                        io.cacheInterface.writeResp.bits.code.fromAxiResp(
                          io.ioInterface.read.get.resp.bits.resp,
                          false.B
                        )
                        state := State.sIdle
                    }
                }
            }
        }

        is(State.sVictimWriteBack) {
            val dirtyVictimPa =
                if (c.setNum == 1)
                    Cat(workSetTagVReg(victimPtr).tag, 0.U(c.offsetBits.W))
                else
                    Cat(
                      workSetTagVReg(victimPtr).tag,
                      transactionSet,
                      0.U(c.offsetBits.W)
                    )

            io.ioInterface.write.get.params.valid     := true.B
            io.ioInterface.write.get.params.bits.addr := dirtyVictimPa
            io.ioInterface.write.get.params.bits.data := workSetDataReg(
              victimPtr
            ).data
            io.ioInterface.write.get.params.bits.size := c.offsetBits.U

            when(io.ioInterface.write.get.resp.valid) {
                // TODO: handle async bus write-back error
                state := State.sReplace
            }
        }

        is(State.sCleanWriteBack) {
            io.ioInterface.write.get.params.valid     := true.B
            io.ioInterface.write.get.params.bits.addr := cleanWriteBackPa
            io.ioInterface.write.get.params.bits.data := cleanWriteBackData
            io.ioInterface.write.get.params.bits.size := c.offsetBits.U

            when(io.ioInterface.write.get.resp.valid) {
                // TODO: handle async bus write-back error
                val writeBackOk = io.ioInterface.write.get.resp.bits.isOk()

                when(cleanWriteBackIsCleanAll) {
                    sCleanWriteBackDirtyWriteValid := true.B
                    sCleanWriteBackDirtyWriteSet   := cleanWriteBackSet
                    for (i <- 0 until c.wayNum) {
                        sCleanWriteBackDirtyWrite(i) := cleanAllSetDirtyReg(i)
                        sCleanWriteBackSetCleanAllDirty(
                          i
                        ) := cleanAllSetDirtyReg(i)
                        when(i.U === cleanWriteBackWay && writeBackOk) {
                            sCleanWriteBackDirtyWrite(i).dirty       := false.B
                            sCleanWriteBackSetCleanAllDirty(i).dirty := false.B
                        }
                    }
                    sCleanWriteBackSetCleanAllDirtyValid := true.B

                    if (c.wayNum > 1) {
                        when(isLastWay(cleanAllWayPtr)) {
                            when(cleanAllSetIdx === (c.setNum - 1).U) {
                                io.cleanAllOutfire := true.B
                                state              := State.sIdle
                            }.otherwise {
                                cleanAllSetIdx    := cleanAllSetIdx + 1.U
                                cleanAllWayPtr    := 0.U
                                cleanAllSetLoaded := false.B

                                sCleanAllReadSramValid := true.B
                                sCleanAllReadSramSet   := cleanAllSetIdx + 1.U

                                state := State.sCleanAll
                            }
                        }.otherwise {
                            cleanAllWayPtr := nextWay(cleanAllWayPtr)
                            state          := State.sCleanAll
                        }
                    } else {
                        when(cleanAllSetIdx === (c.setNum - 1).U) {
                            io.cleanAllOutfire := true.B
                            state              := State.sIdle
                        }.otherwise {
                            cleanAllSetIdx    := cleanAllSetIdx + 1.U
                            cleanAllWayPtr    := 0.U
                            cleanAllSetLoaded := false.B

                            sCleanAllReadSramValid := true.B
                            sCleanAllReadSramSet   := cleanAllSetIdx + 1.U

                            state := State.sCleanAll
                        }
                    }
                }.otherwise {
                    sCleanWriteBackDirtyWriteValid := true.B
                    sCleanWriteBackDirtyWriteSet   := cleanWriteBackSet
                    for (i <- 0 until c.wayNum) {
                        sCleanWriteBackDirtyWrite(i) := workSetDirtyReg(i)
                        when(i.U === cleanWriteBackWay && writeBackOk) {
                            sCleanWriteBackDirtyWrite(i).dirty := false.B
                        }
                    }

                    when(
                      transactionType === TransactionType.amoFlush && writeBackOk
                    ) {
                        sCleanWriteBackTagVWriteValid := true.B
                        sCleanWriteBackTagVWriteSet   := cleanWriteBackSet
                        for (i <- 0 until c.wayNum)
                            when(i.U === cleanWriteBackWay) {
                                sCleanWriteBackTagVWrite(i).valid := false.B
                            }
                    }

                    io.cacheInterface.cleanResp.valid := transactionType === TransactionType.clean
                    io.cacheInterface.cleanResp.bits.code := DCacheCode.cacheHitOk

                    io.cacheInterface.amoFlushResp.valid := transactionType === TransactionType.amoFlush
                    io.cacheInterface.amoFlushResp.bits.code := DCacheCode.cacheHitOk

                    when(io.invalidateAll) {
                        invalidateAllSetIdx := 0.U
                        state               := State.sInvalidateAll
                    }.elsewhen(io.cleanAll) {
                        cleanAllSetIdx    := 0.U
                        cleanAllWayPtr    := 0.U
                        cleanAllSetLoaded := false.B

                        sCleanAllReadSramValid := true.B
                        sCleanAllReadSramSet   := 0.U

                        state := State.sCleanAll
                    }.otherwise {
                        state := State.sIdle
                    }
                }
            }
        }

        is(State.sInvalidateAll) {
            sInvalidateAllWriteValid := true.B
            sInvalidateAllWriteSet   := invalidateAllSetIdx

            when(invalidateAllSetIdx === (c.setNum - 1).U) {
                io.invalidateAllOutfire := true.B
                state                   := State.sIdle
            }.otherwise {
                invalidateAllSetIdx := invalidateAllSetIdx + 1.U
            }
        }

        is(State.sCleanAll) {
            when(!cleanAllSetLoaded) {
                sCleanAllSetSnapshotValid := true.B
                cleanAllSetLoaded         := true.B
                cleanAllWayPtr            := 0.U
            }.otherwise {
                when(
                  cleanAllSetTagVReg(
                    cleanAllWayPtr
                  ).valid && cleanAllSetDirtyReg(cleanAllWayPtr).dirty
                ) {
                    sCleanAllSetCleanWriteBackValid := true.B
                    sCleanAllSetCleanWriteBackSet   := cleanAllSetIdx
                    sCleanAllSetCleanWriteBackWay   := cleanAllWayPtr
                    sCleanAllSetCleanWriteBackPa :=
                        Cat(
                          cleanAllSetTagVReg(cleanAllWayPtr).tag,
                          cleanAllSetIdx,
                          0.U(c.offsetBits.W)
                        )
                    sCleanAllSetCleanWriteBackData := cleanAllSetDataReg(
                      cleanAllWayPtr
                    ).data

                    state := State.sCleanWriteBack
                }.otherwise {
                    if (c.wayNum > 1) {
                        when(isLastWay(cleanAllWayPtr)) {
                            when(cleanAllSetIdx === (c.setNum - 1).U) {
                                io.cleanAllOutfire := true.B
                                state              := State.sIdle
                            }.otherwise {
                                cleanAllSetIdx    := cleanAllSetIdx + 1.U
                                cleanAllWayPtr    := 0.U
                                cleanAllSetLoaded := false.B

                                sCleanAllReadSramValid := true.B
                                sCleanAllReadSramSet   := cleanAllSetIdx + 1.U

                                state := State.sCleanAll
                            }
                        }.otherwise {
                            cleanAllWayPtr := nextWay(cleanAllWayPtr)
                            state          := State.sCleanAll
                        }
                    } else {
                        when(cleanAllSetIdx === (c.setNum - 1).U) {
                            io.cleanAllOutfire := true.B
                            state              := State.sIdle
                        }.otherwise {
                            cleanAllSetIdx    := cleanAllSetIdx + 1.U
                            cleanAllWayPtr    := 0.U
                            cleanAllSetLoaded := false.B

                            sCleanAllReadSramValid := true.B
                            sCleanAllReadSramSet   := cleanAllSetIdx + 1.U

                            state := State.sCleanAll
                        }
                    }
                }
            }
        }
    }

    val paReadHit     = Wire(Bool())
    val paReadHitData = Wire(UInt((8 * c.dataBytes).W))

    val paReadHits = paReadSramReadTagV.map { e =>
        e.valid && (e.tag === paReadTag)
    }

    paReadHit := paReadHits.reduce(_ || _)
    paReadHitData := Mux(
      paReadHit,
      Mux1H(paReadHits, paReadSramReadData.map(_.data)),
      0.U
    )

    // paRead FSM
    switch(paReadState) {
        is(PaReadState.sIdle) {
            when(paReadFire) {
                paReadAddrReg       := reqPaReadPa
                paReadReadSramValid := true.B
                paReadReadSramSet   := reqPaReadSet
                paReadState         := PaReadState.sLookup
            }
        }

        is(PaReadState.sLookup) {
            when(paReadHit) {
                io.cacheInterface.paReadResp.valid     := true.B
                io.cacheInterface.paReadResp.bits.code := DCacheCode.cacheHitOk
                io.cacheInterface.paReadResp.bits.data := shiftLineData(
                  paReadHitData,
                  paReadByteOffset
                )(63, 0)
                paReadState := PaReadState.sIdle
            }.otherwise {
                paReadState := PaReadState.sMiss
            }
        }

        is(PaReadState.sMiss) {
            io.ioInterface.read.get.params.valid     := true.B
            io.ioInterface.read.get.params.bits.addr := paReadLineBasePa
            io.ioInterface.read.get.params.bits.size := c.offsetBits.U

            when(io.ioInterface.read.get.resp.valid) {
                io.cacheInterface.paReadResp.valid := true.B
                io.cacheInterface.paReadResp.bits.code.fromAxiResp(
                  io.ioInterface.read.get.resp.bits.resp,
                  false.B
                )
                io.cacheInterface.paReadResp.bits.data := shiftLineData(
                  io.ioInterface.read.get.resp.bits.data,
                  paReadByteOffset
                )(63, 0)
                paReadState := PaReadState.sIdle
            }
        }
    }

    // commit actions

    val finalSetTransactionValid =
        sIdleSetTransactionValid || sReadSetTransactionValid
    when(finalSetTransactionValid) {
        transactionType :=
            Mux(
              sIdleSetTransactionValid,
              sIdleSetTransactionType,
              sReadSetTransactionType
            )
        transactionAddrLow :=
            Mux(
              sIdleSetTransactionValid,
              sIdleSettransactionAddrLow,
              sReadSettransactionAddrLow
            )
        transactionWriteData :=
            Mux(
              sIdleSetTransactionValid,
              sIdleSetTransactionWriteData,
              sReadSetTransactionWriteData
            )
        transactionWriteMask :=
            Mux(
              sIdleSetTransactionValid,
              sIdleSetTransactionWriteMask,
              sReadSetTransactionWriteMask
            )
    }

    val finalSetTransactionPaHighValid =
        sIdleSetTransactionPaHighValid || sReadSetTransactionPaHighValid

    when(finalSetTransactionPaHighValid) {
        transactionPaHigh :=
            Mux(
              sIdleSetTransactionPaHighValid,
              sIdleSetTransactionPaHigh,
              sReadSetTransactionPaHigh
            )
    }

    when(sReadSetWorkSetValid) {
        workSetTagVReg  := sReadSetWorkSetTagV
        workSetDataReg  := sReadSetWorkSetData
        workSetDirtyReg := sReadSetWorkSetDirty
    }

    when(sReplaceSetWorkSetValid) {
        workSetTagVReg  := sReplaceSetWorkSetTagV
        workSetDataReg  := sReplaceSetWorkSetData
        workSetDirtyReg := sReplaceSetWorkSetDirty
    }

    val finalSetCleanWriteBackValid =
        sReadSetCleanWriteBackValid || sCleanAllSetCleanWriteBackValid

    when(finalSetCleanWriteBackValid) {
        cleanWriteBackIsCleanAll :=
            Mux(
              sReadSetCleanWriteBackValid,
              sReadSetCleanWriteBackIsCleanAll,
              sCleanAllSetCleanWriteBackIsCleanAll
            )
        cleanWriteBackSet :=
            Mux(
              sReadSetCleanWriteBackValid,
              sReadSetCleanWriteBackSet,
              sCleanAllSetCleanWriteBackSet
            )
        cleanWriteBackWay :=
            Mux(
              sReadSetCleanWriteBackValid,
              sReadSetCleanWriteBackWay,
              sCleanAllSetCleanWriteBackWay
            )
        cleanWriteBackPa :=
            Mux(
              sReadSetCleanWriteBackValid,
              sReadSetCleanWriteBackPa,
              sCleanAllSetCleanWriteBackPa
            )
        cleanWriteBackData :=
            Mux(
              sReadSetCleanWriteBackValid,
              sReadSetCleanWriteBackData,
              sCleanAllSetCleanWriteBackData
            )
    }

    when(sCleanAllSetSnapshotValid) {
        cleanAllSetTagVReg  := sCleanAllSetSnapshotTagV
        cleanAllSetDataReg  := sCleanAllSetSnapshotData
        cleanAllSetDirtyReg := sCleanAllSetSnapshotDirty
    }

    when(sCleanWriteBackSetCleanAllDirtyValid) {
        cleanAllSetDirtyReg := sCleanWriteBackSetCleanAllDirty
    }

    when(sWriteWriteValid) {
        tagVArray.write(sWriteWriteSet, sWriteWriteTagV)
        dataArray.write(sWriteWriteSet, sWriteWriteData)
        dirtyArray.write(sWriteWriteSet, sWriteWriteDirty)
    }

    when(sReplaceWriteValid) {
        tagVArray.write(sReplaceWriteSet, sReplaceWriteTagV)
        dataArray.write(sReplaceWriteSet, sReplaceWriteData)
        dirtyArray.write(sReplaceWriteSet, sReplaceWriteDirty)
    }

    when(sReadInvalidateWriteValid) {
        tagVArray.write(sReadInvalidateWriteSet, sReadInvalidateWriteTagV)
        dirtyArray.write(sReadInvalidateWriteSet, sReadInvalidateWriteDirty)
    }

    when(sCleanWriteBackTagVWriteValid) {
        tagVArray.write(sCleanWriteBackTagVWriteSet, sCleanWriteBackTagVWrite)
    }

    when(sCleanWriteBackDirtyWriteValid) {
        dirtyArray.write(
          sCleanWriteBackDirtyWriteSet,
          sCleanWriteBackDirtyWrite
        )
    }

    when(sInvalidateAllWriteValid) {
        tagVArray.write(sInvalidateAllWriteSet, sInvalidateAllWriteTagV)
        dirtyArray.write(sInvalidateAllWriteSet, sInvalidateAllWriteDirty)
    }

    // unified SRAM read port
    val finalMainSramReadValid =
        sIdleReadSramValid ||
            sReadReadSramValid ||
            sCleanAllReadSramValid

    val finalMainSramReadSet = WireDefault(0.U(c.setBits.W))

    when(sIdleReadSramValid) {
        finalMainSramReadSet := sIdleReadSramSet
    }
    when(sReadReadSramValid) {
        finalMainSramReadSet := sReadReadSramSet
    }
    when(sCleanAllReadSramValid) {
        finalMainSramReadSet := sCleanAllReadSramSet
    }

    sramReadTagV := tagVArray.read(finalMainSramReadSet, finalMainSramReadValid)
    sramReadData := dataArray.read(finalMainSramReadSet, finalMainSramReadValid)
    sramReadDirty := dirtyArray.read(
      finalMainSramReadSet,
      finalMainSramReadValid
    )

    paReadSramReadTagV := tagVArray.read(paReadReadSramSet, paReadReadSramValid)
    paReadSramReadData := dataArray.read(paReadReadSramSet, paReadReadSramValid)
    paReadSramReadDirty := dirtyArray.read(
      paReadReadSramSet,
      paReadReadSramValid
    )
}
