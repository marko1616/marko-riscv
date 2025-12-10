package markorv

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.frontend._
import markorv.backend._
import markorv.bus._
import markorv.cache._
import markorv.manage._

class MarkoRvCore(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val axi = new AxiInterface(c.axiConfig)

        val meip = Input(Bool())
        val mtip = Input(Bool())
        val msip = Input(Bool())

        val dcacheCleanReq = if(c.simulate) Some(Flipped(Decoupled(new CacheCleanReq))) else None
        val dcacheCleanResp = if(c.simulate) Some(Output(Bool())) else None
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
    val exceptionUnit = Module(new ExceptionUnit)

    // Module Connections
    // ==================
    // AXI Bus Interface
    // TODO: Handle AXI error
    axiCtrl.io.instrFetch <> iCache.io.ioInterface
    axiCtrl.io.dcacheLoadStore <> dCache.io.ioInterface

    lsu.io.dirLoadStore <> axiCtrl.io.dirLoadStore
    io.axi <> axiCtrl.io.axi

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
    // TODO Zicbom
    if (c.simulate) {
        dCache.io.cacheInterface.cleanReq <> io.dcacheCleanReq.get
        dCache.io.cacheInterface.cleanResp <> io.dcacheCleanResp.get
    } else {
        dCache.io.cacheInterface.cleanReq.valid := false.B
        dCache.io.cacheInterface.cleanReq.bits.addr := 0.U
    }

    // Exception & Flush Control
    val flush = exceptionUnit.io.flush | rob.io.flush

    exceptionUnit.io.pc <> ifu.io.getPc
    exceptionUnit.io.privilege <> misc.io.getPrivilege
    exceptionUnit.io.setException <> csrFile.io.setException
    exceptionUnit.io.exceptionRetInfo <> csrFile.io.exceptionRetInfo
    exceptionUnit.io.mstatus <> csrFile.io.mstatus
    exceptionUnit.io.mie <> csrFile.io.mie
    exceptionUnit.io.robEmpty <> rob.io.empty
    exceptionUnit.io.meip <> io.meip
    exceptionUnit.io.mtip <> io.mtip
    exceptionUnit.io.msip <> io.msip

    rob.io.exceptionRet <> csrFile.io.exceptionRet

    // Frontend Pipeline Connections
    ipu.io.flush := flush
    ipu.io.cacheInterface <> iCache.io.cacheInterface
    ipu.io.transactionAddr <> iCache.io.transactionAddr

    ifq.io.flush := flush
    ifq.io.fetchPc <> ipu.io.fetchPc
    ifq.io.cachelineRead <> ipu.io.fetched
    ifq.io.pc <> ifu.io.getPc

    ifu.io.flush := flush
    ifu.io.invalidDrop <> decoder.io.invalidDrop
    ifu.io.fetchBundle <> ifq.io.fetchBundle
    ifu.io.flushPc := MuxCase(0.U, Seq(
        exceptionUnit.io.flush -> exceptionUnit.io.flushPc,
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
    issuer.io.interruptHlt <> exceptionUnit.io.interruptHlt

    // Reservation Station Interface
    reservStation.io.robHeadIndex <> rob.io.headIndex
    reservStation.io.rsReq <> issuer.io.rsReq
    reservStation.io.flush <> flush
    reservStation.io.regStates <> regFile.io.getStates

    // Main Pipeline Stages
    PipelineConnect(
        ifu.io.instrBundle,
        decoder.io.instrBundle,
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

    rob.io.trap <> exceptionUnit.io.trap
    rob.io.exceptionRet <> exceptionUnit.io.exceptionRet
    misc.io.setPrivilege <> exceptionUnit.io.setPrivilege

    // AMO
    lsu.io.invalidateReserved := rob.io.exceptionRet
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