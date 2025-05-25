package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.frontend.DecoderOutParams
import markorv.backend._

class ALUInstr extends Bundle {
    val aluOpcode = new ALUOpcode
    val params = new DecoderOutParams
}

class ALUIO extends Bundle {
    val aluInstr = Flipped(Decoupled(new ALUInstr))
    val commit = Decoupled(new CommitBundle)
    val outfire = Output(Bool())
}

class ArithmeticLogicUnit extends Module {
    val io = IO(new ALUIO)
    val result = WireInit(0.U(64.W))
    io.aluInstr.ready := true.B

    io.commit.bits.data := 0.U
    io.commit.bits.reg := 0.U
    io.commit.valid := false.B
    io.outfire := false.B

    val opcode = io.aluInstr.bits.aluOpcode
    val params = io.aluInstr.bits.params
    val source1 = params.source1
    val source2 = params.source2
    val op32 = opcode.op32

    val sraShift = Mux(op32,
        (source1(31,0).asSInt >> source2(4,0)).asUInt,
        (source1.asSInt >> source2(5,0)).asUInt
    )

    val sllShift = Mux(op32,
        source1 << source2(4,0),
        source1 << source2(5,0)
    )

    val srlShift = Mux(op32,
        source1(31,0) >> source2(4,0),
        source1 >> source2(5,0)
    )

    val funct3Norm = opcode.getFunct3Norm()
    val funct3SubSra = opcode.getFunct3SubSra()

    result := Mux(opcode.sraSub,
        MuxLookup(funct3SubSra, 0.U)(Seq(
            ALUFunct3SubSra.sub -> (source1 - source2),
            ALUFunct3SubSra.sra -> sraShift
        )),
        MuxLookup(funct3Norm, 0.U)(Seq(
            ALUFunct3Norm.add -> (source1 + source2),
            ALUFunct3Norm.slt -> (source1.asSInt < source2.asSInt).asUInt,
            ALUFunct3Norm.sltu -> (source1 < source2).asUInt,
            ALUFunct3Norm.xor -> (source1 ^ source2),
            ALUFunct3Norm.or -> (source1 | source2),
            ALUFunct3Norm.and -> (source1 & source2),
            ALUFunct3Norm.sll -> sllShift,
            ALUFunct3Norm.srl -> srlShift
        ))
    )

    io.commit.valid := io.aluInstr.valid
    io.commit.bits.reg := params.rd
    io.commit.bits.data := Mux(opcode.op32, result(31, 0).sextu(64), result)
    io.outfire := io.aluInstr.valid
}
