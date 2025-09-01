package markorv.frontend

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import markorv.utils.ChiselUtils._
import markorv.config._

class PcDebug extends DPIClockedVoidFunctionImport {
    val functionName = "update_pc"
    override val inputNames = Some(Seq("pc"))
}

class FetchDebug extends DPIClockedVoidFunctionImport {
    val functionName = "update_fetching_instr"
    override val inputNames = Some(Seq("valid", "instr"))
}

class InstrFetchUnit(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val fetchBundle = Flipped(Decoupled(new FetchQueueEntities))
        val instrBundle = Decoupled(new InstrDecodeBundle)
        val invalidDrop = Input(Bool())

        val getPc = Output(UInt(64.W))
        val flush = Input(Bool())
        val flushPc = Input(UInt(64.W))
    })

    val pc = RegInit(c.resetVector.U(64.W))
    val nextPc = Wire(UInt(64.W))

    // init default values
    io.instrBundle.valid := false.B
    io.instrBundle.bits.instr := new Instruction().zero
    io.instrBundle.bits.predTaken := false.B
    io.instrBundle.bits.predPc := pc
    io.instrBundle.bits.pc := pc

    io.fetchBundle.ready := io.instrBundle.ready
    io.getPc := pc

    val fetchValid = io.fetchBundle.valid && io.instrBundle.ready
    when(fetchValid) {
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

    if(c.simulate) {
        val pcDebugger = new PcDebug
        val fetchDebugger = new FetchDebug

        pcDebugger.call(pc)
        fetchDebugger.call(fetchValid, io.fetchBundle.bits.instr)
    }
}
