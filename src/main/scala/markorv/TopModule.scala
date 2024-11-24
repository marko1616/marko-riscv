package markorv

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import markorv._
import markorv.io._
import markorv.frontend._
import markorv.backend._
import markorv.cache.Cache
import markorv.cache.CacheLine
import markorv.cache.ReadOnlyCache
import markorv.cache.CacheReadWarpper
import markorv.cache.CacheReadWriteWarpper
import markorv.bus.AxiLiteMasterIO

class MarkoRvCore extends Module {
    val io = IO(new Bundle {
        val axi = new AxiLiteMasterIO(64, 64)

        val pc = Output(UInt(64.W))
        val instr_now = Output(UInt(64.W))
        val peek = Output(UInt(64.W))
        val debug_async_flush = Input(Bool())
        val debug_async_outfired = Output(Bool())
    })

    val axi_ctrl = Module(new AXICtrl(64, 64))
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
    val misc_unit = Module(new MiscUnit)

    val register_file = Module(new RegFile)
    val csr_file = Module(new ControlStatusRegisters)
    val int_ctrl = Module(new InterruptionControler)

    val write_back = Module(new WriteBack)

    // Exception & flush control.
    // Impossible flush at same cycle.
    val flush = int_ctrl.io.flush | branch_unit.io.flush
    int_ctrl.io.outer_int <> io.debug_async_flush
    int_ctrl.io.outer_int_outfire <> io.debug_async_outfired

    int_ctrl.io.pc <> instr_fetch_unit.io.peek_pc
    int_ctrl.io.privilege <> misc_unit.io.peek_privilege
    int_ctrl.io.fetched <> instr_fetch_unit.io.peek_fetched
    int_ctrl.io.set_exception <> csr_file.io.set_exception
    int_ctrl.io.ret_exception <> csr_file.io.ret_exception
    int_ctrl.io.mstatus <> csr_file.io.mstatus
    int_ctrl.io.mie <> csr_file.io.mie
    misc_unit.io.set_privilege <> int_ctrl.io.set_privilege
    misc_unit.io.ret <> int_ctrl.io.ret
    misc_unit.io.ret <> csr_file.io.ret


    data_cache_warpper.io.cache_write_req <> data_cache.io.write_req
    data_cache_warpper.io.cache_write_outfire <> data_cache.io.write_outfire
    data_cache.io.read_addr <> data_cache_warpper.io.read_cache_line_addr
    data_cache.io.read_cache_line <> data_cache_warpper.io.read_cache_line

    instr_cache_read_warpper.io.read_cache_line_addr <> instr_cache.io.read_addr
    instr_cache_read_warpper.io.read_cache_line <> instr_cache.io.read_cache_line

    instr_cache.io.upstream_read_addr <> axi_ctrl.io.ports(1).read_addr
    instr_cache.io.upstream_read_data <> axi_ctrl.io.ports(1).read_data

    instr_fetch_queue.io.flush <> flush
    instr_fetch_queue.io.read_req <> instr_cache_read_warpper.io.read_req
    instr_fetch_queue.io.read_data <> instr_cache_read_warpper.io.read_data
    instr_fetch_queue.io.pc <> instr_fetch_unit.io.peek_pc
    instr_fetch_queue.io.reg_read <> register_file.io.read_addrs(2)
    instr_fetch_queue.io.reg_data <> register_file.io.read_datas(2)

    instr_fetch_unit.io.invalid_drop <> instr_decoder.io.invalid_drop
    instr_fetch_unit.io.fetch_bundle <> instr_fetch_queue.io.fetch_bundle
    instr_fetch_unit.io.fetch_hlt <> int_ctrl.io.fetch_hlt
    instr_fetch_unit.io.flush <> flush
    instr_fetch_unit.io.set_pc := 0.U
    when(int_ctrl.io.flush) {
        instr_fetch_unit.io.set_pc := int_ctrl.io.set_pc
    }
    when(branch_unit.io.flush) {
        instr_fetch_unit.io.set_pc := branch_unit.io.set_pc
    }

    io.axi <> axi_ctrl.io.outer

    axi_ctrl.io.ports(1).write_req.valid := false.B
    axi_ctrl.io.ports(1).write_req.bits.size := 0.U
    axi_ctrl.io.ports(1).write_req.bits.addr := 0.U
    axi_ctrl.io.ports(1).write_req.bits.data := 0.U

    instr_issuer.io.reg_read1 <> register_file.io.read_addrs(0)
    instr_issuer.io.reg_read2 <> register_file.io.read_addrs(1)
    instr_issuer.io.reg_data1 <> register_file.io.read_datas(0)
    instr_issuer.io.reg_data2 <> register_file.io.read_datas(1)
    instr_issuer.io.occupied_regs <> register_file.io.peek_occupied
    instr_issuer.io.acquire_reg <> register_file.io.acquire_reg
    instr_issuer.io.acquired <> register_file.io.acquired

    data_cache_warpper.io.write_req <> load_store_unit.io.write_req
    data_cache_warpper.io.write_outfire <> load_store_unit.io.write_outfire
    data_cache_warpper.io.read_data <> load_store_unit.io.read_data
    data_cache_warpper.io.read_req <> load_store_unit.io.read_req

    axi_ctrl.io.ports(0).write_req <> data_cache.io.upstream_write_req
    axi_ctrl.io.ports(0).write_outfire <> data_cache.io.upstream_write_outfire
    axi_ctrl.io.ports(0).read_data <> data_cache.io.upstream_read_data
    axi_ctrl.io.ports(0).read_addr <> data_cache.io.upstream_read_addr

    write_back.io.reg_write <> register_file.io.write_addr
    write_back.io.write_data <> register_file.io.write_data

    csr_file.io.csrio <> misc_unit.io.csrio
    register_file.io.flush := flush
  
    io.pc <> instr_fetch_unit.io.instr_bundle.bits.pc
    when(instr_fetch_unit.io.instr_bundle.valid) {
        io.instr_now := instr_fetch_unit.io.instr_bundle.bits.instr
    }.otherwise{
        io.instr_now := 0.U
    }

    register_file.io.read_addrs(3) := 10.U
    io.peek := register_file.io.read_datas(3)

    // Main pipeline.
    PipelineConnect(
        instr_fetch_unit.io.instr_bundle,
        instr_decoder.io.instr_bundle,
        instr_decoder.io.outfire,
        flush
    )
    PipelineConnect(
        instr_decoder.io.issue_task,
        instr_issuer.io.issue_task,
        instr_issuer.io.outfire,
        flush
    )

    // Execute Units
    instr_fetch_unit.io.exu_outfires(0) := arithmetic_logic_unit.io.outfire
    instr_fetch_unit.io.exu_outfires(1) := load_store_unit.io.outfire
    instr_fetch_unit.io.exu_outfires(2) := misc_unit.io.outfire
    instr_fetch_unit.io.exu_outfires(3) := branch_unit.io.outfire
    PipelineConnect(
        instr_issuer.io.alu_out,
        arithmetic_logic_unit.io.alu_instr,
        arithmetic_logic_unit.io.outfire,
        flush
    )
    PipelineConnect(
        instr_issuer.io.lsu_out,
        load_store_unit.io.lsu_instr,
        load_store_unit.io.outfire,
        flush
    )
    PipelineConnect(
        instr_issuer.io.misc_out,
        misc_unit.io.misc_instr,
        misc_unit.io.outfire,
        flush
    )
    PipelineConnect(
        instr_issuer.io.branch_out,
        branch_unit.io.branch_instr,
        branch_unit.io.outfire,
        flush
    )

    // Write Back
    PipelineConnect(
        load_store_unit.io.write_back,
        write_back.io.write_backs(0),
        write_back.io.outfires(0),
        flush
    )
    PipelineConnect(
        arithmetic_logic_unit.io.write_back,
        write_back.io.write_backs(1),
        write_back.io.outfires(1),
        flush
    )
    PipelineConnect(
        branch_unit.io.write_back,
        write_back.io.write_backs(2),
        write_back.io.outfires(2),
        flush
    )
    PipelineConnect(
        misc_unit.io.write_back,
        write_back.io.write_backs(3),
        write_back.io.outfires(3),
        flush
    )
}

object MarkoRvCore extends App {
    ChiselStage.emitSystemVerilogFile(
        new MarkoRvCore,
        Array("--target-dir", "generated"),
        Array("-disable-all-randomization", "--strip-debug-info")
    )
}
