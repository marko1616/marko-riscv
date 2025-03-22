package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend.DecoderOutParams
import markorv.backend._

object BranchFunct extends ChiselEnum {
    val beq  = Value("b0000".U)
    val bne  = Value("b0001".U)
    val blt  = Value("b0100".U)
    val bge  = Value("b0101".U)
    val bltu = Value("b0110".U)
    val bgeu = Value("b0111".U)

    val jal  = Value("b1000".U)
    val jalr = Value("b1001".U)
}

class BranchUnit extends Module {
    val io = IO(new Bundle {
        val branch_instr = Flipped(Decoupled(new Bundle {
            val branch_opcode = new BranchOpcode
            val pred_taken = Bool()
            val pred_pc = UInt(64.W)
            val recover_pc = UInt(64.W)
            val params = new DecoderOutParams
        }))

        val register_commit = Decoupled(new Bundle {
            val reg = Input(UInt(5.W))
            val data = Input(UInt(64.W))
        })

        val flush = Bool()
        val set_pc = UInt(64.W)
        val outfire = Output(Bool())
    })

    io.outfire := false.B
    io.branch_instr.ready := true.B

    io.register_commit.valid := false.B
    io.register_commit.bits.reg := 0.U
    io.register_commit.bits.data := 0.U
    io.flush := false.B
    io.set_pc := 0.U

    val params     = io.branch_instr.bits.params
    val pred_pc    = io.branch_instr.bits.pred_pc
    val pred_taken = io.branch_instr.bits.pred_taken
    val recover_pc = io.branch_instr.bits.recover_pc
    val source1    = params.source1
    val source2    = params.source2
    val jalr_pc    = ((source1 + source2) & ~(1.U(64.W)))

    val (funct, valid_funct) = BranchFunct.safe(io.branch_instr.bits.branch_opcode.funct)
    val branch_taken = MuxLookup(funct, false.B)(Seq(
        BranchFunct.beq -> (source1 === source2),
        BranchFunct.bne -> (source1 =/= source2),
        BranchFunct.blt -> (source1.asSInt < source2.asSInt),
        BranchFunct.bge -> (source1.asSInt >= source2.asSInt),
        BranchFunct.bltu -> (source1 < source2),
        BranchFunct.bgeu -> (source1 >= source2),
    ))
    val recover = MuxLookup(funct, branch_taken =/= pred_taken)(Seq(
        BranchFunct.jal -> (false.B),
        BranchFunct.jalr -> (jalr_pc =/= pred_pc)
    ))

    when(valid_funct) {
        io.register_commit.valid := io.branch_instr.valid
        io.register_commit.bits.reg := Mux(funct.in(BranchFunct.jal, BranchFunct.jalr), params.rd, 0.U)
        io.register_commit.bits.data := Mux(funct.in(BranchFunct.jal, BranchFunct.jalr), params.pc + 4.U, 0.U)
        io.flush := io.branch_instr.valid && recover
        io.set_pc := Mux(funct === BranchFunct.jalr, jalr_pc, recover_pc)
        io.outfire := io.branch_instr.valid
    }
}
