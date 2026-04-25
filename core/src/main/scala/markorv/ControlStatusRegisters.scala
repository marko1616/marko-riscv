package markorv

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.trap._
import markorv.bus.MmuMode
import markorv.manage.RetireEvent

class ControlStatusRegistersIO extends Bundle {
    val readAddr  = Input(UInt(12.W))
    val readEn    = Input(Bool())
    val writeAddr = Input(UInt(12.W))
    val writeEn   = Input(Bool())

    val readData  = Output(UInt(64.W))
    val writeData = Input(UInt(64.W))

    val illegal   = Output(Bool())
}

class ControlStatusRegisters(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val csrio          = new ControlStatusRegistersIO
        val handleTrap     = new TrapHandleInterface

        val privilege      = Input(UInt(2.W))

        val satpModeField = Output(UInt(4.W))
        val statusMppField = Output(UInt(2.W))
        val statusMprvField = Output(Bool())
        val statusSumField = Output(Bool())
        val statusMxrField = Output(Bool())
        val statusTvmField = Output(Bool())
        val statusTwField = Output(Bool())
        val statusTsrField = Output(Bool())

        val trapRet        = Flipped(Valid(new TrapReturnType.Type))
        val trapRetInfo    = Output(new TrapState)

        val mstatus = Output(UInt(64.W))
        val mie     = Output(UInt(64.W))
        val medeleg = Output(UInt(64.W))
        val mideleg = Output(UInt(64.W))
        val sie     = Output(UInt(64.W))

        val meip = Input(Bool())
        val mtip = Input(Bool())
        val msip = Input(Bool())
        val seip = Output(Bool())
        val stip = Output(Bool())
        val ssip = Output(Bool())

        val mepc = Output(UInt(64.W))
        val sepc = Output(UInt(64.W))

        val ppn     = Output(UInt(44.W))
        val asid    = Output(UInt(c.asidWidth.W))

        val time = Input(UInt(64.W))

        val retireEvent = Flipped(Valid(new RetireEvent))
    })

    val seip = WireInit(false.B)
    val stip = WireInit(false.B)
    val ssip = WireInit(false.B)

    // Machine Information Registers (MRO)
    val csrMvendorid  = new CSRMvendorID
    val csrMarchid    = new CSRMarchID
    val csrMimpid     = new CSRMimpID
    val csrMhartid    = new CSRMhartID
    val CSRMconfigPtr = new CSRMconfigPtr

    // Machine Trap Setup (MRW)
    val csrMstatus    = new CSRMstatus
    val csrMisa       = new CSRMisa
    val csrMedeleg    = new CSRMedeleg
    val csrMideleg    = new CSRMideleg
    val csrMie        = new CSRMie
    val csrMtvec      = new CSRMtvec
    val csrMcounteren = new CSRMcounteren

    // Machine Counter/Timers (MRW)
    val csrMcycle   = new CSRMcycle
    val csrMinstret = new CSRMinstRet

    // Machine Counter Setup (MRW)
    val csrMcountinhibit = new CSRMcountinhibit

    // Machine Trap Handling (MRW)
    val csrMscratch = new CSRMscratch
    val csrMepc     = new CSRMepc
    val csrMcause   = new CSRMcause
    val csrMtval    = new CSRMtval
    // TODO PLIC Supervisor context
    val csrMip      = new CSRMip(io.msip, io.mtip, io.meip, stip, false.B)

    // Machine Configuration(MRW)
    val csrMenvcfg = new CSRMenvcfg

    // Supervisor Trap Setup (SRW)
    val csrSstatus = new CSRSstatus(csrMstatus)
    val csrSie     = new CSRSie(csrMie)
    val csrStvec   = new CSRStvec
    val csrScounteren = new CSRScounteren

    // Supervisor Configuration (SRW)
    val csrSenvcfg = new CSRSenvcfg
    
    // Supervisor Trap Handling (SRW)
    val csrSscratch = new CSRSscratch
    val csrSepc     = new CSRSepc
    val csrScause   = new CSRScause
    val csrStval    = new CSRStval
    val csrSip      = new CSRSip(csrMip)

    // Supervisor Protection and Translation (SRW)
    val csrSatp = new CSRSatp(c.asidWidth)

    // Supervisor Timer Compare (SRW)
    val csrStimecmp = new CSRStimecmp

    // Unprivileged Counter/Timers (URO)
    val csrCycle   = new CSRCycle(csrMcycle.read)
    val csrTime    = new CSRTime(io.time)
    val csrInstret = new CSRInstRet(csrMinstret.read)

    // All CSRs for address dispatch
    val allCSRs: Seq[CSR] = Seq(
        csrMvendorid, csrMarchid, csrMimpid, csrMhartid, CSRMconfigPtr,
        csrMstatus, csrMisa, csrMedeleg, csrMideleg,
        csrMie, csrMtvec, 
        csrMcycle, csrMinstret,
        csrMcounteren,
        csrMcountinhibit,
        csrMscratch, csrMepc, csrMcause, csrMtval, csrMip,
        csrMenvcfg,
        csrSstatus, csrSie, csrStvec, csrScounteren,
        csrSenvcfg,
        csrSscratch, csrSepc, csrScause, csrStval, csrSip,
        csrSatp,
        csrStimecmp,
        csrCycle, csrTime, csrInstret,
    )

    // Defaults
    io.csrio.readData := 0.U
    io.csrio.illegal  := false.B
    io.mstatus        := csrMstatus.read
    io.mie            := csrMie.read
    io.medeleg        := csrMedeleg.read
    io.mideleg        := csrMideleg.read
    io.sie            := csrSie.read
    io.mepc           := csrMepc.read
    io.sepc           := csrSepc.read
    io.ppn            := csrSatp.ppnField.read
    io.asid           := csrSatp.asidField.read

    io.satpModeField := csrSatp.modeField.read
    io.statusMppField := csrMstatus.mppField.read
    io.statusMprvField := csrMstatus.mprvField.read
    io.statusSumField := csrMstatus.sumField.read
    io.statusMxrField := csrMstatus.mxrField.read

    io.statusTvmField := csrMstatus.tvmField.read
    io.statusTwField := csrMstatus.twField.read
    io.statusTsrField := csrMstatus.tsrField.read

    // Counter Logic
    csrMcycle.field.reg := csrMcycle.field.reg + 1.U
    when(io.retireEvent.valid && io.retireEvent.bits.incInstRet) {
        csrMinstret.field.reg := csrMinstret.field.reg + 1.U
    }

    // Supervisor interrupt logic
    seip := csrSip.seipField.read
    stip := (csrStimecmp.cmpField.read <= io.time) && csrMenvcfg.stceField.read.asBool
    ssip := csrSip.ssipField.read

    io.seip := seip
    io.stip := stip
    io.ssip := ssip

    def addrPrivilege(addr: UInt): UInt = addr(9, 8)
    def isReadOnly(addr: UInt): Bool = addr(11, 10) === "b11".U

    def effectivePrivilege(addr: UInt): UInt = {
        val priv = Wire(UInt(2.W))
        priv := addrPrivilege(addr)
        when(addr === csrCycle.addr) {
            priv := MuxLookup(csrScounteren.cyField.read ## csrMcounteren.cyField.read, "b11".U)(Seq(
                "b00".U -> "b11".U,
                "b01".U -> "b01".U,
                "b10".U -> "b11".U,
                "b11".U -> "b00".U
            ))
        }.elsewhen(addr === csrTime.addr) {
            priv := MuxLookup(csrScounteren.tmField.read ## csrMcounteren.tmField.read, "b11".U)(Seq(
                "b00".U -> "b11".U,
                "b01".U -> "b01".U,
                "b10".U -> "b11".U,
                "b11".U -> "b00".U
            ))
        }.elsewhen(addr === csrInstret.addr) {
            priv := MuxLookup(csrScounteren.irField.read ## csrMcounteren.irField.read, "b11".U)(Seq(
                "b00".U -> "b11".U,
                "b01".U -> "b01".U,
                "b10".U -> "b11".U,
                "b11".U -> "b00".U
            ))
        }.elsewhen(addr === csrStimecmp.addr) {
            priv := Mux(csrMcounteren.tmField.read.asBool && csrMenvcfg.stceField.read.asBool, 1.U, 3.U)
        }.elsewhen(addr === csrSatp.addr) {
            priv := Mux(csrMstatus.tvmField.read.asBool, 3.U, 1.U)
        }
        priv
    }

    // Read dispatch
    when(io.csrio.readEn) {
        val matched = WireDefault(false.B)
        for (csr <- allCSRs) {
            when(io.csrio.readAddr === csr.addr) {
                matched := true.B
                when(effectivePrivilege(io.csrio.readAddr) <= io.privilege) {
                    io.csrio.readData := csr.read
                }.otherwise {
                    io.csrio.illegal := true.B
                }
            }
        }
        when(!matched) {
            io.csrio.illegal := true.B
        }
    }

    // Write dispatch
    when(io.csrio.writeEn) {
        val matched = WireDefault(false.B)
        for (csr <- allCSRs) {
            when(io.csrio.writeAddr === csr.addr) {
                matched := true.B
                when(isReadOnly(io.csrio.writeAddr)) {
                    // Writes to read-only CSRs raise illegal
                    io.csrio.illegal := true.B
                }.elsewhen(effectivePrivilege(io.csrio.writeAddr) <= io.privilege) {
                    csr.write(io.csrio.writeData)
                }.otherwise {
                    io.csrio.illegal := true.B
                }
            }
        }
        when(!matched) {
            io.csrio.illegal := true.B
        }
    }

    // Trap Set
    val handleTrap = io.handleTrap
    val trapInfo   = handleTrap.trapInfo
    val set        = handleTrap.set
    handleTrap.trapHandler := 0.U
    handleTrap.privilege   := 0.U

    when(set) {
        val privilege    = trapInfo.state.privilege
        val trapPc       = trapInfo.state.trapPc
        val xtval        = trapInfo.state.xtval
        val interruption = trapInfo.interruption
        val causeCode    = trapInfo.causeCode

        val doTrapDeleg = ~interruption && csrMedeleg.read(causeCode(5,0)) && (privilege <= 1.U)
        val doIntDeleg  = interruption && csrMideleg.read(causeCode(5,0)) && (privilege <= 1.U)
        val doDeleg = doTrapDeleg || doIntDeleg

        val oldMIE = csrMstatus.mieField.read
        val oldSIE = csrMstatus.sieField.read

        when(doDeleg) {
            // Delegated to S-mode
            csrSstatus.spieField.write(oldSIE)
            csrSstatus.sieField.write(0.U)

            csrSstatus.sppField.write(privilege(0))

            csrSepc.write(trapPc)

            csrScause.interruptField.write(interruption)
            csrScause.codeField.write(causeCode)

            val base = Cat(csrStvec.baseField.read, 0.U(2.W))
            val mode = csrStvec.modeField.read

            when(mode === 0.U) {
                // Direct
                handleTrap.trapHandler := base
            }.elsewhen(mode === 1.U) {
                // Vectored
                handleTrap.trapHandler := Mux(interruption, base + (causeCode << 2.U), base)
            }

            csrStval.stvalField.write(xtval)
            handleTrap.privilege := 1.U
        }.otherwise {
            // Handled in M-mode
            csrMstatus.mpieField.write(oldMIE)
            csrMstatus.mieField.write(0.U)

            csrMstatus.mppField.write(privilege)

            csrMepc.write(trapPc)

            csrMcause.interruptField.write(interruption)
            csrMcause.codeField.write(causeCode)

            val base = Cat(csrMtvec.baseField.read, 0.U(2.W))
            val mode = csrMtvec.modeField.read

            when(mode === 0.U) {
                // Direct
                handleTrap.trapHandler := base
            }.elsewhen(mode === 1.U) {
                // Vectored
                handleTrap.trapHandler := Mux(interruption, base + (causeCode << 2.U), base)
            }

            csrMtval.mtvalField.write(xtval)
            handleTrap.privilege := 3.U
        }
    }

    // Trap Return
    val retTrap = io.trapRetInfo
    val ret     = io.trapRet.valid
    retTrap := new TrapState().zero

    when(ret) {
        val targetPriv = WireInit(0.U)
        when(io.trapRet.bits === TrapReturnType.mret) {
            val oldMPP  = csrMstatus.mppField.read
            val oldMPIE = csrMstatus.mpieField.read

            targetPriv := oldMPP
            retTrap.trapPc    := csrMepc.read

            csrMstatus.mieField.write(oldMPIE)

            csrMstatus.mpieField.write(1.U)
            csrMstatus.mppField.write(0.U)
        }.elsewhen(io.trapRet.bits === TrapReturnType.sret) {
            val oldSPP  = csrSstatus.sppField.read
            val oldSPIE = csrSstatus.spieField.read

            targetPriv := 0.U(1.W) ## oldSPP
            retTrap.trapPc    := csrSepc.read

            csrSstatus.sieField.write(oldSPIE)

            csrSstatus.spieField.write(1.U)
            csrSstatus.sppField.write(0.U)
        }

        when(targetPriv =/= 3.U) {
            csrMstatus.mprvField.write(0.U)
        }

        retTrap.privilege := targetPriv
    }
}