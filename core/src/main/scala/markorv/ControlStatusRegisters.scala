package markorv

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.exception._
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
        val setException   = new ExceptionHandleInterface

        val privilege      = Input(UInt(2.W))

        val exceptionRet     = Input(Bool())
        val exceptionRetInfo = Output(new ExceptionState)

        val mstatus = Output(UInt(64.W))
        val mie     = Output(UInt(64.W))

        val meip = Input(Bool())
        val mtip = Input(Bool())
        val msip = Input(Bool())

        val time = Input(UInt(64.W))

        val retireEvent = Flipped(Valid(new RetireEvent))
    })

    // Unprivileged Counter/Timers (URO)
    val csrCycle   = new CSRCycle
    val csrTime    = new CSRTime(io.time)
    val csrInstret = new CSRInstRet

    // Machine Information Registers (MRO)
    val csrMvendorid  = new CSRMvendorID
    val csrMarchid    = new CSRMarchID
    val csrMimpid     = new CSRMimpID
    val csrMhartid    = new CSRMhartID
    val csrMconfigptr = new CSRMConfigPtr

    // Machine Trap Setup (MRW)
    val csrMstatus    = new CSRMstatus
    val csrMisa       = new CSRMisa
    val csrMedeleg    = new CSRMedeleg
    val csrMideleg    = new CSRMideleg
    val csrMie        = new CSRMie
    val csrMtvec      = new CSRMtvec
    val csrMcounteren = new CSRMcounteren

    // Machine Counter Setup (MRW)
    val csrMcountinhibit = new CSRMcountinhibit

    // Machine Trap Handling (MRW)
    val csrMscratch = new CSRMscratch
    val csrMepc     = new CSRMEPC
    val csrMcause   = new CSRMCause
    val csrMtval    = new CSRMtval
    val csrMip      = new CSRMip(io.msip, io.mtip, io.meip)

    // All CSRs for address dispatch
    val allCSRs: Seq[CSR] = Seq(
        csrCycle, csrTime, csrInstret,
        csrMvendorid, csrMarchid, csrMimpid, csrMhartid, csrMconfigptr,
        csrMstatus, csrMisa, csrMedeleg, csrMideleg,
        csrMie, csrMtvec, csrMcounteren,
        csrMcountinhibit,
        csrMscratch, csrMepc, csrMcause, csrMtval, csrMip
    )

    // Defaults
    io.csrio.readData := 0.U
    io.csrio.illegal  := false.B
    io.mstatus        := csrMstatus.read(csrMstatus.addr)
    io.mie            := csrMie.read(csrMie.addr)

    // Counter Logic
    csrCycle.field.reg := csrCycle.field.reg + 1.U
    when(io.retireEvent.valid && ~io.retireEvent.bits.isTrap) {
        csrInstret.field.reg := csrInstret.field.reg + 1.U
    }

    def addrPrivilege(addr: UInt): UInt = addr(9, 8)
    def isReadOnly(addr: UInt): Bool = addr(11, 10) === "b11".U

    def effectivePrivilege(addr: UInt): UInt = {
        val priv = Wire(UInt(2.W))
        priv := addrPrivilege(addr)
        when(addr === csrCycle.addr) {
            priv := Mux(csrMcounteren.cyField.read.asBool, "b00".U, "b11".U)
        }.elsewhen(addr === csrTime.addr) {
            priv := Mux(csrMcounteren.tmField.read.asBool, "b00".U, "b11".U)
        }.elsewhen(addr === csrInstret.addr) {
            priv := Mux(csrMcounteren.irField.read.asBool, "b00".U, "b11".U)
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
                    io.csrio.readData := csr.read(io.csrio.readAddr)
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
                    csr.write(io.csrio.writeAddr, io.csrio.writeData)
                }.otherwise {
                    io.csrio.illegal := true.B
                }
            }
        }
        when(!matched) {
            io.csrio.illegal := true.B
        }
    }

    // Exception Set
    val setException   = io.setException
    val exceptionInfo  = setException.exceptionInfo
    val set            = setException.set
    setException.exceptionHandler := 0.U
    setException.privilege        := 0.U

    when(set) {
        val privilege    = exceptionInfo.state.privilege
        val exceptionPc  = exceptionInfo.state.exceptionPc
        val interruption = exceptionInfo.interruption
        val causeCode    = exceptionInfo.causeCode

        val oldMIE = csrMstatus.mieField.reg
        val oldSIE = csrMstatus.sieField.reg

        csrMstatus.mpieField.reg := oldMIE
        csrMstatus.spieField.reg := oldSIE
        csrMstatus.mieField.reg  := 0.U
        csrMstatus.sieField.reg  := 0.U

        csrMstatus.mppField.reg := privilege

        csrMepc.field.reg := exceptionPc

        csrMcause.interruptField.reg := interruption
        csrMcause.codeField.reg      := causeCode

        val base = Cat(csrMtvec.baseField.reg, 0.U(2.W))
        val mode = csrMtvec.modeField.reg

        when(mode === 0.U) {
            // Direct
            setException.exceptionHandler := base
        }.elsewhen(mode === 1.U) {
            // Vectored
            setException.exceptionHandler := base + (causeCode << 2.U)
        }

        // TODO: trap delegate
        setException.privilege := 3.U
    }

    // Exception Return
    val retException = io.exceptionRetInfo
    val ret          = io.exceptionRet
    retException := new ExceptionState().zero

    when(ret) {
        val oldMPP  = csrMstatus.mppField.reg
        val oldMPIE = csrMstatus.mpieField.reg
        val oldSPIE = csrMstatus.spieField.reg

        retException.privilege    := oldMPP
        retException.exceptionPc  := csrMepc.field.reg

        csrMstatus.mieField.reg  := oldMPIE
        csrMstatus.sieField.reg  := oldSPIE

        csrMstatus.mpieField.reg := 1.U
        csrMstatus.spieField.reg := oldSPIE
        csrMstatus.mppField.reg  := 0.U
    }
}