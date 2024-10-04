package markorv

import chisel3._
import chisel3.util._

import markorv.FetchQueueEntities

class InstrIPBundle extends Bundle {
    val instr = Output(UInt(32.W))
    val pred_taken = Output(Bool())
    val pred_pc = Output(UInt(64.W))
    val recovery_pc = Output(UInt(64.W))
    val pc = Output(UInt(64.W))
}

class InstrFetchUnit extends Module {
    val io = IO(new Bundle {
        val fetch_bundle = Flipped(Decoupled(new FetchQueueEntities))
        val instr_bundle = Decoupled(new InstrIPBundle)

        val peek_pc = Output(UInt(64.W))
        val pc_in = Input(UInt(64.W))
        val set_pc = Input(Bool())
    })

    val pc = RegInit(0.U(64.W))
    val next_pc = Wire(UInt(64.W))

    // init default values
    io.instr_bundle.valid := false.B
    io.instr_bundle.bits.instr := 0.U(32.W)
    io.instr_bundle.bits.pred_taken := false.B
    io.instr_bundle.bits.recovery_pc := pc
    io.instr_bundle.bits.pred_pc := pc
    io.instr_bundle.bits.pc := pc

    io.fetch_bundle.ready := io.instr_bundle.ready
    io.peek_pc := pc

    when(io.fetch_bundle.valid && io.instr_bundle.ready) {
        io.instr_bundle.valid := true.B
        io.instr_bundle.bits.instr := io.fetch_bundle.bits.instr
        io.instr_bundle.bits.pred_taken := io.fetch_bundle.bits.pred_taken
        io.instr_bundle.bits.pred_pc := io.fetch_bundle.bits.pred_pc
        io.instr_bundle.bits.recovery_pc := io.fetch_bundle.bits.recovery_pc
        io.instr_bundle.bits.pc := pc

        next_pc := io.fetch_bundle.bits.pred_pc
    }.otherwise {
        next_pc := pc
    }

    when(io.set_pc) {
        pc := io.pc_in
    }.otherwise {
        pc := next_pc
    }
}
