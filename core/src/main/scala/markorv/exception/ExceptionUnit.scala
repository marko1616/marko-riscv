package markorv

import chisel3._
import chisel3.util._
import markorv.exception._

class ExceptionUnit extends Module {
    val io = IO(new Bundle {
        // Interrupt signals
        // ========================
        val meip = Input(Bool())
        val mtip = Input(Bool())
        val msip = Input(Bool())

        // Exception signals
        // ========================
        val trap = Flipped(Decoupled(new TrapInfo))
        val setException = Flipped(new ExceptionHandleInterface)
        val exceptionRet = Input(Bool())
        val exceptionRetInfo = Input(new ExceptionState)

        // Flush control signals
        // ========================
        val robEmpty = Input(Bool())
        val flush = Output(Bool())
        val flushPc = Output(UInt(64.W))
        val pc = Input(UInt(64.W))

        // Privilege control signals
        // ========================
        val privilege = Input(UInt(2.W))
        val setPrivilege = Decoupled(UInt(2.W))

        // CSR status signals
        // ========================
        val mstatus = Input(UInt(64.W))
        val mie = Input(UInt(64.W))

        // Pipeline control signals
        // ========================
        val interruptHlt = Output(Bool())
    })
    val interruptCode = WireInit(0.U(4.W))
    val exceptionInfo = io.setException.exceptionInfo

    val globalMie = io.mstatus(3)

    io.interruptHlt := false.B
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

    when(globalMie) {
        when(io.meip && io.mie(11)) {
            io.interruptHlt := true.B
            interruptCode := 11.U
        }.elsewhen(io.mtip && io.mie(7)) {
            io.interruptHlt := true.B
            interruptCode := 7.U
        }.elsewhen(io.msip && io.mie(3)) {
            io.interruptHlt := true.B
            interruptCode := 3.U
        }
    }

    when(io.exceptionRet) {
        io.flush := true.B
        io.flushPc := io.exceptionRetInfo.exceptionPc
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits := io.exceptionRetInfo.privilege
    }

    when(interruptCode =/= 0.U && io.robEmpty) {
        io.setException.set := true.B

        exceptionInfo.interruption := true.B
        exceptionInfo.causeCode := interruptCode
        exceptionInfo.state.privilege := io.privilege
        exceptionInfo.state.exceptionPc := io.trap.bits.xepc

        io.flush := true.B
        io.flushPc := io.setException.exceptionHandler
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits := io.setException.privilege
    }

    when(io.trap.valid) {
        io.setException.set := true.B

        exceptionInfo.interruption := false.B
        exceptionInfo.causeCode := io.trap.bits.cause
        exceptionInfo.state.privilege := io.privilege
        exceptionInfo.state.exceptionPc := io.trap.bits.xepc

        io.flush := true.B
        io.flushPc := io.setException.exceptionHandler
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits := io.setException.privilege
    }
}