package markorv

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._
import _root_.circt.stage.ChiselStage

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.frontend._
import markorv.backend._
import markorv.bus._
import markorv.cache._
import markorv.manage._
import markorv.trap._
import markorv.utils._

class IssueEventDPI extends DPIClockedVoidFunctionImport {
    val functionName = "fire_issue_event"
    override val inputNames = Some(Seq("entry"))
}

class CommitEventDPI extends DPIClockedVoidFunctionImport {
    val functionName = "fire_commit_event"
    override val inputNames = Some(Seq("entry"))
}

class DisconEventDPI extends DPIClockedVoidFunctionImport {
    val functionName = "fire_discon_event"
    override val inputNames = Some(Seq("entry"))
}

class RetireEventDPI extends DPIClockedVoidFunctionImport {
    val functionName = "fire_retire_event"
    override val inputNames = Some(Seq("entry"))
}

class MarkoRvCore(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val axi = new AxiInterface(c.axiConfig)

        val meip = Input(Bool())
        val mtip = Input(Bool())
        val msip = Input(Bool())

        val time = Input(UInt(64.W))

        val dcacheCleanAllReq = if(c.simulate) Some(Input(Bool())) else None
        val dcacheCleanAllResp = if(c.simulate) Some(Output(Bool())) else None
    })

    // Submodule Instantiations
    // ========================
    // Bus Controllers
    val axiCtrl = Module(new AxiCtrl)

    // Cache
    val iCache = Module(new InstrCache()(c.icacheConfig))
    val dCache = Module(new DataCache()(c.icacheConfig))

    // Frontend Pipeline
    val ipu = Module(new InstrPrefetchUnit)
    val ifq = Module(new InstrFetchQueue)
    val ifu = Module(new InstrFetchUnit)
    val decoder = Module(new InstrDecoder)

    // Dataflow schedule pipeline & Register file
    val regFile = Module(new RegFile)
    val issuer = Module(new Issuer)
    val reservStation = Module(new ReservationStation)
    val commitUnit = Module(new CommitUnit)
    val renameTable = Module(new RenameTable)
    val rob = Module(new ReorderBuffer)
    val regStateCtrl = Module(new RegStateController)

    // Execution Units
    val alu = Module(new ArithmeticLogicUnit)
    val lsu = Module(new LoadStoreUnit)
    val bru = Module(new BranchUnit)
    val misc = Module(new MISCUnit)
    val mdu = Module(new MultiplyDivisionUnit)

    // CSR & Trap controller.
    val csrFile = Module(new ControlStatusRegisters)
    val trapUnit = Module(new TrapUnit)

    // Module Connections
    // ==================
    // AXI Bus Interface
    // TODO: Handle AXI error
    axiCtrl.io.instrFetch <> iCache.io.ioInterface
    axiCtrl.io.dcacheLoadStore <> dCache.io.ioInterface

    lsu.io.dirLoadStore <> axiCtrl.io.dirLoadStore
    io.axi <> axiCtrl.io.axi

    // Exception & Flush Control
    val flush = trapUnit.io.flush | rob.io.flush

    // Cache
    iCache.io.invalidateAll <> misc.io.icacheInvalidateAll
    iCache.io.invalidateAllOutfire <> misc.io.icacheInvalidateAllOutfire
    dCache.io.cleanAll <> misc.io.dcacheCleanAll
    dCache.io.cleanAllOutfire <> misc.io.dcacheCleanAllOutfire
    // TODO Dcache invalidation
    dCache.io.invalidateAll := false.B
    dCache.io.cacheInterface.readReq <> lsu.io.cacheReadReq
    dCache.io.cacheInterface.readResp <> lsu.io.cacheReadResp
    dCache.io.cacheInterface.writeReq <> lsu.io.cacheWriteReq
    dCache.io.cacheInterface.writeResp <> lsu.io.cacheWriteResp
    dCache.io.cacheInterface.cleanReq <> lsu.io.cacheCleanReq
    dCache.io.cacheInterface.cleanResp <> lsu.io.cacheCleanResp
    dCache.io.cacheInterface.invalidateReq <> lsu.io.cacheInvalidateReq
    dCache.io.cacheInterface.invalidateResp <> lsu.io.cacheInvalidateResp
    // TODO Zicbom
    if(c.simulate) {
        dCache.io.cleanAll <> (io.dcacheCleanAllReq.get || misc.io.dcacheCleanAll)
        dCache.io.cleanAllOutfire <> io.dcacheCleanAllResp.get
    }

    trapUnit.io.pc <> ifu.io.pc
    trapUnit.io.privilege <> misc.io.getPrivilege
    trapUnit.io.handleTrap <> csrFile.io.handleTrap
    trapUnit.io.trapRetInfo <> csrFile.io.trapRetInfo
    trapUnit.io.mstatus <> csrFile.io.mstatus
    trapUnit.io.mie <> csrFile.io.mie
    trapUnit.io.sie <> csrFile.io.sie
    trapUnit.io.medeleg <> csrFile.io.medeleg
    trapUnit.io.mideleg <> csrFile.io.mideleg
    trapUnit.io.interruptXepc <> rob.io.interruptXepc
    trapUnit.io.interruptHlt <> rob.io.interruptHlt
    trapUnit.io.meip <> io.meip
    trapUnit.io.mtip <> io.mtip
    trapUnit.io.msip <> io.msip
    trapUnit.io.seip <> csrFile.io.seip
    trapUnit.io.stip <> csrFile.io.stip
    trapUnit.io.ssip <> csrFile.io.ssip
    
    rob.io.trapRet <> csrFile.io.trapRet

    // Frontend Pipeline Connections
    ipu.io.flush := flush
    ipu.io.cacheInterface <> iCache.io.cacheInterface

    ifq.io.flush := flush
    ifq.io.cachelineReadReq <> ipu.io.fetchPc
    ifq.io.cachelineReadResp <> ipu.io.fetched
    ifq.io.pc <> ifu.io.pc

    ifu.io.flush := flush
    ifu.io.fetchBundle <> ifq.io.fetchBundle
    ifu.io.flushPc := MuxCase(0.U, Seq(
        trapUnit.io.flush -> trapUnit.io.flushPc,
        rob.io.flush -> rob.io.flushPc
    ))

    // Rename Table Interface
    renameTable.io.createCkpt <> issuer.io.createRtCkpt
    renameTable.io.restoreIndex <> rob.io.rtRestoreIndex
    renameTable.io.rmLastCkpt <> rob.io.rtRmLastCkpt
    renameTable.io.readIndices(1) := 0.U

    // Register File Interface
    regStateCtrl.io.renameTableReadIndex <> renameTable.io.readIndices(0)
    regStateCtrl.io.renameTableReadEntry <> renameTable.io.readEntries(0)
    regStateCtrl.io.issueEvent   <> issuer.io.issueEvent
    regStateCtrl.io.commitEvents <> commitUnit.io.commitEvents
    regStateCtrl.io.retireEvent  <> rob.io.retireEvent
    regStateCtrl.io.disconEvent  <> rob.io.disconEvent

    regFile.io.setStates <> regStateCtrl.io.setStates
    regFile.io.getStates <> regStateCtrl.io.getStates

    reservStation.io.regRead1 <> regFile.io.readAddrs(0)
    reservStation.io.regRead2 <> regFile.io.readAddrs(1)
    reservStation.io.regData1 <> regFile.io.readDatas(0)
    reservStation.io.regData2 <> regFile.io.readDatas(1)

    // Issuer Interface
    issuer.io.rsRegReqBits <> reservStation.io.rsRegReqBits
    issuer.io.robMayDison <> rob.io.robMayDison
    issuer.io.robReq <> rob.io.allocReq
    issuer.io.robResp <> rob.io.allocResp
    issuer.io.robFull <> rob.io.full
    issuer.io.regStates <> regFile.io.getStates
    issuer.io.renameTailIndex <> renameTable.io.tailIndex
    issuer.io.renameTable <> renameTable.io.tailEntry
    issuer.io.interruptHlt <> trapUnit.io.interruptHlt

    // Reservation Station Interface
    reservStation.io.robHeadIndex <> rob.io.headIndex
    reservStation.io.rsReq <> issuer.io.rsReq
    reservStation.io.flush <> flush
    reservStation.io.regStates <> regFile.io.getStates

    // Main Pipeline Stages
    PipelineConnect(
        ifu.io.decodeTask,
        decoder.io.decodeTask,
        decoder.io.outfire,
        flush
    )

    PipelineConnect(
        decoder.io.issueTask,
        issuer.io.issueTask,
        issuer.io.outfire,
        flush
    )

    // Execution Unit Dispatch
    PipelineConnect(reservStation.io.aluOut, alu.io.aluInstr, alu.io.outfire, flush)
    PipelineConnect(reservStation.io.bruOut, bru.io.branchInstr, bru.io.outfire, flush)
    PipelineConnect(reservStation.io.lsuOut, lsu.io.lsuInstr, lsu.io.outfire, flush)
    PipelineConnect(reservStation.io.mduOut, mdu.io.muInstr, mdu.io.outfire, flush)
    PipelineConnect(reservStation.io.miscOut, misc.io.miscInstr, misc.io.outfire, flush)

    mdu.io.flush <> flush

    // Commit Stage
    PipelineConnect(alu.io.commit, commitUnit.io.alu, commitUnit.io.outfires(0), flush)
    PipelineConnect(bru.io.commit, commitUnit.io.bru, commitUnit.io.outfires(1), flush)
    PipelineConnect(lsu.io.commit, commitUnit.io.lsu, commitUnit.io.outfires(2), flush)
    PipelineConnect(mdu.io.commit, commitUnit.io.mdu, commitUnit.io.outfires(3), flush)
    PipelineConnect(misc.io.commit, commitUnit.io.misc, commitUnit.io.outfires(4), flush)

    commitUnit.io.robReadIndices  <> rob.io.readIndices
    commitUnit.io.robReadEntries <> rob.io.readEntries
    commitUnit.io.regWrites  <> regFile.io.writePorts
    commitUnit.io.robCommits <> rob.io.commits

    // CSR and Privilege
    csrFile.io.retireEvent := rob.io.retireEvent
    csrFile.io.csrio <> misc.io.csrio
    csrFile.io.privilege <> misc.io.getPrivilege
    csrFile.io.mepc <> misc.io.mepc
    csrFile.io.sepc <> misc.io.sepc

    csrFile.io.meip <> io.meip
    csrFile.io.mtip <> io.mtip
    csrFile.io.msip <> io.msip

    csrFile.io.time <> io.time

    rob.io.exception <> trapUnit.io.exception
    rob.io.trapRet <> trapUnit.io.trapRet
    misc.io.setPrivilege <> trapUnit.io.setPrivilege

    // AMO
    lsu.io.invalidateReserved <> rob.io.trapRet.valid

    if (c.simulate) {
        val issueEventDPI = new IssueEventDPI
        when(issuer.io.issueEvent.valid) {
            issueEventDPI.call(
                issuer.io.issueEvent.bits
            )
        }

        for (commitEvent <- commitUnit.io.commitEvents) {
            val commitEventDPI = new CommitEventDPI
            when(commitEvent.valid) {
                commitEventDPI.call(commitEvent.bits)
            }
        }

        val disconEventDPI = new DisconEventDPI
        when(rob.io.disconEvent.valid) {
            disconEventDPI.call(rob.io.disconEvent.bits)
        }

        val retireEventDPI = new RetireEventDPI
        when(rob.io.retireEvent.valid) {
            retireEventDPI.call(rob.io.retireEvent.bits)
        }
    }
}

object Main extends App {
    val configPath = if (args.nonEmpty) args(0) else "../assets/core_config.yaml"
    ConfigLoader.loadCoreConfigFromFile(configPath) match {
        case Right(config) =>
            ChiselStage.emitSystemVerilogFile(
                new MarkoRvCore()(using config),
                Array("--target-dir", "generated"),
                Array("-disable-all-randomization", "--strip-debug-info", "-enable-layers=Verification")
            )
            println(s"Config loaded: $config")
        case Left(error) =>
            println(s"Config loading error: $error")
    }
}