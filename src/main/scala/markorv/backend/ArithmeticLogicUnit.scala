package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend.DecoderOutParams
import markorv.backend._

object ALUFunct3Norm extends ChiselEnum {
    val add  = Value("b000".U)
    val sll  = Value("b001".U)
    val slt  = Value("b010".U)
    val sltu = Value("b011".U)
    val xor  = Value("b100".U)
    val srl  = Value("b101".U)
    val or   = Value("b110".U)
    val and  = Value("b111".U)
}

object ALUFunct3SubSra extends ChiselEnum {
    val sub  = Value("b000".U)
    val sra  = Value("b101".U)
}

class ALUInstr extends Bundle {
    val alu_opcode = new ALUOpcode
    val params = new DecoderOutParams(64)
}

class ALUIO extends Bundle {
    val alu_instr = Flipped(Decoupled(new ALUInstr))
    val register_commit = Decoupled(new RegisterCommit)
    val outfire = Output(Bool())
}

class ArithmeticLogicUnit extends Module {
    val io = IO(new ALUIO)
    val result = WireInit(0.U(64.W))
    io.alu_instr.ready := true.B

    io.register_commit.bits.data := 0.U
    io.register_commit.bits.reg := 0.U
    io.register_commit.valid := false.B
    io.outfire := false.B

    val opcode = io.alu_instr.bits.alu_opcode
    val params = io.alu_instr.bits.params
    val source1 = params.source1
    val source2 = params.source2
    val op32 = opcode.op32

    val sra_shift = Mux(op32,
        (source1(31,0).asSInt >> source2(4,0)).asUInt,
        (source1.asSInt >> source2(5,0)).asUInt
    )

    val sll_shift = Mux(op32,
        source1 << source2(4,0),
        source1 << source2(5,0)
    )

    val srl_shift = Mux(op32,
        source1(31,0) >> source2(4,0),
        source1 >> source2(5,0)
    )

    val funct3_norm = ALUFunct3Norm(opcode.funct3)
    val (funct3_sub_sra, valid_funct3_sub_sra) = ALUFunct3SubSra.safe(opcode.funct3)
    val valid_funct3 = Mux(opcode.sra_sub, valid_funct3_sub_sra, true.B)

    result := Mux(opcode.sra_sub,
        MuxLookup(funct3_sub_sra, 0.U)(Seq(
            ALUFunct3SubSra.sub -> (source1 - source2),
            ALUFunct3SubSra.sra -> sra_shift
        )),
        MuxLookup(funct3_norm, 0.U)(Seq(
            ALUFunct3Norm.add -> (source1 + source2),
            ALUFunct3Norm.slt -> (source1.asSInt < source2.asSInt).asUInt,
            ALUFunct3Norm.sltu -> (source1 < source2).asUInt,
            ALUFunct3Norm.xor -> (source1 ^ source2),
            ALUFunct3Norm.or -> (source1 | source2),
            ALUFunct3Norm.and -> (source1 & source2),
            ALUFunct3Norm.sll -> sll_shift,
            ALUFunct3Norm.srl -> srl_shift
        ))
    )
    when(valid_funct3) {
        io.register_commit.valid := io.alu_instr.valid
        io.register_commit.bits.reg := params.rd
        io.register_commit.bits.data := Mux(opcode.op32, result(31, 0).sextu(64), result)
        io.outfire := io.alu_instr.valid
    }
}
