package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.backend._

class InstrDecoder extends Module with BaseOpcode {
    val io = IO(new Bundle {
        val decodeTask = Flipped(Decoupled(new InstrDecodeTask))
        val issueTask  = Decoupled(new IssueTask)

        val outfire = Output(Bool())
    })

    case class DecodeEntry(
        opcode: UInt,
        matchFn: Instruction32 => Bool,
        handler: (Instruction32, ExuOpcode, LogicRegRequests, DecodedParams, UInt) => Bool,
        unit: EXUEnum.Type
    )

    val instr  = io.decodeTask.bits.instr
    val pc     = io.decodeTask.bits.pc
    val opcode = instr.rawBits(6, 0)

    val decodedResults = Seq(
        DecodeEntry(
        OP_LUI,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.aluOpcode.fromLui(i, lregReq, params, pc),
        EXUEnum.alu
        ),
        DecodeEntry(
        OP_AUIPC,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.aluOpcode.fromAuipc(i, lregReq, params, pc),
        EXUEnum.alu
        ),
        DecodeEntry(
        OP_IMM,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.aluOpcode.fromImm(i, lregReq, params, pc),
        EXUEnum.alu
        ),
        DecodeEntry(
        OP_IMM32,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.aluOpcode.fromImm32(i, lregReq, params, pc),
        EXUEnum.alu
        ),
        DecodeEntry(
        OP,
        i => i.rawBits(31, 25) =/= "b0000001".U,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.aluOpcode.fromReg(i, lregReq, params, pc),
        EXUEnum.alu
        ),
        DecodeEntry(
        OP,
        i => i.rawBits(31, 25) === "b0000001".U,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.mduOpcode.fromReg(i, lregReq, params, pc),
        EXUEnum.mdu
        ),
        DecodeEntry(
        OP_32,
        i => i.rawBits(31, 25) =/= "b0000001".U,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.aluOpcode.fromReg32(i, lregReq, params, pc),
        EXUEnum.alu
        ),
        DecodeEntry(
        OP_32,
        i => i.rawBits(31, 25) === "b0000001".U,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.mduOpcode.fromReg32(i, lregReq, params, pc),
        EXUEnum.mdu
        ),
        DecodeEntry(
        OP_LOAD,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.lsuOpcode.fromLoad(i, lregReq, params, pc),
        EXUEnum.lsu
        ),
        DecodeEntry(
        OP_STOR,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.lsuOpcode.fromStore(i, lregReq, params, pc),
        EXUEnum.lsu
        ),
        DecodeEntry(
        OP_JAL,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.branchOpcode.fromJal(i, lregReq, params, pc),
        EXUEnum.bru
        ),
        DecodeEntry(
        OP_JALR,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.branchOpcode.fromJalr(i, lregReq, params, pc, i.from16),
        EXUEnum.bru
        ),
        DecodeEntry(
        OP_BRANCH,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.branchOpcode.fromBranch(i, lregReq, params, pc, i.from16),
        EXUEnum.bru
        ),
        DecodeEntry(
        OP_SYSTEM,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.miscOpcode.fromSys(i, lregReq, params, pc),
        EXUEnum.misc
        ),
        DecodeEntry(
        OP_MISC_MEM,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.miscOpcode.fromMISCMem(i, lregReq, params, pc),
        EXUEnum.misc
        ),
        DecodeEntry(
        OP_AMO,
        _ => true.B,
        (i, exuOpcode, lregReq, params, pc) =>
            exuOpcode.lsuOpcode.fromAmo(i, lregReq, params, pc),
        EXUEnum.lsu
        )
    ).map { entry =>
        val hit       = WireDefault(false.B)
        val lregReq   = WireInit(new LogicRegRequests().zero)
        val params    = WireInit(new DecodedParams().zero)
        val exu       = WireDefault(entry.unit)
        val exuOpcode = WireInit(new ExuOpcode().zero)
        params.pc := pc
        hit := io.decodeTask.valid && (opcode === entry.opcode) && entry.matchFn(instr) && entry.handler(instr, exuOpcode, lregReq, params, pc)
        (hit, lregReq, params, exu, exuOpcode)
    }

    val hits       = decodedResults.map(_._1)
    val lregReqs   = decodedResults.map(_._2)
    val paramsList = decodedResults.map(_._3)
    val exus       = decodedResults.map(_._4)
    val exuOpcodes = decodedResults.map(_._5)

    val validInstr = hits.reduce(_ || _)

    val selectedLregReq   = Mux1H(hits, lregReqs)
    val selectedParams    = Mux1H(hits, paramsList)
    val selectedExu       = suppressEnumCastWarning { Mux1H(hits, exus) }
    val selectedExuOpcode = Mux1H(hits, exuOpcodes)

    val illegalInstrLregReq   = WireInit(new LogicRegRequests().zero)
    val illegalInstrParams    = WireInit(new DecodedParams().zero)
    val illegalInstrExu       = WireDefault(EXUEnum.misc)
    val illegalInstrExuOpcode = WireInit(new ExuOpcode().zero)

    illegalInstrParams.pc := pc
    illegalInstrExuOpcode.miscOpcode.fromIllegal(
        instr,
        illegalInstrLregReq,
        illegalInstrParams,
        pc
    )

    val finalLregReq   = Mux(validInstr, selectedLregReq, illegalInstrLregReq)
    val finalParams    = Mux(validInstr, selectedParams, illegalInstrParams)
    val finalExu       = Mux(validInstr, selectedExu, illegalInstrExu)
    val finalExuOpcode = Mux(validInstr, selectedExuOpcode, illegalInstrExuOpcode)

    val issueTask = WireInit(new IssueTask().zero)
    issueTask.lregReq    := finalLregReq
    issueTask.params     := finalParams
    issueTask.exu := suppressEnumCastWarning { finalExu.asTypeOf(EXUEnum()) }
    issueTask.exuOpcode  := finalExuOpcode
    issueTask.predTaken  := io.decodeTask.bits.predTaken
    issueTask.predPc     := io.decodeTask.bits.predPc

    io.issueTask.bits := issueTask

    val fire = io.decodeTask.valid && io.issueTask.ready
    io.issueTask.valid  := io.decodeTask.valid
    io.outfire          := fire
    io.decodeTask.ready := io.issueTask.ready
}
