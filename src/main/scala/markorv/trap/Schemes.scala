package markorv.trap

import chisel3._
import chisel3.util._

class TrapState extends Bundle {
    val privilege = UInt(2.W)
    val exceptionPc = UInt(64.W)
}

class TrapInfo extends Bundle {
    val interruption = Bool()
    val causeCode = UInt(6.W)
    val state = new TrapState
}

class TrapHandleInterface extends Bundle {
    val set = Input(Bool())
    val trapInfo = Input(new TrapInfo)
    val trapHandler = Output(UInt(64.W))
    val privilege = Output(UInt(2.W))
}

class ExceptionInfo extends Bundle {
    val cause = UInt(6.W)
    val retAddr = UInt(64.W)
}