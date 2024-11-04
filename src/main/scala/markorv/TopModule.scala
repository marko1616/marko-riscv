package markorv

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import markorv._
import markorv.memory._
import markorv.cache.Cache
import markorv.cache.CacheLine
import markorv.cache.ReadOnlyCache
import markorv.cache.CacheReadWarpper
import markorv.cache.CacheReadWriteWarpper

class MarkoRvCore(init_mem: String = "/home/marko1616/marko-riscv/tests/function.hex") extends Module {
    val io = IO(new Bundle {
        val pc = Output(UInt(64.W))
        val instr_now = Output(UInt(64.W))
        val peek = Output(UInt(64.W))
    })

    val mem = Module(new Memory(init_mem, 64, 64, 4096))
    val instr_cache = Module(new ReadOnlyCache(32, 4, 16, 64))
    val instr_cache_read_warpper = Module(new CacheReadWarpper(32, 4, 16))

    val data_cache = Module(new Cache(32, 4, 16, 64))
    val data_cache_warpper = Module(new CacheReadWriteWarpper(32, 4, 16))

    val instr_fetch_queue = Module(new InstrFetchQueue)
    val instr_fetch_unit = Module(new InstrFetchUnit)
    val instr_decoder = Module(new InstrDecoder)
    val instr_issuer = Module(new InstrIssueUnit)

    val load_store_unit = Module(new LoadStoreUnit)
    val arithmetic_logic_unit = Module(new ArithmeticLogicUnit)
    val branch_unit = Module(new BranchUnit)

    val register_file = Module(new RegFile)
    val write_back = Module(new WriteBack)

    data_cache_warpper.io.cache_write_req <> data_cache.io.write_req
    data_cache_warpper.io.cache_write_outfire <> data_cache.io.write_outfire
    data_cache.io.read_addr <> data_cache_warpper.io.read_cache_line_addr
    data_cache.io.read_cache_line <> data_cache_warpper.io.read_cache_line

    instr_cache_read_warpper.io.read_cache_line_addr <> instr_cache.io.read_addr
    instr_cache_read_warpper.io.read_cache_line <> instr_cache.io.read_cache_line

    instr_fetch_unit.io.pc_in <> branch_unit.io.rev_pc
    instr_fetch_unit.io.set_pc <> branch_unit.io.flush
    instr_fetch_unit.io.fetch_bundle <> instr_fetch_queue.io.fetch_bundle

    instr_cache.io.upstream_read_addr <> mem.io.port2.read_addr
    instr_cache.io.upstream_read_data <> mem.io.port2.data_out

    instr_fetch_queue.io.flush <> branch_unit.io.flush
    instr_fetch_queue.io.read_req <> instr_cache_read_warpper.io.read_req
    instr_fetch_queue.io.read_data <> instr_cache_read_warpper.io.read_data
    instr_fetch_queue.io.pc <> instr_fetch_unit.io.peek_pc
    instr_fetch_queue.io.reg_read <> register_file.io.read_addr3
    instr_fetch_queue.io.reg_data <> register_file.io.read_data3

    mem.io.port2.mem_write_req.valid := false.B
    mem.io.port2.mem_write_req.bits.size := 0.U
    mem.io.port2.mem_write_req.bits.addr := 0.U
    mem.io.port2.mem_write_req.bits.data := 0.U

    io.pc <> instr_fetch_unit.io.instr_bundle.bits.pc
    io.instr_now <> instr_fetch_unit.io.instr_bundle.bits.instr

    register_file.io.read_addr4 := 10.U
    io.peek <> register_file.io.read_data4

    instr_issuer.io.reg_read1 <> register_file.io.read_addr1
    instr_issuer.io.reg_read2 <> register_file.io.read_addr2
    instr_issuer.io.reg_data1 <> register_file.io.read_data1
    instr_issuer.io.reg_data2 <> register_file.io.read_data2
    instr_issuer.io.occupied_regs <> register_file.io.peek_occupied
    instr_issuer.io.acquire_reg <> register_file.io.acquire_reg
    instr_issuer.io.acquired <> register_file.io.acquired

    data_cache_warpper.io.write_req <> load_store_unit.io.write_req
    data_cache_warpper.io.write_outfire <> load_store_unit.io.write_outfire
    data_cache_warpper.io.read_data <> load_store_unit.io.read_data
    data_cache_warpper.io.read_req <> load_store_unit.io.read_req

    mem.io.port1.mem_write_req <> data_cache.io.upstream_write_req
    mem.io.port1.write_outfire <> data_cache.io.upstream_write_outfire
    mem.io.port1.data_out <> data_cache.io.upstream_read_data
    mem.io.port1.read_addr <> data_cache.io.upstream_read_addr

    write_back.io.reg_write <> register_file.io.write_addr
    write_back.io.write_data <> register_file.io.write_data

    register_file.io.flush := branch_unit.io.flush

    // Main pipeline.
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
        load_store_unit.io.outfire,
        branch_unit.io.flush
    )
    PipelineConnect(
        instr_issuer.io.branch_out,
        branch_unit.io.branch_instr,
        instr_issuer.io.outfire,
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
        Array("--target-dir", "generated"),
        Array("--strip-debug-info")
    )
}
