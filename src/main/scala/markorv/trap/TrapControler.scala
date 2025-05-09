package markorv

import chisel3._
import chisel3.util._
import markorv.trap._

class TrapController extends Module {
    val io = IO(new Bundle {
        val outerInt = Input(Bool())
        val exceptions = Vec(1, Flipped(Decoupled(new ExceptionInfo)))

        val fetched = Input(UInt(4.W))
        val fetchHlt = Output(Bool())

        val setTrap = Flipped(new TrapHandleInterface)

        val flush = Output(Bool())
        val pc = Input(UInt(64.W))
        val setPc = Output(UInt(64.W))
        val privilege = Input(UInt(2.W))
        val setPrivilege = Decoupled(UInt(2.W))

        val trapRet = Input(Bool())
        val trapRetInfo = Input(new TrapState)

        val mstatus = Input(UInt(64.W))
        val mie = Input(UInt(64.W))
    })
    def doTrap(cause: UInt, isInt: Bool, instExceptionPc: UInt) = {
        io.setTrap.set := true.B
        intPending := false.B

        trapInfo.interruption := isInt
        trapInfo.causeCode := cause
        trapInfo.state.privilege := io.privilege
        when(isInt) {
            trapInfo.state.exceptionPc := io.pc
        }.otherwise {
            trapInfo.state.exceptionPc := instExceptionPc
        }

        io.flush := true.B
        io.setPc := io.setTrap.trapHandler
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits := io.setTrap.privilege
    }

    val trapInfo = io.setTrap.trapInfo
    val intPending = RegInit(false.B)

    val mie = io.mstatus(3)
    val sie = io.mstatus(1)

    io.fetchHlt := false.B
    io.flush := false.B
    io.setPc := 0.U
    io.setPrivilege.valid := false.B
    io.setPrivilege.bits := 0.U

    io.setTrap.set := false.B
    trapInfo.interruption := false.B
    trapInfo.causeCode := 0.U
    trapInfo.state.privilege := 0.U
    trapInfo.state.exceptionPc := 0.U

    for(i <- 0 until io.exceptions.length) {
        io.exceptions(i).ready := true.B
    }

    when(mie) {
        intPending := io.outerInt
    }.otherwise {
        intPending := false.B
    }
    when(intPending) {
        io.fetchHlt := true.B
        when(io.fetched === 0.U) {
            doTrap(11.U, true.B, 0.U)
        }
    }

    when(io.trapRet) {
        io.flush := true.B
        io.setPc := io.trapRetInfo.exceptionPc
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits := io.trapRetInfo.privilege
    }

    for(i <- 0 until io.exceptions.length) {
        when(io.exceptions(i).valid) {
            doTrap(io.exceptions(i).bits.cause, false.B, io.exceptions(i).bits.retAddr)
        }
    }
}