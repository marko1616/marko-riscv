package markorv

import chisel3._
import chisel3.util._

import markorv.BranchPredUnit
import markorv.cache.CacheLine

class FetchQueueEntities extends Bundle {
    val instr = UInt(32.W)
    val is_branch = Bool()
    val pred_taken = Bool()
    val pred_pc = UInt(64.W)
    val recovery_pc = UInt(64.W)
}

class InstrFetchQueue(queue_size: Int = 16, n_set: Int = 8, n_way: Int = 4, n_byte: Int = 16) extends Module {
    val io = IO(new Bundle {
        val read_addr = Decoupled(UInt(64.W))
        val read_cache_line = Flipped(Decoupled(new CacheLine(n_set, n_way, n_byte)))

        val fetch_bundle = Decoupled(new FetchQueueEntities)
        val pc = Input(UInt(64.W))

        val flush = Input(Bool())
    })

    val bpu = Module(new BranchPredUnit)

    val instr_queue = Module(new Queue(
        new FetchQueueEntities, 
        queue_size,
        flow=true,
        hasFlush=true))
    val end_pc_reg = RegInit(0.U(64.W))
    val end_pc = Wire(UInt(64.W))

    val temp_cache_line = RegInit(0.U.asTypeOf(new CacheLine(n_set, n_way, n_byte)))
    val cache_line_valid = RegInit(false.B)
    val cache_line_addr = RegInit(0.U(64.W))

    io.fetch_bundle <> instr_queue.io.deq

    instr_queue.io.enq.valid := false.B
    instr_queue.io.enq.bits := 0.U.asTypeOf(new FetchQueueEntities)

    bpu.io.bpu_instr.instr := 0.U
    bpu.io.bpu_instr.pc := 0.U

    io.read_cache_line.ready := false.B
    io.read_addr.valid := false.B
    io.read_addr.bits := 0.U

    when(instr_queue.io.count === 0.U) {
        end_pc := io.pc
    }.otherwise {
        end_pc := end_pc_reg
    }

    instr_queue.io.flush.getOrElse(false.B) := io.flush

    when(instr_queue.io.enq.ready && end_pc(63, log2Ceil(n_byte)) === cache_line_addr(63, log2Ceil(n_byte)) && cache_line_valid && !io.flush) {
        // Read from fetched cache line
        val splited_data = Wire(Vec((8*n_byte)/32, UInt(32.W)))
        for(i <- 0 until (8*n_byte)/32) {
            splited_data(i) := temp_cache_line.data((32*(i+1))-1, (32*i))
        }

        bpu.io.bpu_instr.pc := end_pc
        bpu.io.bpu_instr.instr := splited_data(end_pc(log2Ceil((8*n_byte)/32)-1+2,2))

        instr_queue.io.enq.bits.instr := splited_data(end_pc(log2Ceil((8*n_byte)/32)-1+2,2))
        instr_queue.io.enq.bits.is_branch := bpu.io.bpu_result.is_branch
        instr_queue.io.enq.bits.pred_taken := bpu.io.bpu_result.pred_taken
        instr_queue.io.enq.bits.pred_pc := bpu.io.bpu_result.pred_pc
        instr_queue.io.enq.bits.recovery_pc := bpu.io.bpu_result.recovery_pc
        instr_queue.io.enq.valid := true.B

        end_pc_reg := bpu.io.bpu_result.pred_pc
    }.elsewhen(instr_queue.io.enq.ready && !io.flush) {
        // Read from upstream cache
        io.read_cache_line.ready := true.B
        io.read_addr.valid := true.B
        io.read_addr.bits := end_pc
        when(io.read_cache_line.valid) {
            temp_cache_line := io.read_cache_line.bits
            cache_line_valid := true.B
            cache_line_addr := end_pc
        }
    }
}