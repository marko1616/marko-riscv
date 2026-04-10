package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.bus._
import markorv.config._

class InstrFetchQueue(implicit val config: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val cachelineReadReq = Output(UInt(64.W))
        val cachelineReadResp = Flipped(Valid(UInt((8 * config.icacheConfig.dataBytes).W)))

        val fetchBundle = Decoupled(new FetchQueueEntities)
        val pc = Input(UInt(64.W))
        val flush = Input(Bool())
    })

    val bpu = Module(new BranchPredUnit)
    val instrQueue = Module(new Queue(
        new FetchQueueEntities,
        config.fetchQueueSize,
        flow = true,
        hasFlush = true
    ))

    val bufferedNextPcReg = RegInit(0.U(64.W))
    val splitInstrPendingReg = RegInit(false.B)
    val splitInstrLow16Reg = RegInit(0.U(16.W))

    io.fetchBundle <> instrQueue.io.deq

    instrQueue.io.enq.valid := false.B
    instrQueue.io.enq.bits := new FetchQueueEntities().zero
    instrQueue.io.flush.get := io.flush

    bpu.io.bpuInstr.instr := new Instruction().zero
    bpu.io.bpuInstr.pc := 0.U

    val queueEmpty = instrQueue.io.count === 0.U
    val fetchPc = Mux(queueEmpty, io.pc, bufferedNextPcReg)

    val lineOffsetBits = config.icacheConfig.offsetBits
    val lastHalfwordOffsetInLine = (config.icacheConfig.dataBytes - 2).U(lineOffsetBits.W)

    val startsAtWordBoundary = fetchPc(1) === 0.U
    val startsAtLastHalfwordInLine =
        fetchPc(lineOffsetBits - 1, 0) === lastHalfwordOffsetInLine

    val cacheReadPc = Mux(splitInstrPendingReg, fetchPc + 2.U, fetchPc)
    val cachelineByteOffset = cacheReadPc(lineOffsetBits - 1, 0)
    val cachelineBits = io.cachelineReadResp.bits
    val fetchedInstrBits = (cachelineBits >> (cachelineByteOffset << 3.U))(31, 0)

    io.cachelineReadReq := cacheReadPc

    val acceptFetchResp =
        instrQueue.io.enq.ready && io.cachelineReadResp.valid && !io.flush

    when(acceptFetchResp) {
        val assembledInstr = WireInit(new Instruction().zero)

        when(splitInstrPendingReg) {
            splitInstrPendingReg := false.B
            assembledInstr.fromUInt(Cat(fetchedInstrBits(15, 0), splitInstrLow16Reg))
        }.otherwise {
            assembledInstr.fromUInt(fetchedInstrBits)
        }

        val needSecondCacheline =
            !startsAtWordBoundary &&
            !assembledInstr.isCompressed &&
            !splitInstrPendingReg &&
            startsAtLastHalfwordInLine

        when(needSecondCacheline) {
            splitInstrPendingReg := true.B
            splitInstrLow16Reg := fetchedInstrBits(15, 0)
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