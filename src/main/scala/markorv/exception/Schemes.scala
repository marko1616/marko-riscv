package markorv.exception

import chisel3._
import chisel3.util._

class ExceptionState extends Bundle {
    val privilege = UInt(2.W)
    val exceptionPc = UInt(64.W)
}

class ExceptionInfo extends Bundle {
    val interruption = Bool()
    val causeCode = UInt(6.W)
    val state = new ExceptionState
}

class ExceptionHandleInterface extends Bundle {
    val set = Input(Bool())
    val exceptionInfo = Input(new ExceptionInfo)
    val exceptionHandler = Output(UInt(64.W))
    val privilege = Output(UInt(2.W))
}

class TrapInfo extends Bundle {
    val cause = UInt(6.W)
    val xepc = UInt(64.W)
}