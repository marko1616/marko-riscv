package markorv

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import markorv._
import markorv.frontend._
import markorv.backend._
import markorv.bus._

class MarkoRvCore extends Module {
    val io = IO(new Bundle {
        val axi = new AxiLiteMasterIO(64, 64)
        val time = Input(UInt(64.W))

        val pc = Output(UInt(64.W))
        val instr_now = Output(UInt(64.W))
        val peek = Output(UInt(64.W))
    })

    val axi_ctrl = Module(new AXICtrl(64, 64))

    val instr_fetch_queue = Module(new InstrFetchQueue)
    val instr_fetch_unit = Module(new InstrFetchUnit)
    val instr_decoder = Module(new InstrDecoder)
    val instr_issuer = Module(new InstrIssueUnit)

    val load_store_unit = Module(new LoadStoreUnit)
    val arithmetic_logic_unit = Module(new ArithmeticLogicUnit)
    val misc_unit = Module(new MiscUnit)
    val multiply_unit = Module(new MultiplyUnit)
    val branch_unit = Module(new BranchUnit)

    val register_file = Module(new RegFile)
    val csr_file = Module(new ControlStatusRegisters)
    val trap_ctrl = Module(new TrapController)

    val commit_unit = Module(new CommitUnit)
    val lsu_iocontroller = Module(new LoadStoreController)
    val ifq_iocontroller = Module(new LoadStoreController)

    // AXI bus
    load_store_unit.io.read_req <> lsu_iocontroller.io.read_req
    load_store_unit.io.read_data <> lsu_iocontroller.io.read_data
    load_store_unit.io.write_req <> lsu_iocontroller.io.write_req
    load_store_unit.io.write_outfire <> lsu_iocontroller.io.write_outfire

    instr_fetch_queue.io.read_req <> ifq_iocontroller.io.read_req
    instr_fetch_queue.io.read_data <> ifq_iocontroller.io.read_data
    ifq_iocontroller.io.write_req.valid := 0.U
    ifq_iocontroller.io.write_req.bits.size := 0.U
    ifq_iocontroller.io.write_req.bits.addr := 0.U
    ifq_iocontroller.io.write_req.bits.data := 0.U
    ifq_iocontroller.io.write_req.bits.direct := false.B

    lsu_iocontroller.io.io_bus <> axi_ctrl.io.ports(1)
    ifq_iocontroller.io.io_bus <> axi_ctrl.io.ports(0)

    // Exception & flush control.
    // Impossible flush at same cycle.
    val flush = trap_ctrl.io.flush | branch_unit.io.flush
    trap_ctrl.io.outer_int := false.B

    trap_ctrl.io.pc <> instr_fetch_unit.io.get_pc
    trap_ctrl.io.privilege <> misc_unit.io.get_privilege
    trap_ctrl.io.fetched <> instr_fetch_unit.io.get_fetched
    trap_ctrl.io.set_trap <> csr_file.io.set_trap
    trap_ctrl.io.trap_ret_info <> csr_file.io.trap_ret_info
    trap_ctrl.io.mstatus <> csr_file.io.mstatus
    trap_ctrl.io.mie <> csr_file.io.mie
    misc_unit.io.set_privilege <> trap_ctrl.io.set_privilege
    misc_unit.io.trap_ret <> trap_ctrl.io.trap_ret
    misc_unit.io.trap_ret <> csr_file.io.trap_ret
    misc_unit.io.exception <> trap_ctrl.io.exceptions(0)

    instr_fetch_queue.io.flush <> flush
    instr_fetch_queue.io.pc <> instr_fetch_unit.io.get_pc
    instr_fetch_queue.io.reg_read <> register_file.io.read_addrs(2)
    instr_fetch_queue.io.reg_data <> register_file.io.read_datas(2)

    instr_fetch_unit.io.invalid_drop <> instr_decoder.io.invalid_drop
    instr_fetch_unit.io.fetch_bundle <> instr_fetch_queue.io.fetch_bundle
    instr_fetch_unit.io.fetch_hlt <> trap_ctrl.io.fetch_hlt
    instr_fetch_unit.io.flush <> flush
    instr_fetch_unit.io.set_pc := 0.U
    when(trap_ctrl.io.flush) {
        instr_fetch_unit.io.set_pc := trap_ctrl.io.set_pc
    }
    when(branch_unit.io.flush) {
        instr_fetch_unit.io.set_pc := branch_unit.io.set_pc
    }

    io.axi <> axi_ctrl.io.outer

    instr_issuer.io.reg_read1 <> register_file.io.read_addrs(0)
    instr_issuer.io.reg_read2 <> register_file.io.read_addrs(1)
    instr_issuer.io.reg_data1 <> register_file.io.read_datas(0)
    instr_issuer.io.reg_data2 <> register_file.io.read_datas(1)
    instr_issuer.io.occupied_regs <> register_file.io.get_occupied
    instr_issuer.io.acquire_reg <> register_file.io.acquire_reg
    instr_issuer.io.acquired <> register_file.io.acquired

    load_store_unit.io.local_load_reserved.ready := true.B
    load_store_unit.io.invalidate_reserved := misc_unit.io.trap_ret
    commit_unit.io.reg_write <> register_file.io.write_addr
    commit_unit.io.write_data <> register_file.io.write_data

    csr_file.io.instret <> commit_unit.io.instret
    csr_file.io.time <> io.time
    csr_file.io.csrio <> misc_unit.io.csrio
    csr_file.io.privilege <> misc_unit.io.get_privilege
    register_file.io.flush := flush

    io.pc <> instr_fetch_unit.io.instr_bundle.bits.pc
    when(instr_fetch_unit.io.instr_bundle.valid) {
        io.instr_now := instr_fetch_unit.io.instr_bundle.bits.instr
    }.otherwise{
        io.instr_now := 0.U
    }

    register_file.io.read_addrs(3) := 14.U
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
    instr_fetch_unit.io.exu_outfires(3) := multiply_unit.io.outfire
    instr_fetch_unit.io.exu_outfires(4) := branch_unit.io.outfire
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
        instr_issuer.io.mu_out,
        multiply_unit.io.mu_instr,
        multiply_unit.io.outfire,
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
        load_store_unit.io.register_commit,
        commit_unit.io.register_commits(0),
        true.B,
        flush
    )
    PipelineConnect(
        arithmetic_logic_unit.io.register_commit,
        commit_unit.io.register_commits(1),
        true.B,
        flush
    )
    PipelineConnect(
        misc_unit.io.register_commit,
        commit_unit.io.register_commits(2),
        true.B,
        flush
    )
    PipelineConnect(
        multiply_unit.io.register_commit,
        commit_unit.io.register_commits(3),
        true.B,
        flush
    )
    PipelineConnect(
        branch_unit.io.register_commit,
        commit_unit.io.register_commits(4),
        true.B,
        flush & ~branch_unit.io.register_commit.valid
    )
}

object MarkoRvCore extends App {
    ChiselStage.emitSystemVerilogFile(
        new MarkoRvCore,
        Array("--target-dir", "generated"),
        Array("-disable-all-randomization", "--strip-debug-info")
    )
}
