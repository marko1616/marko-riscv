package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._

class InstrFetchUnit extends Module {
    val io = IO(new Bundle {
        val fetchBundle = Flipped(Decoupled(new FetchQueueEntities))
        val instrBundle = Decoupled(new InstrDecodeBundle)
        val invalidDrop = Input(Bool())

        val getPc = Output(UInt(64.W))
        val flush = Input(Bool())
        val flushPc = Input(UInt(64.W))
        val fetchHlt = Input(Bool())
    })

    val pc = RegInit("h00001000".U(64.W))
    val nextPc = Wire(UInt(64.W))

    // init default values
    io.instrBundle.valid := false.B
    io.instrBundle.bits.instr := new Instruction().zero
    io.instrBundle.bits.predTaken := false.B
    io.instrBundle.bits.predPc := pc
    io.instrBundle.bits.pc := pc

    io.fetchBundle.ready := io.instrBundle.ready && !io.fetchHlt
    io.getPc := pc

    when(io.fetchBundle.valid && io.instrBundle.ready && !io.fetchHlt) {
        io.instrBundle.valid := true.B
        io.instrBundle.bits.instr.rawBits := io.fetchBundle.bits.instr
        io.instrBundle.bits.predTaken := io.fetchBundle.bits.predTaken
        io.instrBundle.bits.predPc := io.fetchBundle.bits.predPc
        io.instrBundle.bits.pc := pc

        nextPc := io.fetchBundle.bits.predPc
    }.otherwise {
        nextPc := pc
    }

    pc := Mux(io.flush, io.flushPc, nextPc)
}
