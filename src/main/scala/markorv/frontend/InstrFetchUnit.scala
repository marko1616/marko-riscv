package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.frontend._

class InstrIPBundle extends Bundle {
    val instr = Output(UInt(32.W))
    val pred_taken = Output(Bool())
    val pred_pc = Output(UInt(64.W))
    val recover_pc = Output(UInt(64.W))
    val pc = Output(UInt(64.W))
}

class InstrFetchUnit extends Module {
    val io = IO(new Bundle {
        val fetch_bundle = Flipped(Decoupled(new FetchQueueEntities))
        val instr_bundle = Decoupled(new InstrIPBundle)

        val exu_outfires = Input(Vec(5, Bool()))
        val invalid_drop = Input(Bool())

        val get_pc = Output(UInt(64.W))
        val set_pc = Input(UInt(64.W))
        val flush = Input(Bool())
        val fetch_hlt = Input(Bool())

        val get_fetched = Output(UInt(4.W))
    })

    val fetched_count = RegInit(0.U(4.W))
    val next_fetched_count = Wire(UInt(4.W))
    val pc = RegInit("h10000000".U(64.W))
    val next_pc = Wire(UInt(64.W))

    // init default values
    io.instr_bundle.valid := false.B
    io.instr_bundle.bits.instr := 0.U(32.W)
    io.instr_bundle.bits.pred_taken := false.B
    io.instr_bundle.bits.recover_pc := pc
    io.instr_bundle.bits.pred_pc := pc
    io.instr_bundle.bits.pc := pc

    io.fetch_bundle.ready := io.instr_bundle.ready && !io.fetch_hlt
    io.get_pc := pc
    io.get_fetched := fetched_count

    val outfire_instr = io.exu_outfires.reduce(_ | _).asTypeOf(UInt(2.W)) + io.invalid_drop.asTypeOf(UInt(2.W))
    when(io.fetch_bundle.valid && io.instr_bundle.ready && !io.fetch_hlt) {
        io.instr_bundle.valid := true.B
        io.instr_bundle.bits.instr := io.fetch_bundle.bits.instr
        io.instr_bundle.bits.pred_taken := io.fetch_bundle.bits.pred_taken
        io.instr_bundle.bits.pred_pc := io.fetch_bundle.bits.pred_pc
        io.instr_bundle.bits.recover_pc := io.fetch_bundle.bits.recover_pc
        io.instr_bundle.bits.pc := pc

        next_pc := io.fetch_bundle.bits.pred_pc
        next_fetched_count := fetched_count + 1.U - outfire_instr
    }.otherwise {
        next_pc := pc
        next_fetched_count := fetched_count - outfire_instr
    }

    when(io.flush) {
        pc := io.set_pc
        fetched_count := 0.U
    }.otherwise {
        pc := next_pc
        fetched_count := next_fetched_count
    }
}
