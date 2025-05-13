package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.frontend._
import markorv.utils.ChiselUtils._

class InstrFetchUnit extends Module {
    val io = IO(new Bundle {
        val fetchBundle = Flipped(Decoupled(new FetchQueueEntities))
        val instrBundle = Decoupled(new InstrDecodeBundle)

        val exuOutfires = Input(Vec(5, Bool()))
        val invalidDrop = Input(Bool())

        val getPc = Output(UInt(64.W))
        val setPc = Input(UInt(64.W))
        val flush = Input(Bool())
        val fetchHlt = Input(Bool())

        val getFetched = Output(UInt(4.W))
    })

    val fetchedCount = RegInit(0.U(4.W))
    val nextFetchedCount = Wire(UInt(4.W))
    val pc = RegInit("h00001000".U(64.W))
    val nextPc = Wire(UInt(64.W))

    // init default values
    io.instrBundle.valid := false.B
    io.instrBundle.bits.instr := new Instruction().zero
    io.instrBundle.bits.predTaken := false.B
    io.instrBundle.bits.recoverPc := pc
    io.instrBundle.bits.predPc := pc
    io.instrBundle.bits.pc := pc

    io.fetchBundle.ready := io.instrBundle.ready && !io.fetchHlt
    io.getPc := pc
    io.getFetched := fetchedCount

    val outfireInstr = io.exuOutfires.reduce(_ | _).asTypeOf(UInt(2.W)) + io.invalidDrop.asTypeOf(UInt(2.W))
    when(io.fetchBundle.valid && io.instrBundle.ready && !io.fetchHlt) {
        io.instrBundle.valid := true.B
        io.instrBundle.bits.instr.rawBits := io.fetchBundle.bits.instr
        io.instrBundle.bits.predTaken := io.fetchBundle.bits.predTaken
        io.instrBundle.bits.predPc := io.fetchBundle.bits.predPc
        io.instrBundle.bits.recoverPc := io.fetchBundle.bits.recoverPc
        io.instrBundle.bits.pc := pc

        nextPc := io.fetchBundle.bits.predPc
        nextFetchedCount := fetchedCount + 1.U - outfireInstr
    }.otherwise {
        nextPc := pc
        nextFetchedCount := fetchedCount - outfireInstr
    }

    when(io.flush) {
        pc := io.setPc
        fetchedCount := 0.U
    }.otherwise {
        pc := nextPc
        fetchedCount := nextFetchedCount
    }
}
