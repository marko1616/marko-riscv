package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.backend._

class InstrDecoder extends Module {
    val io = IO(new Bundle {
        val instrBundle = Flipped(Decoupled(new InstrDecodeBundle))
        val issueTask = Decoupled(new IssueTask)

        val invalidDrop = Output(Bool())
        val outfire = Output(Bool())
    })

    // Helper case class for table-driven decoding
    case class DecodeEntry(
        opcode: UInt,
        matchFn: Instruction => Bool,
        handler: (Instruction, RegisterRequests, DecodedParams, UInt) => Bool,
        unit: EXUEnum.Type
    )

    // Local wires
    val issueTask = WireInit(new IssueTask().zero)
    val validInstr = WireDefault(false.B)
    val instr = io.instrBundle.bits.instr
    val pc = io.instrBundle.bits.pc
    val operateUnit = WireInit(EXUEnum.alu)
    val params = WireInit(new DecodedParams().zero)
    params.pc := pc

    val regRequests = WireDefault(0.U.asTypeOf(new RegisterRequests))
    val opcode = instr.rawBits(6, 0)

    // Pred info (used only by branch-like instructions)
    val predTaken = io.instrBundle.bits.predTaken
    val predPc = io.instrBundle.bits.predPc

    // === OPCODE CONSTANTS ===
    val OP_LUI      = "b0110111".U
    val OP_AUIPC    = "b0010111".U
    val OP_IMM      = "b0010011".U
    val OP_IMM32    = "b0011011".U
    val OP          = "b0110011".U
    val OP_32       = "b0111011".U
    val OP_LOAD     = "b0000011".U
    val OP_STOR     = "b0100011".U
    val OP_JAL      = "b1101111".U
    val OP_JALR     = "b1100111".U
    val OP_BRANCH   = "b1100011".U
    val OP_SYSTEM   = "b1110011".U
    val OP_MISC_MEM = "b0001111".U
    val OP_AMO      = "b0101111".U

    // === Decode Table ===
    val decodeTable = Seq(
        DecodeEntry(OP_LUI,     _ => true.B, issueTask.aluOpcode.fromLui,       EXUEnum.alu),
        DecodeEntry(OP_AUIPC,   _ => true.B, issueTask.aluOpcode.fromAuipc,     EXUEnum.alu),
        DecodeEntry(OP_IMM,     _ => true.B, issueTask.aluOpcode.fromImm,       EXUEnum.alu),
        DecodeEntry(OP_IMM32,   _ => true.B, issueTask.aluOpcode.fromImm32,     EXUEnum.alu),
        DecodeEntry(OP,         _ => true.B, issueTask.aluOpcode.fromReg,       EXUEnum.alu),
        DecodeEntry(OP,         i => i.rawBits(31,25) === "b0000001".U, issueTask.mduOpcode.fromReg,   EXUEnum.mdu),
        DecodeEntry(OP_32,      _ => true.B, issueTask.aluOpcode.fromReg32,     EXUEnum.alu),
        DecodeEntry(OP_32,      i => i.rawBits(31,25) === "b0000001".U, issueTask.mduOpcode.fromReg32, EXUEnum.mdu),
        DecodeEntry(OP_LOAD,    _ => true.B, issueTask.lsuOpcode.fromLoad,      EXUEnum.lsu),
        DecodeEntry(OP_STOR,    _ => true.B, issueTask.lsuOpcode.fromStore,     EXUEnum.lsu),
        DecodeEntry(OP_JAL,     _ => true.B, issueTask.branchOpcode.fromJal,    EXUEnum.bru),
        DecodeEntry(OP_JALR,    _ => true.B, issueTask.branchOpcode.fromJalr,   EXUEnum.bru),
        DecodeEntry(OP_BRANCH,  _ => true.B, issueTask.branchOpcode.fromBranch, EXUEnum.bru),
        DecodeEntry(OP_SYSTEM,  _ => true.B, issueTask.miscOpcode.fromSys,      EXUEnum.misc),
        DecodeEntry(OP_MISC_MEM,_ => true.B, issueTask.miscOpcode.fromMISCMem,  EXUEnum.misc),
        DecodeEntry(OP_AMO,     _ => true.B, issueTask.lsuOpcode.fromAmo,       EXUEnum.lsu)
    )

    // === Decode dispatcher ===
    for (entry <- decodeTable) {
        when(io.instrBundle.valid && (opcode === entry.opcode) && entry.matchFn(instr)) {
            validInstr := entry.handler(instr, regRequests, params, pc)
            operateUnit := entry.unit

            when(entry.unit === EXUEnum.bru) {
                issueTask.predTaken := predTaken
                issueTask.predPc := predPc
            }
        }
    }

    // === Commit task ===
    io.instrBundle.ready := false.B
    io.outfire := false.B
    io.invalidDrop := false.B
    io.issueTask.valid := false.B
    io.issueTask.bits := new IssueTask().zero

    when(validInstr && io.instrBundle.valid && io.issueTask.ready) {
        issueTask.params := params
        issueTask.operateUnit := operateUnit
        issueTask.regRequests := regRequests

        io.issueTask.valid := true.B
        io.issueTask.bits := issueTask
        io.instrBundle.ready := true.B
        io.outfire := true.B
    }

    when(!validInstr) {
        io.instrBundle.ready := io.issueTask.ready
        when(io.instrBundle.valid) {
            io.invalidDrop := true.B
            io.outfire := true.B
        }
    }
}
