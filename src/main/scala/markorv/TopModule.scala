package markorv

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import markorv._
import markorv.memory._
import markorv.cache.Cache

class MarkoRvCore extends Module {
    val io = IO(new Bundle {
        val pc = Output(UInt(64.W))
        val instr_now = Output(UInt(64.W))
        val peek = Output(UInt(64.W))
    })

    val mem = Module(new Memory(64, 64, 4096))
    val instr_cache = Module(new Cache(32, 4, 16, 64))

    val instr_fetch_queue = Module(new InstrFetchQueue)
    val instr_fetch_unit = Module(new InstrFetchUnit)
    val instr_decoder = Module(new InstrDecoder)
    val instr_issuer = Module(new InstrIssueUnit)

    val load_store_unit = Module(new LoadStoreUnit)
    val arithmetic_logic_unit = Module(new ArithmeticLogicUnit)
    val branch_unit = Module(new BranchUnit)

    val register_file = Module(new RegFile)
    val write_back = Module(new WriteBack)

    instr_fetch_unit.io.pc_in <> branch_unit.io.rev_pc
    instr_fetch_unit.io.set_pc <> branch_unit.io.flush
    instr_fetch_unit.io.fetch_bundle <> instr_fetch_queue.io.fetch_bundle

    instr_cache.io.mem_read_addr <> mem.io.port2.read_addr
    instr_cache.io.mem_read_data <> mem.io.port2.data_out

    instr_fetch_queue.io.flush <> branch_unit.io.flush
    instr_fetch_queue.io.read_addr <> instr_cache.io.read_addr
    instr_fetch_queue.io.read_cache_line <> instr_cache.io.read_cache_line
    instr_fetch_queue.io.pc <> instr_fetch_unit.io.peek_pc
    instr_fetch_queue.io.reg_read <> register_file.io.read_addr3
    instr_fetch_queue.io.reg_data <> register_file.io.read_data3

    mem.io.port2.write_addr := 0.U(64.W)
    mem.io.port2.write_enable := false.B
    mem.io.port2.write_data := 0.U(64.W)
    mem.io.port2.write_width := 0.U(2.W)

    load_store_unit.io.mem_write.ready := true.B

    io.pc <> instr_fetch_unit.io.instr_bundle.bits.pc
    io.instr_now <> instr_fetch_unit.io.instr_bundle.bits.instr

    io.peek <> mem.io.peek

    instr_decoder.io.reg_read1 <> register_file.io.read_addr1
    instr_decoder.io.reg_read2 <> register_file.io.read_addr2
    instr_decoder.io.reg_data1 <> register_file.io.read_data1
    instr_decoder.io.reg_data2 <> register_file.io.read_data2
    instr_decoder.io.acquire_reg <> register_file.io.acquire_reg
    instr_decoder.io.acquired <> register_file.io.acquired
    instr_decoder.io.occupied_regs <> register_file.io.peek_occupied

    mem.io.port1.write_data <> load_store_unit.io.mem_write.bits
    mem.io.port1.write_enable <> load_store_unit.io.mem_write.valid
    mem.io.port1.data_out <> load_store_unit.io.mem_read
    mem.io.port1.read_addr <> load_store_unit.io.mem_read_addr
    mem.io.port1.write_addr <> load_store_unit.io.mem_write_addr
    mem.io.port1.write_outfire <> load_store_unit.io.mem_write_outfire
    mem.io.port1.write_width <> load_store_unit.io.mem_write_width

    write_back.io.reg_write1 <> register_file.io.write_addr1
    write_back.io.write_data1 <> register_file.io.write_data1
    write_back.io.reg_write2 <> register_file.io.write_addr2
    write_back.io.write_data2 <> register_file.io.write_data2
    write_back.io.reg_write3 <> register_file.io.write_addr3
    write_back.io.write_data3 <> register_file.io.write_data3

    register_file.io.flush := branch_unit.io.flush

    PipelineConnect(
        instr_fetch_unit.io.instr_bundle,
        instr_decoder.io.instr_bundle,
        instr_decoder.io.outfire,
        branch_unit.io.flush
    )
    PipelineConnect(
        instr_decoder.io.issue_task,
        instr_issuer.io.issue_task,
        instr_issuer.io.outfire,
        branch_unit.io.flush
    )

    // Execute Units
    PipelineConnect(
        instr_issuer.io.alu_out,
        arithmetic_logic_unit.io.alu_instr,
        true.B,
        branch_unit.io.flush
    )
    PipelineConnect(
        instr_issuer.io.lsu_out,
        load_store_unit.io.lsu_instr,
        load_store_unit.io.state_peek === 0.U,
        branch_unit.io.flush
    )
    PipelineConnect(
        instr_issuer.io.branch_out,
        branch_unit.io.branch_instr,
        true.B,
        branch_unit.io.flush
    )

    // Write Back
    PipelineConnect(
        load_store_unit.io.write_back,
        write_back.io.write_back1,
        write_back.io.outfire1,
        branch_unit.io.flush
    )
    PipelineConnect(
        arithmetic_logic_unit.io.write_back,
        write_back.io.write_back2,
        write_back.io.outfire2,
        branch_unit.io.flush
    )
    PipelineConnect(
        branch_unit.io.write_back,
        write_back.io.write_back3,
        write_back.io.outfire3,
        branch_unit.io.flush
    )
}

object MarkoRvCore extends App {
    ChiselStage.emitSystemVerilogFile(
        new MarkoRvCore,
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
}
