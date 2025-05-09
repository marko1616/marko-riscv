package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.trap._
import markorv.frontend.DecoderOutParams
import markorv.ControlStatusRegistersIO

object CsrOperation extends ChiselEnum {
    val csrrw = Value("h1".U)
    val csrrs = Value("h2".U)
    val csrrc = Value("h3".U)
}

object SystemOperation extends ChiselEnum {
    val ecall = Value("h1".U)
    val ebreak = Value("h2".U)
    val wfi = Value("h3".U)
    val mret = Value("h4".U)
}

object MemoryOperation extends ChiselEnum {
    val fence = Value("h1".U)
    val fenceI = Value("h2".U)
}

class MiscUnit extends Module {
    val io = IO(new Bundle {
        val miscInstr = Flipped(Decoupled(new Bundle {
            val miscOpcode = new MiscOpcode
            val params = new DecoderOutParams
        }))
        val commit = Decoupled(new CommitBundle)

        val csrio = Flipped(new ControlStatusRegistersIO)
        val outfire = Output(Bool())

        val getPrivilege = Output(UInt(2.W))
        val setPrivilege = Flipped(Decoupled(UInt(2.W)))

        val exception = Decoupled(new ExceptionInfo)
        val trapRet = Output(Bool())

        val flush = Output(Bool())
        val setPc = Output(UInt(64.W))

        val icacheInvalidate = Output(Bool())
        val icacheInvalidateOutfire = Input(Bool())
    })

    // M-mode by default on reset
    val privilegeReg = RegInit(3.U(2.W))
    val opcode = io.miscInstr.bits.miscOpcode
    val params = io.miscInstr.bits.params

    val (csrOp, validCsrOp) = CsrOperation.safe(opcode.miscCsrFunct(1, 0))
    val (sysOp, validSysOp) = SystemOperation.safe(opcode.miscSysFunct)
    val (memOp, validMemOp) = MemoryOperation.safe(opcode.miscMemFunct)

    val validOp = io.miscInstr.valid && (validCsrOp || validSysOp || validMemOp)

    io.csrio.readEn := false.B
    io.csrio.writeEn := false.B
    io.csrio.readAddr := 0.U
    io.csrio.writeAddr := 0.U
    io.csrio.writeData := 0.U

    io.outfire := false.B
    io.miscInstr.ready := true.B
    io.commit.valid := false.B
    io.commit.bits := new CommitBundle().zero

    io.exception.valid := false.B
    io.exception.bits := new ExceptionInfo().zero

    io.getPrivilege := privilegeReg
    io.setPrivilege.ready := true.B
    io.trapRet := false.B

    io.flush := false.B
    io.setPc := 0.U

    io.icacheInvalidate := false.B

    when(validOp) {
        when(validCsrOp) {
            val csrSrc1 = params.source1
            val csrAddr = params.source2
            io.csrio.readEn := opcode.miscCsrFunct(3)
            io.csrio.writeEn := opcode.miscCsrFunct(2)

            val isCsrReadOnly = csrAddr(11, 10) === 3.U
            val hasPrivilege = privilegeReg > csrAddr(9, 8)

            io.csrio.readAddr := csrAddr
            val csrData = io.csrio.readData

            io.csrio.writeAddr := csrAddr
            io.csrio.writeData := MuxLookup(csrOp, 0.U)(Seq(
                CsrOperation.csrrw -> csrSrc1,
                CsrOperation.csrrs -> (csrData | csrSrc1),
                CsrOperation.csrrc -> (csrData & ~csrSrc1)
            ))

            io.commit.valid := true.B
            io.commit.bits.reg := params.rd
            io.commit.bits.data := csrData
            io.outfire := true.B
        }

        when(validSysOp) {
            switch(sysOp) {
                is(SystemOperation.wfi) {
                    io.outfire := true.B // wfi treated as NOP
                }
                is(SystemOperation.ecall) {
                    io.exception.valid := true.B
                    io.exception.bits.cause := 11.U
                    io.exception.bits.retAddr := params.pc + 4.U
                    io.outfire := true.B
                }
                is(SystemOperation.ebreak) {
                    io.exception.valid := true.B
                    io.exception.bits.cause := 3.U
                    io.exception.bits.retAddr := params.pc + 4.U
                    io.outfire := true.B
                }
                is(SystemOperation.mret) {
                    io.trapRet := true.B
                    io.outfire := true.B
                }
            }
        }

        when(validMemOp) {
            switch(memOp) {
                is(MemoryOperation.fenceI) {
                    io.miscInstr.ready := false.B
                    io.icacheInvalidate := true.B
                    when(io.icacheInvalidateOutfire) {
                        io.flush := true.B
                        io.outfire := true.B
                        io.setPc := params.source1
                    }
                }
            }
        }
    }

    when(io.setPrivilege.valid) {
        privilegeReg := io.setPrivilege.bits
    }
}
