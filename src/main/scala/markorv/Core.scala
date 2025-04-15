package markorv

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import markorv.config._
import markorv.frontend._
import markorv.backend._
import markorv.bus._
import markorv.cache._

class MarkoRvCore extends Module {
    implicit val config: CoreConfig = new CoreConfig
    val io = IO(new Bundle {
        val axi = new AxiInterface(config.axi_config)
        val time = Input(UInt(config.data_width.W))
        val pc = Output(UInt(config.data_width.W))
        val instr_now = Output(UInt(config.data_width.W))
    })

    // Submodule Instantiations
    // ========================
    // Bus Controllers
    val axi_ctrl = Module(new AxiCtrl)

    // Frontend Pipeline
    val instr_cache = Module(new InstrCache()(config.icache_config))
    val instr_prefetch = Module(new InstrPrefetchUnit)
    val instr_fetch_queue = Module(new InstrFetchQueue)
    val instr_fetch_unit = Module(new InstrFetchUnit)
    val instr_decoder = Module(new InstrDecoder)
    val instr_issuer = Module(new InstrIssueUnit)

    // Execution Units
    val arithmetic_logic_unit = Module(new ArithmeticLogicUnit)
    val load_store_unit = Module(new LoadStoreUnit)
    val branch_unit = Module(new BranchUnit)
    val misc_unit = Module(new MiscUnit)
    val multiply_unit = Module(new MultiplyUnit)

    // Register Files and State
    val register_file = Module(new RegFile)
    val csr_file = Module(new ControlStatusRegisters)
    val trap_ctrl = Module(new TrapController)

    // Commit and Control
    val commit_unit = Module(new CommitUnit)

    // Module Connections
    // ==================
    // AXI Bus Interface
    axi_ctrl.io.instr_fetch.read.get.params.bits.size := (log2Ceil(config.axi_config.addr_width)-3).U
    axi_ctrl.io.instr_fetch.read.get.params.valid     <> instr_cache.io.out_read_req.valid
    axi_ctrl.io.instr_fetch.read.get.params.ready     <> instr_cache.io.out_read_req.ready
    axi_ctrl.io.instr_fetch.read.get.params.bits.addr <> instr_cache.io.out_read_req.bits
    axi_ctrl.io.instr_fetch.read.get.resp.valid       <> instr_cache.io.out_read_data.valid
    axi_ctrl.io.instr_fetch.read.get.resp.ready       <> instr_cache.io.out_read_data.ready
    axi_ctrl.io.instr_fetch.read.get.resp.bits.data   <> instr_cache.io.out_read_data.bits

    load_store_unit.io.interface <> axi_ctrl.io.load_store
    io.axi <> axi_ctrl.io.axi

    // Cache
    instr_cache.io.invalidate := false.B

    // Exception & Flush Control
    val flush = trap_ctrl.io.flush | branch_unit.io.flush
    trap_ctrl.io.outer_int := false.B
    
    trap_ctrl.io.pc <> instr_fetch_unit.io.get_pc
    trap_ctrl.io.privilege <> misc_unit.io.get_privilege
    trap_ctrl.io.fetched <> instr_fetch_unit.io.get_fetched
    trap_ctrl.io.set_trap <> csr_file.io.set_trap
    trap_ctrl.io.trap_ret_info <> csr_file.io.trap_ret_info
    trap_ctrl.io.mstatus <> csr_file.io.mstatus
    trap_ctrl.io.mie <> csr_file.io.mie

    misc_unit.io.trap_ret <> csr_file.io.trap_ret

    // Frontend Pipeline Connections
    instr_prefetch.io.flush := flush
    instr_prefetch.io.read_addr <> instr_cache.io.in_read_req
    instr_prefetch.io.read_data <> instr_cache.io.in_read_data
    instr_prefetch.io.transaction_addr <> instr_cache.io.transaction_addr

    instr_fetch_queue.io.flush := flush
    instr_fetch_queue.io.fetch_pc <> instr_prefetch.io.fetch_pc
    instr_fetch_queue.io.cacheline_read <> instr_prefetch.io.fetched
    instr_fetch_queue.io.pc <> instr_fetch_unit.io.get_pc
    instr_fetch_queue.io.reg_read <> register_file.io.read_addrs(2)
    instr_fetch_queue.io.reg_data <> register_file.io.read_datas(2)

    instr_fetch_unit.io.flush := flush
    instr_fetch_unit.io.invalid_drop <> instr_decoder.io.invalid_drop
    instr_fetch_unit.io.fetch_bundle <> instr_fetch_queue.io.fetch_bundle
    instr_fetch_unit.io.fetch_hlt <> trap_ctrl.io.fetch_hlt
    instr_fetch_unit.io.set_pc := MuxCase(0.U, Seq(
        trap_ctrl.io.flush -> trap_ctrl.io.set_pc,
        branch_unit.io.flush -> branch_unit.io.set_pc
    ))

    // Register File Interface
    instr_issuer.io.reg_read1 <> register_file.io.read_addrs(0)
    instr_issuer.io.reg_read2 <> register_file.io.read_addrs(1)
    instr_issuer.io.reg_data1 <> register_file.io.read_datas(0)
    instr_issuer.io.reg_data2 <> register_file.io.read_datas(1)
    instr_issuer.io.occupied_regs <> register_file.io.get_occupied
    instr_issuer.io.acquire_reg <> register_file.io.acquire_reg
    instr_issuer.io.acquired <> register_file.io.acquired
    register_file.io.flush := flush

    // Main Pipeline Stages
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

    // Execution Unit Dispatch
    PipelineConnect(instr_issuer.io.alu_out, arithmetic_logic_unit.io.alu_instr, arithmetic_logic_unit.io.outfire, flush)
    PipelineConnect(instr_issuer.io.lsu_out, load_store_unit.io.lsu_instr, load_store_unit.io.outfire, flush)
    PipelineConnect(instr_issuer.io.misc_out, misc_unit.io.misc_instr, misc_unit.io.outfire, flush)
    PipelineConnect(instr_issuer.io.mu_out, multiply_unit.io.mu_instr, multiply_unit.io.outfire, flush)
    PipelineConnect(instr_issuer.io.branch_out, branch_unit.io.branch_instr, branch_unit.io.outfire, flush)

    // Execution Unit Status
    instr_fetch_unit.io.exu_outfires := VecInit(Seq(
        arithmetic_logic_unit.io.outfire,
        load_store_unit.io.outfire,
        misc_unit.io.outfire,
        multiply_unit.io.outfire,
        branch_unit.io.outfire
    ))

    // Commit Stage
    Seq(
        (load_store_unit.io.register_commit, 0),
        (arithmetic_logic_unit.io.register_commit, 1),
        (misc_unit.io.register_commit, 2),
        (multiply_unit.io.register_commit, 3),
        (branch_unit.io.register_commit, 4)
    ).foreach { case (unit, idx) =>
        // No flush here, as flushing would corrupt the `jalr`.
        PipelineConnect(unit, commit_unit.io.register_commits(idx), true.B, false.B)
    }

    commit_unit.io.reg_write <> register_file.io.write_addr
    commit_unit.io.write_data <> register_file.io.write_data

    // CSR and Privilege
    csr_file.io.instret <> commit_unit.io.instret
    csr_file.io.time <> io.time
    csr_file.io.csrio <> misc_unit.io.csrio
    csr_file.io.privilege <> misc_unit.io.get_privilege

    misc_unit.io.set_privilege <> trap_ctrl.io.set_privilege
    misc_unit.io.trap_ret <> trap_ctrl.io.trap_ret
    misc_unit.io.exception <> trap_ctrl.io.exceptions(0)

    // AMO
    load_store_unit.io.invalidate_reserved := misc_unit.io.trap_ret

    // Debug Outputs
    io.pc := instr_fetch_unit.io.instr_bundle.bits.pc
    io.instr_now := Mux(instr_fetch_unit.io.instr_bundle.valid,instr_fetch_unit.io.instr_bundle.bits.instr,0.U)
}

object MarkoRvCore extends App {
    ChiselStage.emitSystemVerilogFile(
        new MarkoRvCore,
        Array("--target-dir", "generated"),
        Array("-disable-all-randomization", "--strip-debug-info")
    )
}