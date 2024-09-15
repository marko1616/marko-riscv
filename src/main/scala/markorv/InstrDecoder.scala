package markorv

import chisel3._
import chisel3.util._

import markorv.InstrIPBundle

class DecoderOutParams(data_width: Int) extends Bundle {
    val immediate = SInt(data_width.W)
    val source1 = SInt(data_width.W)
    val source2 = SInt(data_width.W)
    val rd = UInt(5.W)
}

class InstrDecoder(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        val instr_bundle = Flipped(Decoupled(new InstrIPBundle))

        val lsu_out = Decoupled(new Bundle {
            // {Load:0/Store:1}[4]{Mem:0/Imm:1}[3]{SInt:0/UInt:1}[2]{Size}[1,0]
            val lsu_opcode = UInt(5.W)
            val params = new DecoderOutParams(data_width)
        })

        val reg_read1 = Output(UInt(5.W))
        val reg_read2 = Output(UInt(5.W))
        val reg_data1 = Input(UInt(data_width.W))
        val reg_data2 = Input(UInt(data_width.W))

        val acquire_reg = Output(UInt(5.W))
        val acquired = Input(Bool())
        val occupied_regs = Input(UInt(32.W))

        val outfire = Output(Bool())
    })

    val instr = Wire(UInt(32.W))
    val opcode = Wire(UInt(7.W))
    val acquire_reg = Wire(UInt(5.W))
    val next_stage_ready = Wire(Bool())
    val valid_instr = Wire(Bool())
    val occupied_reg = Wire(Bool())

    instr := io.instr_bundle.bits.instr
    opcode := instr(6,0)
    acquire_reg := 0.U
    next_stage_ready := io.lsu_out.ready
    valid_instr := false.B
    occupied_reg := false.B

    io.instr_bundle.ready := false.B
    io.outfire := false.B
    io.reg_read1 := 0.U(addr_width.W)
    io.reg_read2 := 0.U(addr_width.W)
    io.acquire_reg := 0.U(5.W)

    io.lsu_out.valid := false.B
    io.lsu_out.bits.lsu_opcode := 0.U
    io.lsu_out.bits.params.immediate := 0.S(data_width.W)
    io.lsu_out.bits.params.source1 := 0.S(data_width.W)
    io.lsu_out.bits.params.source2 := 0.S(data_width.W)
    io.lsu_out.bits.params.rd := 0.U

    when(io.instr_bundle.valid) {
        switch(opcode) {
            is("b0000011".U) {
                // Load Memory
                io.reg_read1 := instr(19,15)
                io.reg_read2 := 0.U(5.W)

                occupied_reg := io.occupied_regs(instr(19,15))

                io.lsu_out.bits.lsu_opcode := Cat(0.U(2.W), instr(14,12))
                io.lsu_out.bits.params.immediate := instr(31,20).asSInt
                io.lsu_out.bits.params.source1 := io.reg_data1.asSInt
                io.lsu_out.bits.params.source2 := 0.S(data_width.W)
                io.lsu_out.bits.params.rd := instr(11,7)

                acquire_reg := instr(11,7)

                valid_instr := true.B
            }
            is("b0100011".U) {
                // Store Memory
                io.reg_read1 := instr(19,15)
                io.reg_read2 := instr(24,20)

                occupied_reg := io.occupied_regs(instr(19,15)) | io.occupied_regs(instr(24,20))

                io.lsu_out.bits.lsu_opcode := Cat("b10".U, instr(14,12))
                io.lsu_out.bits.params.immediate := Cat(instr(31,25),instr(11,7)).asSInt
                io.lsu_out.bits.params.source1 := io.reg_data1.asSInt
                io.lsu_out.bits.params.source2 := io.reg_data2.asSInt
                io.lsu_out.bits.params.rd := 0.U(5.W)

                valid_instr := true.B
            }
        }
    }

    when(next_stage_ready) {
        io.acquire_reg := acquire_reg
    }
    when(io.acquired && io.instr_bundle.valid && valid_instr && (~occupied_reg)) {
        io.lsu_out.valid := true.B
        io.instr_bundle.ready := true.B
        io.outfire := true.B
    }
    when(~valid_instr) {
        io.instr_bundle.ready := true.B
    }
}