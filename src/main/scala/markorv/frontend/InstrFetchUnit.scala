package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.frontend._

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

        val exu_outfires = Input(Vec(4, Bool()))
        val invalid_drop = Input(Bool())

        val peek_pc = Output(UInt(64.W))
        val pc_in = Input(UInt(64.W))
        val flush = Input(Bool())
        val hold_fire = Input(Bool())

        val peek_fetched = Output(UInt(4.W))
    })

    val fetched_count = RegInit(0.U(4.W))
    val next_fetched_count = Wire(UInt(4.W))
    val pc = RegInit(0.U(64.W))
    val next_pc = Wire(UInt(64.W))

    // init default values
    io.instr_bundle.valid := false.B
    io.instr_bundle.bits.instr := 0.U(32.W)
    io.instr_bundle.bits.pred_taken := false.B
    io.instr_bundle.bits.recovery_pc := pc
    io.instr_bundle.bits.pred_pc := pc
    io.instr_bundle.bits.pc := pc

    io.fetch_bundle.ready := io.instr_bundle.ready && !io.hold_fire
    io.peek_pc := pc

    val outfired_instr = io.exu_outfires.reduce(_ | _) + io.invalid_drop
    when(io.fetch_bundle.valid && io.instr_bundle.ready && !io.hold_fire) {
        io.instr_bundle.valid := true.B
        io.instr_bundle.bits.instr := io.fetch_bundle.bits.instr
        io.instr_bundle.bits.pred_taken := io.fetch_bundle.bits.pred_taken
        io.instr_bundle.bits.pred_pc := io.fetch_bundle.bits.pred_pc
        io.instr_bundle.bits.recovery_pc := io.fetch_bundle.bits.recovery_pc
        io.instr_bundle.bits.pc := pc

        next_pc := io.fetch_bundle.bits.pred_pc
        next_fetched_count := fetched_count + 1.U - outfired_instr
    }.otherwise {
        next_pc := pc
        next_fetched_count := fetched_count - outfired_instr
    }

    when(io.flush) {
        pc := io.pc_in
        fetched_count := 0.U
        io.peek_fetched := 0.U
    }.otherwise {
        pc := next_pc
        fetched_count := next_fetched_count
        io.peek_fetched := next_fetched_count
    }
}
