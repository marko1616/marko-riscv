package markorv.frontend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils.{
    DataOperationExtension,
    UIntOperationExtension
}

class BranchPredUnit extends Module with BaseOpcode {
    val io = IO(new Bundle {
        val bpuInstr = Input(new Bundle {
            val instr = new Instruction
            val pc    = UInt(64.W)
        })

        val bpuResult = Output(new Bundle {
            val predTaken = Bool()
            val predPc    = UInt(64.W)
        })
    })

    class BPUDecodeInfo extends Bundle {
        val isJal            = Bool()
        val isJalr           = Bool()
        val isBranch         = Bool()
        val imm              = UInt(64.W)
        val fallThroughBytes = UInt(64.W)
    }

    // 32-bit J-type immediate
    private def jImm32(inst: UInt): UInt =
        Cat(
          inst(31),
          inst(19, 12),
          inst(20),
          inst(30, 21),
          0.U(1.W)
        ).sextu(64)

    // 32-bit B-type immediate
    private def bImm32(inst: UInt): UInt =
        Cat(
          inst(31),
          inst(7),
          inst(30, 25),
          inst(11, 8),
          0.U(1.W)
        ).sextu(64)

    // 16-bit C.J immediate
    private def cjImm16(inst: UInt): UInt =
        Cat(
          inst(12),
          inst(8),
          inst(10, 9),
          inst(6),
          inst(7),
          inst(2),
          inst(11),
          inst(5, 3),
          0.U(1.W)
        ).sextu(64)

    // 16-bit C.BEQZ / C.BNEZ immediate
    private def cbImm16(inst: UInt): UInt =
        Cat(
          inst(12),
          inst(6, 5),
          inst(2),
          inst(11, 10),
          inst(4, 3),
          0.U(1.W)
        ).sextu(64)

    private def decodeForBPU(instr: Instruction): BPUDecodeInfo = {
        val d     = Wire(new BPUDecodeInfo)
        val raw   = instr.rawBits
        val raw16 = raw(15, 0)

        d.isJal            := false.B
        d.isJalr           := false.B
        d.isBranch         := false.B
        d.imm              := 0.U(64.W)
        d.fallThroughBytes := Mux(instr.isCompressed, 2.U(64.W), 4.U(64.W))

        when(!instr.isCompressed) {
            switch(raw(6, 0)) {
                is(OP_JAL) {
                    d.isJal := true.B
                    d.imm   := jImm32(raw)
                }

                is(OP_JALR) {
                    d.isJalr := true.B
                }

                is(OP_BRANCH) {
                    d.isBranch := true.B
                    d.imm      := bImm32(raw)
                }
            }
        }.otherwise {
            switch(raw16(1, 0)) {
                is("b01".U) {
                    switch(raw16(15, 13)) {
                        is("b101".U) { // C.J
                            d.isJal := true.B
                            d.imm   := cjImm16(raw16)
                        }

                        is("b110".U, "b111".U) { // C.BEQZ / C.BNEZ
                            d.isBranch := true.B
                            d.imm      := cbImm16(raw16)
                        }
                    }
                }

                is("b10".U) {
                    when(raw16(15, 13) === "b100".U) {
                        val rs1 = raw16(11, 7)
                        val rs2 = raw16(6, 2)

                        when(
                          raw16(
                            12
                          ) === 0.U && rs2 === rs2.zeroAsUInt && rs1 =/= rs1.zeroAsUInt
                        ) {
                            // C.JR
                            d.isJalr := true.B
                        }.elsewhen(
                          raw16(
                            12
                          ) === 1.U && rs2 === rs2.zeroAsUInt && rs1 =/= rs1.zeroAsUInt
                        ) {
                            // C.JALR
                            d.isJalr := true.B
                        }
                    }
                }
            }
        }

        d
    }

    val instr = io.bpuInstr.instr
    val pc    = io.bpuInstr.pc
    val dec   = decodeForBPU(instr)

    val seqPc = pc +% dec.fallThroughBytes

    io.bpuResult.predTaken := false.B
    io.bpuResult.predPc    := seqPc

    when(dec.isJal) {
        io.bpuResult.predTaken := true.B
        io.bpuResult.predPc    := pc +% dec.imm
    }.elsewhen(dec.isBranch) {
        val taken = dec.imm.asSInt < 0.S
        io.bpuResult.predTaken := taken
        io.bpuResult.predPc    := Mux(taken, pc +% dec.imm, seqPc)
    }.elsewhen(dec.isJalr) {
        // TODO
        io.bpuResult.predTaken := true.B
        io.bpuResult.predPc    := seqPc
    }
}
