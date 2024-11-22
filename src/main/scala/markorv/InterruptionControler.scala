package markorv

import chisel3._
import chisel3.util._

class InterruptionControler extends Module {
    val io = IO(new Bundle {
        val outer_int = Input(Bool())
        val outer_int_outfire = Output(Bool())

        val fetched = Input(UInt(4.W))
        val fetch_hlt = Output(Bool())

        val set_exception = Flipped(new ExceptionHandleIO)

        val flush = Output(Bool())
        val pc = Input(UInt(64.W))
        val set_pc = Output(UInt(64.W))
        val privilege = Input(UInt(2.W))
        val set_privilege = Output(UInt(2.W))

        val ret = Input(Bool())
        val ret_exception = Input(new ExceptionState)
    })
    val exception_info = io.set_exception.exception_info
    val int_pending = RegInit(false.B)

    io.outer_int_outfire := false.B   
    io.fetch_hlt := false.B
    io.flush := false.B
    io.set_pc := 0.U
    io.set_privilege := 0.U

    io.set_exception.set := false.B
    exception_info.interruption := false.B
    exception_info.cause_code := 0.U
    exception_info.state.privilege := 0.U
    exception_info.state.exception_pc := 0.U

    int_pending := io.outer_int
    when(int_pending) {
        io.fetch_hlt := true.B
        when(io.fetched === 0.U) {
            io.set_exception.set := true.B
            io.outer_int_outfire := true.B
            exception_info.interruption := true.B
            int_pending := false.B

            // Machine external interrupt.
            exception_info.cause_code := 11.U
            exception_info.state.privilege := io.privilege
            exception_info.state.exception_pc := io.pc

            io.flush := true.B
            io.set_pc := io.set_exception.exception_handler
        }
    }

    when(io.ret) {
        io.flush := true.B
        io.set_pc := io.ret_exception.exception_pc
        io.set_privilege := io.ret_exception.privilege
    }
}