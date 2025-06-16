package markorv.manage

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import markorv.utils.ChiselUtils._
import markorv.utils._
import markorv.config._
import markorv.backend._

class ReservationStationDebug extends DPIClockedVoidFunctionImport {
    val functionName = "update_rs"
    override val inputNames = Some(Seq("entry", "index"))
}

class ReservationStation(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        // Issuer signals
        // ========================
        val rsReq = Flipped(Decoupled(new ReservationStationEntry))
        val rsHasLdSt = Output(Bool())
        val rsHasMisc = Output(Bool())
        val rsRegReqBits = Output(UInt(c.regFileSize.W))

        // Dispatch signals
        // ========================
        val lsuOut = Decoupled(new Bundle {
            val lsuOpcode = new LoadStoreOpcode
            val params = new EXUParams
        })

        val aluOut = Decoupled(new Bundle {
            val aluOpcode = new ALUOpcode
            val params = new EXUParams
        })

        val miscOut = Decoupled(new Bundle {
            val miscOpcode = new MISCOpcode
            val params = new EXUParams
        })

        val mduOut = Decoupled(new Bundle {
            val mduOpcode = new MDUOpcode
            val params = new EXUParams
        })

        val bruOut = Decoupled(new Bundle {
            val branchOpcode = new BranchOpcode
            val predTaken = Bool()
            val predPc = UInt(64.W)
            val params = new EXUParams
        })

        // Register signals
        // ========================
        val regStates = Input(Vec(c.regFileSize, new PhyRegState.Type))
        val regRead1 = Output(UInt(5.W))
        val regRead2 = Output(UInt(5.W))
        val regData1 = Input(UInt(64.W))
        val regData2 = Input(UInt(64.W))

        // Boardcast
        // ========================
        val flush = Input(Bool())
    })

    val regStates = io.regStates
    val buffer    = RegInit(VecInit.tabulate(c.rsSize)(_ => new ReservationStationEntry().zero))

    val validVec = buffer.map(_.valid)
    val readyVec = buffer.map(_.ready(regStates))

    val freeBits         = validVec.map(~_.asUInt).reduce(_ ## _)
    val freeLeadingZeros = CountLeadingZeros(freeBits)
    val hasFreeEntries   = ~freeLeadingZeros(log2Ceil(c.rsSize))
    val freeIndex        = freeLeadingZeros(log2Ceil(c.rsSize)-1, 0)

    val readyBits         = readyVec.map(_.asUInt).reduce(_ ## _)
    val readyLeadingZeros = CountLeadingZeros(readyBits)
    val hasReadyEntries   = ~readyLeadingZeros(log2Ceil(c.rsSize))
    val readyIndex        = readyLeadingZeros(log2Ceil(c.rsSize)-1, 0)

    io.rsReq.ready := hasFreeEntries
    io.rsHasLdSt := buffer.map(e => e.exu === EXUEnum.lsu  && e.valid).reduce(_ || _)
    io.rsHasMisc := buffer.map(e => e.exu === EXUEnum.misc && e.valid).reduce(_ || _)
    io.rsRegReqBits := buffer.map { entry =>
        val prs1Bit = Mux(entry.regReq.prs1Valid, (1.U << entry.regReq.prs1), 0.U)
        val prs2Bit = Mux(entry.regReq.prs2Valid, (1.U << entry.regReq.prs2), 0.U)
        val regBits = prs1Bit | prs2Bit
        Mux(entry.valid, regBits, 0.U)
    }.reduce(_ | _)

    // Set new entry
    // ========================
    when(io.rsReq.valid && hasFreeEntries) {
        buffer(freeIndex) := io.rsReq.bits
    }

    // Dispatch entry
    // ========================
    val readyEntry = buffer(readyIndex)
    val params = readyEntry.params
    val regRes = readyEntry.regReq
    val finalParams = WireInit(new EXUParams().zero)

    io.regRead1 := readyEntry.regReq.prs1
    io.regRead2 := readyEntry.regReq.prs2
    val regData1 = io.regData1
    val regData2 = io.regData2

    finalParams.robIndex := readyEntry.params.robIndex
    finalParams.pc := readyEntry.params.pc
    finalParams.source1 := params.source1 + Mux(readyEntry.regReq.prs1Valid, regData1, 0.U)
    finalParams.source2 := params.source2 + Mux(readyEntry.regReq.prs2Valid, regData2, 0.U)

    io.aluOut.valid := false.B
    io.bruOut.valid := false.B
    io.lsuOut.valid := false.B
    io.mduOut.valid := false.B
    io.miscOut.valid := false.B
    when(hasReadyEntries) {
        val exuFire = WireInit(false.B)
        when(readyEntry.exu === EXUEnum.alu) {
            io.aluOut.valid := true.B
            exuFire := io.aluOut.ready
        }.elsewhen(readyEntry.exu === EXUEnum.bru) {
            io.bruOut.valid := true.B
            exuFire := io.bruOut.ready
        }.elsewhen(readyEntry.exu === EXUEnum.lsu) {
            io.lsuOut.valid := true.B
            exuFire := io.lsuOut.ready
        }.elsewhen(readyEntry.exu === EXUEnum.mdu) {
            io.mduOut.valid := true.B
            exuFire := io.mduOut.ready
        }.elsewhen(readyEntry.exu === EXUEnum.misc) {
            io.miscOut.valid := true.B
            exuFire := io.miscOut.ready
        }
        when(exuFire) {
            readyEntry.valid := false.B
        }
    }

    io.aluOut.bits.aluOpcode := readyEntry.opcodes.aluOpcode
    io.aluOut.bits.params := finalParams

    io.bruOut.bits.branchOpcode := readyEntry.opcodes.branchOpcode
    io.bruOut.bits.predTaken := readyEntry.predTaken
    io.bruOut.bits.predPc := readyEntry.predPc
    io.bruOut.bits.params := finalParams

    io.lsuOut.bits.lsuOpcode := readyEntry.opcodes.lsuOpcode
    io.lsuOut.bits.params := finalParams

    io.mduOut.bits.mduOpcode := readyEntry.opcodes.mduOpcode
    io.mduOut.bits.params := finalParams

    io.miscOut.bits.miscOpcode := readyEntry.opcodes.miscOpcode
    io.miscOut.bits.params := finalParams

    // Flush
    // ========================
    when(io.flush) {
        buffer := VecInit.tabulate(c.rsSize)(_ => new ReservationStationEntry().zero)
    }

    // Debug
    if(c.simulate) {
        val debugger = new ReservationStationDebug
        for((e,i) <- buffer.zipWithIndex) {
            debugger.call(e, i.U(32.W))
        }
    }
}