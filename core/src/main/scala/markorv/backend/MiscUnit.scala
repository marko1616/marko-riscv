package markorv.backend

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.trap._
import markorv.config._
import markorv.frontend.DecodedParams
import markorv.ControlStatusRegistersIO
import markorv.manage.RegisterCommit
import markorv.manage.EXUParams
import markorv.manage.DisconEventType

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
    val sret = Value("h5".U)
    val sfenceVma = Value("h6".U)
    val pmaFaultLowInstr = Value("h7".U)
    val pageFaultLowInstr = Value("h8".U)
    val pmaFaultHighInstr = Value("h9".U)
    val pageFaultHighInstr = Value("ha".U)
    val illegalInstr = Value("hb".U)
}

object MemoryOperation extends ChiselEnum {
    val fence = Value("h1".U)
    val fenceI = Value("h2".U)
}

class MISCUnit(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val miscInstr = Flipped(Decoupled(new Bundle {
            val miscOpcode = new MISCOpcode
            val params = new EXUParams
        }))
        val commit = Decoupled(new MISCCommit)

        val csrio = Flipped(new ControlStatusRegistersIO)
        val outfire = Output(Bool())

        val getPrivilege = Output(UInt(2.W))
        val setPrivilege = Flipped(Valid(UInt(2.W)))
        val mepc = Input(UInt(64.W))
        val sepc = Input(UInt(64.W))
        val statusTvmField = Input(Bool())
        val statusTwField = Input(Bool())
        val statusTsrField = Input(Bool())

        val icacheInvalidateAll = Output(Bool())
        val icacheInvalidateAllOutfire = Input(Bool())
        val dcacheCleanAll = Output(Bool())
        val dcacheCleanAllOutfire = Input(Bool())
    })
    // M-mode by default on reset
    val privilegeReg = RegInit(3.U(2.W))
    val isFenceiCleanDcacheStage = RegInit(true.B)
    val opcode = io.miscInstr.bits.miscOpcode
    val params = io.miscInstr.bits.params

    val (csrOp, validCsrOp) = CsrOperation.safe(opcode.miscCsrFunct(1, 0))
    val (sysOp, validSysOp) = SystemOperation.safe(opcode.miscSysFunct)
    val (memOp, validMemOp) = MemoryOperation.safe(opcode.miscMemFunct)

    val validOp = io.miscInstr.valid && (validCsrOp || validSysOp || validMemOp)

    def emitCommit(): Unit = {
        io.commit.valid := true.B
        io.outfire := true.B
    }

    def emitException(eventPc: UInt, xtval: UInt, cause: UInt): Unit = {
        io.commit.valid := true.B
        io.commit.bits.discon := true.B
        io.commit.bits.disconType := DisconEventType.instrException
        io.commit.bits.eventPc := eventPc
        io.commit.bits.xtval := xtval
        io.commit.bits.cause := cause
        io.outfire := true.B
    }

    def emitIllegalInstr(): Unit = {
        emitException(params.pc, opcode.rawInstr, 2.U)
    }

    def emitSync(eventPc: UInt, disconType: DisconEventType.Type = DisconEventType.instrSync): Unit = {
        io.commit.valid := true.B
        io.commit.bits.discon := true.B
        io.commit.bits.disconType := disconType
        io.commit.bits.eventPc := eventPc
        io.outfire := true.B
    }

    def emitExcepReturn(retType: TrapReturnType.Type, epc: UInt): Unit = {
        io.commit.valid := true.B
        io.commit.bits.discon := true.B
        io.commit.bits.disconType := DisconEventType.excepReturn
        io.commit.bits.xretType := retType
        io.commit.bits.eventPc := epc
        io.outfire := true.B
    }

    io.csrio.readEn := false.B
    io.csrio.writeEn := false.B
    io.csrio.readAddr := 0.U
    io.csrio.writeAddr := 0.U
    io.csrio.writeData := 0.U

    io.outfire := false.B
    io.miscInstr.ready := io.commit.ready && ~validOp
    io.commit.valid := false.B
    io.commit.bits := new MISCCommit().zero
    io.commit.bits.robIndex := params.robIndex

    io.getPrivilege := privilegeReg
    io.icacheInvalidateAll := false.B
    io.dcacheCleanAll := false.B

    when(validOp) {
        when(validCsrOp) {
            val csrSrc1 = params.source1
            val csrAddr = params.source2
            val readEn = opcode.miscCsrFunct(3)
            val writeEn = opcode.miscCsrFunct(2)
            io.csrio.readEn := readEn
            io.csrio.writeEn := writeEn

            io.csrio.readAddr := csrAddr
            val csrData = io.csrio.readData

            io.csrio.writeAddr := csrAddr
            io.csrio.writeData := MuxLookup(csrOp, 0.U)(Seq(
                CsrOperation.csrrw -> csrSrc1,
                CsrOperation.csrrs -> (csrData | csrSrc1),
                CsrOperation.csrrc -> (csrData & ~csrSrc1)
            ))

            when(io.csrio.illegal) {
                emitIllegalInstr()
            }.otherwise {
                // We need to sync frontend to ensure the csr explicit access visible to instr fetch.
                val syncType = Mux(writeEn && csrAddr === "hb02".U, DisconEventType.instrSyncNoRet, DisconEventType.instrSync)
                emitSync(params.pc + 4.U, syncType)
                io.commit.bits.data := csrData
            }
        }

        when(validSysOp) {
            switch(sysOp) {
                is(SystemOperation.wfi) {
                    when(io.statusTwField && privilegeReg =/= 3.U) {
                        emitIllegalInstr()
                    }.otherwise {
                        emitCommit() // wfi treated as NOP
                    }
                }
                is(SystemOperation.ecall) {
                    emitException(params.pc, 0.U, MuxLookup(privilegeReg, 2.U)(Seq(
                        0.U -> 8.U, // U-mode ecall
                        1.U -> 9.U, // S-mode ecall
                        3.U -> 11.U // M-mode ecall
                    )))
                }
                is(SystemOperation.ebreak) {
                    emitException(params.pc, params.pc, 3.U)
                }
                is(SystemOperation.mret) {
                    when(privilegeReg === 3.U) {
                        emitExcepReturn(TrapReturnType.mret, io.mepc)
                    }.otherwise {
                        emitIllegalInstr()
                    }
                }
                is(SystemOperation.sret) {
                    when(privilegeReg >= Mux(io.statusTsrField, 3.U, 1.U)) {
                        emitExcepReturn(TrapReturnType.sret, io.sepc)
                    }.otherwise {
                        emitIllegalInstr()
                    }
                }
                is(SystemOperation.sfenceVma) {
                    // TODO TLB
                    when(io.statusTvmField && privilegeReg =/= 3.U) {
                        emitIllegalInstr()
                    }.otherwise {
                        emitSync(params.pc + 4.U)
                    }
                }
                is(SystemOperation.pmaFaultLowInstr) {
                    emitException(params.pc, params.pc, 1.U)
                }
                is(SystemOperation.pageFaultLowInstr) {
                    emitException(params.pc, params.pc, 12.U)
                }
                is(SystemOperation.pmaFaultHighInstr) {
                    emitException(params.pc, params.pc + 2.U, 1.U)
                }
                is(SystemOperation.pageFaultHighInstr) {
                    emitException(params.pc, params.pc + 2.U, 12.U)
                }
                is(SystemOperation.illegalInstr) {
                    emitIllegalInstr()
                }
            }
        }

        when(validMemOp) {
            switch(memOp) {
                // Currently there is no reordered load/store command so there is no need for fence instruction.
                is(MemoryOperation.fence) {
                    emitCommit()
                }
                is(MemoryOperation.fenceI) {
                    io.miscInstr.ready := false.B
                    when(isFenceiCleanDcacheStage) {
                        io.dcacheCleanAll := true.B
                        when(io.dcacheCleanAllOutfire) {
                            isFenceiCleanDcacheStage := false.B
                        }
                    }.otherwise {
                        io.icacheInvalidateAll := true.B
                        when(io.icacheInvalidateAllOutfire) {
                            emitSync(params.pc + 4.U)
                            isFenceiCleanDcacheStage := true.B
                        }
                    }
                }
            }
        }
    }

    when(io.setPrivilege.valid) {
        privilegeReg := io.setPrivilege.bits
    }
}
