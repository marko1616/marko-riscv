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
        val time = Input(UInt(64.W))
        val pc = Output(UInt(64.W))
        val instrNow = Output(UInt(64.W))
    })

    // Submodule Instantiations
    // ========================
    // Bus Controllers
    val axiCtrl = Module(new AxiCtrl)

    // Cache
    val iCache = Module(new InstrCache()(c.icacheConfig))

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
    axiCtrl.io.instrFetch.read.get.params.bits.size := (log2Ceil(c.axiConfig.addrWidth)-3).U
    axiCtrl.io.instrFetch.read.get.params.valid     <> iCache.io.outReadReq.valid
    axiCtrl.io.instrFetch.read.get.params.ready     <> iCache.io.outReadReq.ready
    axiCtrl.io.instrFetch.read.get.params.bits.addr <> iCache.io.outReadReq.bits
    axiCtrl.io.instrFetch.read.get.resp.valid       <> iCache.io.outReadData.valid
    axiCtrl.io.instrFetch.read.get.resp.ready       <> iCache.io.outReadData.ready
    axiCtrl.io.instrFetch.read.get.resp.bits.data   <> iCache.io.outReadData.bits

    lsu.io.interface <> axiCtrl.io.loadStore
    io.axi <> axiCtrl.io.axi

    // Cache
    iCache.io.invalidate <> misc.io.icacheInvalidate
    iCache.io.invalidateOutfire <> misc.io.icacheInvalidateOutfire

    // Exception & Flush Control
    val flush = exceptionUnit.io.flush | rob.io.flush
    exceptionUnit.io.outerInt := false.B

    exceptionUnit.io.pc <> ifu.io.getPc
    exceptionUnit.io.privilege <> misc.io.getPrivilege
    exceptionUnit.io.setException <> csrFile.io.setException
    exceptionUnit.io.exceptionRetInfo <> csrFile.io.exceptionRetInfo
    exceptionUnit.io.mstatus <> csrFile.io.mstatus
    exceptionUnit.io.mie <> csrFile.io.mie

    rob.io.exceptionRet <> csrFile.io.exceptionRet

    // Frontend Pipeline Connections
    ipu.io.flush := flush
    ipu.io.readAddr <> iCache.io.inReadReq
    ipu.io.readData <> iCache.io.inReadData
    ipu.io.transactionAddr <> iCache.io.transactionAddr

    ifq.io.flush := flush
    ifq.io.fetchPc <> ipu.io.fetchPc
    ifq.io.cachelineRead <> ipu.io.fetched
    ifq.io.pc <> ifu.io.getPc

    ifu.io.flush := flush
    ifu.io.invalidDrop <> decoder.io.invalidDrop
    ifu.io.fetchBundle <> ifq.io.fetchBundle
    ifu.io.fetchHlt <> exceptionUnit.io.fetchHlt
    ifu.io.flushPc := MuxCase(0.U, Seq(
        exceptionUnit.io.flush -> exceptionUnit.io.flushPc,
        rob.io.flush -> rob.io.flushPc
    ))

    // Rename Table Interface
    renameTable.io.createCkpt.valid := false.B
    renameTable.io.createCkpt.bits := Vec(31,UInt(log2Ceil(c.regFileSize).W)).zero
    renameTable.io.rmLastCkpt := false.B
    renameTable.io.restoreEntry.valid := 0.U
    renameTable.io.restoreEntry.bits := Vec(31,UInt(log2Ceil(c.regFileSize).W)).zero

    // Reorder Buffer Interface
    rob.io.renameTailIndex <> renameTable.io.tailIndex

    // Register File Interface
    regStateCtrl.io.renameTableReadIndex <> renameTable.io.readIndex
    regStateCtrl.io.renameTableReadEntry <> renameTable.io.readEntry
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
    issuer.io.rsHasLdSt <> reservStation.io.rsHasLdSt
    issuer.io.rsHasMisc <> reservStation.io.rsHasMisc
    issuer.io.rsRegReqBits <> reservStation.io.rsRegReqBits
    issuer.io.robHasBrc <> rob.io.robHasBrc
    issuer.io.robReq <> rob.io.allocReq
    issuer.io.robResp <> rob.io.allocResp
    issuer.io.regStates <> regFile.io.getStates
    issuer.io.renameTable <> renameTable.io.tailEntry

    // Reservation Station Interface
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

    // Execution Unit Side Effect Control
    lsu.io.robHeadIndex <> rob.io.headIndex
    misc.io.robHeadIndex <> rob.io.headIndex

    // Commit Stage
    PipelineConnect(alu.io.commit, commitUnit.io.alu, commitUnit.io.outfires(0), flush)
    PipelineConnect(bru.io.commit, commitUnit.io.bru, commitUnit.io.outfires(1), flush)
    PipelineConnect(lsu.io.commit, commitUnit.io.lsu, commitUnit.io.outfires(2), flush)
    PipelineConnect(mdu.io.commit, commitUnit.io.mdu, commitUnit.io.outfires(3), flush)
    PipelineConnect(misc.io.commit, commitUnit.io.misc, commitUnit.io.outfires(4), flush)

    commitUnit.io.robReadIndexs  <> rob.io.readIndexs
    commitUnit.io.robReadEntries <> rob.io.readEntries
    commitUnit.io.regWrites  <> regFile.io.writePorts
    commitUnit.io.robCommits <> rob.io.commits

    // CSR and Privilege
    csrFile.io.retireEvent := rob.io.retireEvent
    csrFile.io.time <> io.time
    csrFile.io.csrio <> misc.io.csrio
    csrFile.io.privilege <> misc.io.getPrivilege

    rob.io.trap <> exceptionUnit.io.trap
    rob.io.exceptionRet <> exceptionUnit.io.exceptionRet
    misc.io.setPrivilege <> exceptionUnit.io.setPrivilege

    // AMO
    lsu.io.invalidateReserved := rob.io.exceptionRet

    // Debug Outputs
    io.pc := ifu.io.instrBundle.bits.pc
    io.instrNow := Mux(ifu.io.instrBundle.valid,ifu.io.instrBundle.bits.instr.rawBits,0.U)
}

object Main extends App {
    val configPath = if (args.nonEmpty) args(0) else "assets/core_config.json"
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