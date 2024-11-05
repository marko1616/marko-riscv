package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.frontend._
import markorv.cache.CacheLine

class FetchQueueEntities extends Bundle {
    val instr = UInt(32.W)
    val is_branch = Bool()
    val pred_taken = Bool()
    val pred_pc = UInt(64.W)
    val recovery_pc = UInt(64.W)
}

class InstrFetchQueue(
    queue_size: Int = 16,
    n_set: Int = 8,
    n_way: Int = 4,
    n_byte: Int = 16
) extends Module {
    val io = IO(new Bundle {
        val read_req = Decoupled(new Bundle {
            val addr = UInt(64.W)
            val size = UInt(2.W)
            // true for signed read
            val sign = Bool()
        })
        val read_data = Flipped(Decoupled(UInt(64.W)))

        val reg_read = Output(UInt(5.W))
        val reg_data = Input(UInt(64.W))

        val fetch_bundle = Decoupled(new FetchQueueEntities)
        val pc = Input(UInt(64.W))
        val flush = Input(Bool())
    })

    val bpu = Module(new BranchPredUnit)

    val instr_queue = Module(
      new Queue(
        new FetchQueueEntities,
        queue_size,
        flow = true,
        hasFlush = true
      )
    )
    val end_pc_reg = RegInit(0.U(64.W))
    val end_pc = Wire(UInt(64.W))

    io.fetch_bundle <> instr_queue.io.deq

    instr_queue.io.enq.valid := false.B
    instr_queue.io.enq.bits := 0.U.asTypeOf(new FetchQueueEntities)

    bpu.io.bpu_instr.instr := 0.U
    bpu.io.bpu_instr.pc := 0.U
    bpu.io.reg_read <> io.reg_read
    bpu.io.reg_data <> io.reg_data

    io.read_req.valid := false.B
    io.read_req.bits.addr := 0.U
    io.read_req.bits.size := 0.U
    io.read_req.bits.sign := false.B

    io.read_data.ready := false.B

    when(instr_queue.io.count === 0.U) {
        end_pc := io.pc
    }.otherwise {
        end_pc := end_pc_reg
    }

    instr_queue.io.flush.getOrElse(false.B) := io.flush

    when(instr_queue.io.enq.ready && !io.flush) {
        // Read from fetched cache line
        io.read_data.ready := true.B
        io.read_req.valid := true.B
        io.read_req.bits.addr := end_pc
        io.read_req.bits.size := 2.U
        io.read_req.bits.sign := false.B

        when(io.read_data.valid) {
            bpu.io.bpu_instr.pc := end_pc
            bpu.io.bpu_instr.instr := io.read_data.bits(31,0)
            instr_queue.io.enq.bits.instr := io.read_data.bits(31,0)
            instr_queue.io.enq.bits.is_branch := bpu.io.bpu_result.is_branch
            instr_queue.io.enq.bits.pred_taken := bpu.io.bpu_result.pred_taken
            instr_queue.io.enq.bits.pred_pc := bpu.io.bpu_result.pred_pc
            instr_queue.io.enq.bits.recovery_pc := bpu.io.bpu_result.recovery_pc
            instr_queue.io.enq.valid := true.B

            end_pc_reg := bpu.io.bpu_result.pred_pc
        }
    }
}
