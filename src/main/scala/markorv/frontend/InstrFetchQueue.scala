package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.bus._
import markorv.config._

class InstrFetchQueue(implicit val config: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val cachelineRead = Flipped(Decoupled(UInt((8 * config.icacheConfig.dataBytes).W)))

        val fetchBundle = Decoupled(new FetchQueueEntities)
        val pc = Input(UInt(64.W))
        val flush = Input(Bool())

        val fetchPc = Output(UInt(64.W))
    })

    val bpu = Module(new BranchPredUnit)
    val instrQueue = Module(new Queue(
        new FetchQueueEntities,
        config.fetchQueueSize,
        flow = true,
        hasFlush = true
    ))

    val endPcReg = RegInit(0.U(64.W))
    val endPc = Wire(UInt(64.W))

    io.fetchBundle <> instrQueue.io.deq
    instrQueue.io.enq.valid := false.B
    instrQueue.io.enq.bits := new FetchQueueEntities().zero

    bpu.io.bpuInstr.instr := 0.U
    bpu.io.bpuInstr.pc := 0.U

    when(instrQueue.io.count === 0.U) {
        endPc := io.pc
    }.otherwise {
        endPc := endPcReg
    }

    io.fetchPc := endPc
    io.cachelineRead.ready := instrQueue.io.enq.ready && !io.flush

    instrQueue.io.flush.get := io.flush

    val offsetBits = config.icacheConfig.offsetBits
    val pcIndex = (endPc >> 2.U)(offsetBits - 3, 0)
    val fetchLine = io.cachelineRead.bits
    val instr = (fetchLine >> (pcIndex << 5))(31, 0)

    when(instrQueue.io.enq.ready && io.cachelineRead.valid && !io.flush) {
        bpu.io.bpuInstr.pc := endPc
        bpu.io.bpuInstr.instr := instr

        instrQueue.io.enq.bits.instr := instr
        instrQueue.io.enq.bits.isBranch := bpu.io.bpuResult.isBranch
        instrQueue.io.enq.bits.predTaken := bpu.io.bpuResult.predTaken
        instrQueue.io.enq.bits.predPc := bpu.io.bpuResult.predPc
        instrQueue.io.enq.valid := true.B

        endPcReg := bpu.io.bpuResult.predPc
    }
}
