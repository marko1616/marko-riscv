package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend._
import markorv.bus._
import markorv.config._

class FetchQueueEntities extends Bundle {
    val instr = UInt(32.W)
    val is_branch = Bool()
    val pred_taken = Bool()
    val pred_pc = UInt(64.W)
    val recover_pc = UInt(64.W)
}

class InstrFetchQueue(implicit val config: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val cacheline_read = Flipped(Decoupled(UInt((8 * config.icache_config.data_bytes).W)))
        val reg_read = Output(UInt(5.W))
        val reg_data = Input(UInt(64.W))

        val fetch_bundle = Decoupled(new FetchQueueEntities)
        val pc = Input(UInt(64.W))
        val flush = Input(Bool())

        val fetch_pc = Output(UInt(64.W))
    })

    val bpu = Module(new BranchPredUnit)
    val instr_queue = Module(new Queue(
        new FetchQueueEntities,
        config.ifq_size,
        flow = true,
        hasFlush = true
    ))

    val end_pc_reg = RegInit(0.U(64.W))
    val end_pc = Wire(UInt(64.W))

    io.fetch_bundle <> instr_queue.io.deq
    instr_queue.io.enq.valid := false.B
    instr_queue.io.enq.bits := new FetchQueueEntities().zero

    bpu.io.bpu_instr.instr := 0.U
    bpu.io.bpu_instr.pc := 0.U
    bpu.io.reg_read <> io.reg_read
    bpu.io.reg_data <> io.reg_data

    when(instr_queue.io.count === 0.U) {
        end_pc := io.pc
    }.otherwise {
        end_pc := end_pc_reg
    }

    io.fetch_pc := end_pc
    io.cacheline_read.ready := instr_queue.io.enq.ready && !io.flush

    instr_queue.io.flush.get := io.flush

    val offset_bits = config.icache_config.offset_bits
    val pc_index = (end_pc >> 2.U)(offset_bits - 3, 0)
    val fetch_line = io.cacheline_read.bits
    val instr = (fetch_line >> (pc_index << 5))(31, 0)

    when(instr_queue.io.enq.ready && io.cacheline_read.valid && !io.flush) {
        bpu.io.bpu_instr.pc := end_pc
        bpu.io.bpu_instr.instr := instr

        instr_queue.io.enq.bits.instr := instr
        instr_queue.io.enq.bits.is_branch := bpu.io.bpu_result.is_branch
        instr_queue.io.enq.bits.pred_taken := bpu.io.bpu_result.pred_taken
        instr_queue.io.enq.bits.pred_pc := bpu.io.bpu_result.pred_pc
        instr_queue.io.enq.bits.recover_pc := bpu.io.bpu_result.recover_pc
        instr_queue.io.enq.valid := true.B

        end_pc_reg := bpu.io.bpu_result.pred_pc
    }
}
