package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.bus._
import markorv.config._
import markorv.cache._

class InstrFetchQueue(implicit val c: CoreConfig) extends Module {
    // priority: PMA Fault > PageFault
    val io = IO(new Bundle {
        val cachelineReadReq = Output(UInt(64.W))
        val cachelineReadResp = Flipped(Valid(new PreFetchedLine))

        val fetchBundle = Decoupled(new FetchQueueEntities)
        val pc = Input(UInt(64.W))
        val flush = Input(Bool())
    })

    val bpu = Module(new BranchPredUnit)
    val instrQueue = Module(new Queue(
        new FetchQueueEntities,
        c.fetchQueueSize,
        flow = true,
        hasFlush = true
    ))

    val bufferedNextPcReg = RegInit(0.U(64.W))
    val splitInstrPendingReg = RegInit(false.B)
    val splitInstrLow16Code = RegInit(ICacheCode.cacheHitOk)
    val splitInstrLow16Reg = RegInit(0.U(16.W))

    io.fetchBundle <> instrQueue.io.deq

    instrQueue.io.enq.valid := false.B
    instrQueue.io.enq.bits := new FetchQueueEntities().zero
    instrQueue.io.flush.get := io.flush

    bpu.io.bpuInstr.instr := new Instruction().zero
    bpu.io.bpuInstr.pc := 0.U

    val queueEmpty = instrQueue.io.count === 0.U
    val fetchPc = Mux(queueEmpty, io.pc, bufferedNextPcReg)

    val lineOffsetBits = c.icacheConfig.offsetBits
    val lastHalfwordOffsetInLine = (c.icacheConfig.dataBytes - 2).U(lineOffsetBits.W)

    val startsAtWordBoundary = fetchPc(1) === 0.U
    val startsAtLastHalfwordInLine = fetchPc(lineOffsetBits - 1, 0) === lastHalfwordOffsetInLine
    val cacheReadPc = Mux(splitInstrPendingReg, fetchPc + 2.U, fetchPc)
    val cachelineByteOffset = cacheReadPc(lineOffsetBits - 1, 0)
    val cachelineBits = io.cachelineReadResp.bits.data
    val cachelineCode = io.cachelineReadResp.bits.code
    val fetchedInstrBits = (cachelineBits >> (cachelineByteOffset << 3.U))(31, 0)

    io.cachelineReadReq := cacheReadPc

    val acceptFetchResp =
        instrQueue.io.enq.ready && io.cachelineReadResp.valid && !io.flush

    when(acceptFetchResp) {
        val assembledInstr = WireInit(new Instruction().zero)

        when(splitInstrPendingReg) {
            // TODO AXI Error
            splitInstrPendingReg := false.B
            val isLow16PmaFault = splitInstrLow16Code.in(ICacheCode.pmaMmuWalkErr, ICacheCode.pmaInstErr)
            val isHigh16PmaFault = cachelineCode.in(ICacheCode.pmaMmuWalkErr, ICacheCode.pmaInstErr)
            val isLow16PteFault = splitInstrLow16Code.in(ICacheCode.pageInstErr)
            val isHigh16PteFault = cachelineCode.in(ICacheCode.pageInstErr)
            // No possible for 16bits instr cross line
            val instrStatus = MuxCase(InstrStatus.instrOk32, Seq(
                isLow16PmaFault -> InstrStatus.instrPmaFaultLow,
                isHigh16PmaFault -> InstrStatus.instrPmaFaultHigh,
                isLow16PteFault -> InstrStatus.instrPageFaultLow,
                isHigh16PteFault -> InstrStatus.instrPageFaultHigh,
            ))
            assembledInstr.fromUInt(Cat(fetchedInstrBits(15, 0), splitInstrLow16Reg))
            assembledInstr.status := instrStatus
        }.otherwise {
            val isPmaFault = cachelineCode.in(ICacheCode.pmaMmuWalkErr, ICacheCode.pmaInstErr)
            val isPteFault = cachelineCode.in(ICacheCode.pageInstErr)
            val instrStatus = MuxCase(InstrStatus.instrOk32, Seq(
                isPmaFault -> InstrStatus.instrPmaFaultLow,
                isPteFault -> InstrStatus.instrPageFaultLow,
                (fetchedInstrBits(1, 0) =/= "b11".U) -> InstrStatus.instrOk16
            ))
            assembledInstr.fromUInt(fetchedInstrBits)
            assembledInstr.status := instrStatus
        }

        val needSecondCacheline =
            !startsAtWordBoundary &&
            !assembledInstr.isCompressed &&
            !splitInstrPendingReg &&
            startsAtLastHalfwordInLine

        when(needSecondCacheline) {
            splitInstrPendingReg := true.B
            splitInstrLow16Reg := fetchedInstrBits(15, 0)
            splitInstrLow16Code := cachelineCode
        }.otherwise {
            bpu.io.bpuInstr.instr := assembledInstr
            bpu.io.bpuInstr.pc := fetchPc

            instrQueue.io.enq.bits.instr := assembledInstr
            instrQueue.io.enq.bits.predTaken := bpu.io.bpuResult.predTaken
            instrQueue.io.enq.bits.predPc := bpu.io.bpuResult.predPc
            instrQueue.io.enq.valid := true.B

            bufferedNextPcReg := bpu.io.bpuResult.predPc
        }
    }

    when(io.flush) {
        splitInstrPendingReg := false.B
    }
}