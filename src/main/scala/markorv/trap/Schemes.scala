package markorv.trap

import chisel3._
import chisel3.util._

class TrapState extends Bundle {
    val privilege = UInt(2.W)
    val exception_pc = UInt(64.W)
}

class TrapInfo extends Bundle {
    val interruption = Bool()
    val cause_code = UInt(6.W)
    val state = new TrapState
}

class TrapHandleInterface extends Bundle {
    val set = Input(Bool())
    val trap_info = Input(new TrapInfo)
    val trap_handler = Output(UInt(64.W))
    val privilege = Output(UInt(2.W))
}

class ExceptionInfo extends Bundle {
    val cause = UInt(6.W)
    val ret_addr = UInt(64.W)
}

class CoreLocalInterruptInterface extends {
    
}
