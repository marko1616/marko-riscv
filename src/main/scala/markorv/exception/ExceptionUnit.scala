package markorv

import chisel3._
import chisel3.util._
import markorv.exception._

class ExceptionUnit extends Module {
    val io = IO(new Bundle {
        val outerInt = Input(Bool())
        val trap = Flipped(Decoupled(new TrapInfo))

        val fetchHlt = Output(Bool())

        val setException = Flipped(new ExceptionHandleInterface)

        val flush = Output(Bool())
        val pc = Input(UInt(64.W))
        val flushPc = Output(UInt(64.W))
        val privilege = Input(UInt(2.W))
        val setPrivilege = Decoupled(UInt(2.W))

        val exceptionRet = Input(Bool())
        val exceptionRetInfo = Input(new ExceptionState)

        val mstatus = Input(UInt(64.W))
        val mie = Input(UInt(64.W))
    })
    def doException(cause: UInt, isInt: Bool, instExceptionPc: UInt) = {
        io.setException.set := true.B
        intPending := false.B

        exceptionInfo.interruption := isInt
        exceptionInfo.causeCode := cause
        exceptionInfo.state.privilege := io.privilege
        when(isInt) {
            exceptionInfo.state.exceptionPc := io.pc
        }.otherwise {
            exceptionInfo.state.exceptionPc := instExceptionPc
        }

        io.flush := true.B
        io.flushPc := io.setException.exceptionHandler
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits := io.setException.privilege
    }

    val exceptionInfo = io.setException.exceptionInfo
    val intPending = RegInit(false.B)

    val mie = io.mstatus(3)
    val sie = io.mstatus(1)

    io.fetchHlt := false.B
    io.flush := false.B
    io.flushPc := 0.U
    io.setPrivilege.valid := false.B
    io.setPrivilege.bits := 0.U

    io.setException.set := false.B
    exceptionInfo.interruption := false.B
    exceptionInfo.causeCode := 0.U
    exceptionInfo.state.privilege := 0.U
    exceptionInfo.state.exceptionPc := 0.U

    io.trap.ready := true.B

    when(mie) {
        intPending := io.outerInt
    }.otherwise {
        intPending := false.B
    }
    when(intPending) {
        // TODO Interruption
        io.fetchHlt := true.B
    }

    when(io.exceptionRet) {
        io.flush := true.B
        io.flushPc := io.exceptionRetInfo.exceptionPc
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits := io.exceptionRetInfo.privilege
    }

    when(io.trap.valid) {
        doException(io.trap.bits.cause, false.B, io.trap.bits.xepc)
    }
}