package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.debug._

class InstrFetchUnit(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val fetchBundle = Flipped(Decoupled(new FetchQueueEntities))
        val decodeTask = Decoupled(new InstrDecodeTask)

        val pc = Output(UInt(64.W))
        val flush = Input(Bool())
        val flushPc = Input(UInt(64.W))
    })
    val dbgIo = if (c.simulate) Some(IO(Output(new InstrFetchUnitDebugIO))) else None

    val pc = RegInit(c.resetVector.U(64.W))
    val nextPc = Wire(UInt(64.W))

    // init default values
    io.decodeTask.valid := false.B
    io.decodeTask.bits.instr := new Instruction32().zero
    io.decodeTask.bits.predTaken := false.B
    io.decodeTask.bits.predPc := pc
    io.decodeTask.bits.pc := pc

    io.fetchBundle.ready := io.decodeTask.ready
    io.pc := pc

    val fetchValid = io.fetchBundle.valid && io.decodeTask.ready
    when(fetchValid) {
        io.decodeTask.valid := true.B
        io.decodeTask.bits.instr := io.fetchBundle.bits.instr.asInstruction32
        io.decodeTask.bits.predTaken := io.fetchBundle.bits.predTaken
        io.decodeTask.bits.predPc := io.fetchBundle.bits.predPc
        io.decodeTask.bits.pc := pc

        nextPc := io.fetchBundle.bits.predPc
    }.otherwise {
        nextPc := pc
    }

    pc := Mux(io.flush, io.flushPc, nextPc)

    dbgIo.foreach { dbg =>
        dbg.pc := pc
        dbg.fetchValid := fetchValid
        dbg.fetchingInstr := io.fetchBundle.bits.instr.rawBits
    }
}
