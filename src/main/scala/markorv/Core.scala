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
        val axi = new AxiInterface(config.axiConfig)
        val time = Input(UInt(64.W))
        val pc = Output(UInt(64.W))
        val instrNow = Output(UInt(64.W))
    })

    // Submodule Instantiations
    // ========================
    // Bus Controllers
    val axiCtrl = Module(new AxiCtrl)

    // Cache
    val instrCache = Module(new InstrCache()(config.icacheConfig))

    // Frontend Pipeline
    val instrPrefetchUnit = Module(new InstrPrefetchUnit)
    val instrFetchQueue = Module(new InstrFetchQueue)
    val instrFetchUnit = Module(new InstrFetchUnit)
    val instrDecoder = Module(new InstrDecoder)
    val instrIssuer = Module(new InstrIssueUnit)

    // Execution Units
    val arithmeticLogicUnit = Module(new ArithmeticLogicUnit)
    val loadStoreUnit = Module(new LoadStoreUnit)
    val branchUnit = Module(new BranchUnit)
    val miscUnit = Module(new MiscUnit)
    val multiplyUnit = Module(new MultiplyUnit)

    // Register Files and State
    val registerFile = Module(new RegFile)
    val csrFile = Module(new ControlStatusRegisters)
    val trapCtrl = Module(new TrapController)

    // Commit and Control
    val commitUnit = Module(new CommitUnit)

    // Module Connections
    // ==================
    // AXI Bus Interface
    axiCtrl.io.instrFetch.read.get.params.bits.size := (log2Ceil(config.axiConfig.addrWidth)-3).U
    axiCtrl.io.instrFetch.read.get.params.valid     <> instrCache.io.outReadReq.valid
    axiCtrl.io.instrFetch.read.get.params.ready     <> instrCache.io.outReadReq.ready
    axiCtrl.io.instrFetch.read.get.params.bits.addr <> instrCache.io.outReadReq.bits
    axiCtrl.io.instrFetch.read.get.resp.valid       <> instrCache.io.outReadData.valid
    axiCtrl.io.instrFetch.read.get.resp.ready       <> instrCache.io.outReadData.ready
    axiCtrl.io.instrFetch.read.get.resp.bits.data   <> instrCache.io.outReadData.bits

    loadStoreUnit.io.interface <> axiCtrl.io.loadStore
    io.axi <> axiCtrl.io.axi

    // Cache
    instrCache.io.invalidate <> miscUnit.io.icacheInvalidate
    instrCache.io.invalidateOutfire <> miscUnit.io.icacheInvalidateOutfire

    // Exception & Flush Control
    val flush = trapCtrl.io.flush | branchUnit.io.flush | miscUnit.io.flush
    trapCtrl.io.outerInt := false.B

    trapCtrl.io.pc <> instrFetchUnit.io.getPc
    trapCtrl.io.privilege <> miscUnit.io.getPrivilege
    trapCtrl.io.fetched <> instrFetchUnit.io.getFetched
    trapCtrl.io.setTrap <> csrFile.io.setTrap
    trapCtrl.io.trapRetInfo <> csrFile.io.trapRetInfo
    trapCtrl.io.mstatus <> csrFile.io.mstatus
    trapCtrl.io.mie <> csrFile.io.mie

    miscUnit.io.trapRet <> csrFile.io.trapRet

    // Frontend Pipeline Connections
    instrPrefetchUnit.io.flush := flush
    instrPrefetchUnit.io.readAddr <> instrCache.io.inReadReq
    instrPrefetchUnit.io.readData <> instrCache.io.inReadData
    instrPrefetchUnit.io.transactionAddr <> instrCache.io.transactionAddr

    instrFetchQueue.io.flush := flush
    instrFetchQueue.io.fetchPc <> instrPrefetchUnit.io.fetchPc
    instrFetchQueue.io.cachelineRead <> instrPrefetchUnit.io.fetched
    instrFetchQueue.io.pc <> instrFetchUnit.io.getPc
    instrFetchQueue.io.regRead <> registerFile.io.readAddrs(2)
    instrFetchQueue.io.regData <> registerFile.io.readDatas(2)

    instrFetchUnit.io.flush := flush
    instrFetchUnit.io.invalidDrop <> instrDecoder.io.invalidDrop
    instrFetchUnit.io.fetchBundle <> instrFetchQueue.io.fetchBundle
    instrFetchUnit.io.fetchHlt <> trapCtrl.io.fetchHlt
    instrFetchUnit.io.setPc := MuxCase(0.U, Seq(
        trapCtrl.io.flush -> trapCtrl.io.setPc,
        branchUnit.io.flush -> branchUnit.io.setPc,
        miscUnit.io.flush -> miscUnit.io.setPc
    ))

    // Register File Interface
    instrIssuer.io.regRead1 <> registerFile.io.readAddrs(0)
    instrIssuer.io.regRead2 <> registerFile.io.readAddrs(1)
    instrIssuer.io.regData1 <> registerFile.io.readDatas(0)
    instrIssuer.io.regData2 <> registerFile.io.readDatas(1)
    instrIssuer.io.occupiedRegs <> registerFile.io.getOccupied
    instrIssuer.io.acquireReg <> registerFile.io.acquireReg
    instrIssuer.io.acquired <> registerFile.io.acquired
    registerFile.io.flush := flush

    // Main Pipeline Stages
    PipelineConnect(
        instrFetchUnit.io.instrBundle,
        instrDecoder.io.instrBundle,
        instrDecoder.io.outfire,
        flush
    )
    PipelineConnect(
        instrDecoder.io.issueTask,
        instrIssuer.io.issueTask,
        instrIssuer.io.outfire,
        flush
    )

    // Execution Unit Dispatch
    PipelineConnect(instrIssuer.io.aluOut, arithmeticLogicUnit.io.aluInstr, arithmeticLogicUnit.io.outfire, flush)
    PipelineConnect(instrIssuer.io.lsuOut, loadStoreUnit.io.lsuInstr, loadStoreUnit.io.outfire, flush)
    PipelineConnect(instrIssuer.io.miscOut, miscUnit.io.miscInstr, miscUnit.io.outfire, flush)
    PipelineConnect(instrIssuer.io.muOut, multiplyUnit.io.muInstr, multiplyUnit.io.outfire, flush)
    PipelineConnect(instrIssuer.io.branchOut, branchUnit.io.branchInstr, branchUnit.io.outfire, flush)

    // Execution Unit Status
    instrFetchUnit.io.exuOutfires := VecInit(Seq(
        arithmeticLogicUnit.io.outfire,
        loadStoreUnit.io.outfire,
        miscUnit.io.outfire,
        multiplyUnit.io.outfire,
        branchUnit.io.outfire
    ))

    // Commit Stage
    Seq(
        (loadStoreUnit.io.commit, 0),
        (arithmeticLogicUnit.io.commit, 1),
        (miscUnit.io.commit, 2),
        (multiplyUnit.io.commit, 3),
        (branchUnit.io.commit, 4)
    ).foreach { case (unit, idx) =>
        // No flush here, as flushing would corrupt the `jalr`.
        PipelineConnect(unit, commitUnit.io.registerCommits(idx), true.B, false.B)
    }

    commitUnit.io.regWrite <> registerFile.io.writeAddr
    commitUnit.io.writeData <> registerFile.io.writeData

    // CSR and Privilege
    csrFile.io.instret <> commitUnit.io.instret
    csrFile.io.time <> io.time
    csrFile.io.csrio <> miscUnit.io.csrio
    csrFile.io.privilege <> miscUnit.io.getPrivilege

    miscUnit.io.setPrivilege <> trapCtrl.io.setPrivilege
    miscUnit.io.trapRet <> trapCtrl.io.trapRet
    miscUnit.io.exception <> trapCtrl.io.exceptions(0)

    // AMO
    loadStoreUnit.io.invalidateReserved := miscUnit.io.trapRet

    // Debug Outputs
    io.pc := instrFetchUnit.io.instrBundle.bits.pc
    io.instrNow := Mux(instrFetchUnit.io.instrBundle.valid,instrFetchUnit.io.instrBundle.bits.instr.rawBits,0.U)
}

object MarkoRvCore extends App {
    ChiselStage.emitSystemVerilogFile(
        new MarkoRvCore,
        Array("--target-dir", "generated"),
        Array("-disable-all-randomization", "--strip-debug-info")
    )
}