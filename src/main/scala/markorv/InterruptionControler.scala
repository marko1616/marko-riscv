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

        val ecall = Flipped(Decoupled(UInt(64.W)))
        val ebreak = Flipped(Decoupled(UInt(64.W)))

        val ret = Input(Bool())
        val ret_exception = Input(new ExceptionState)

        val mstatus = Input(UInt(64.W))
        val mie = Input(UInt(64.W))
    })
    def do_exception(cause: UInt, is_int: Bool, inst_exc_pc: UInt) = {
        io.set_exception.set := true.B
        io.outer_int_outfire := true.B
        int_pending := false.B

        exception_info.interruption := is_int
        exception_info.cause_code := cause
        exception_info.state.privilege := io.privilege
        when(is_int) {
            exception_info.state.exception_pc := io.pc
        }.otherwise {
            exception_info.state.exception_pc := inst_exc_pc
        }

        io.flush := true.B
        io.set_pc := io.set_exception.exception_handler
    }

    val exception_info = io.set_exception.exception_info
    val int_pending = RegInit(false.B)

    val mie = io.mstatus(3)
    val sie = io.mstatus(1)

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

    io.ecall.ready := true.B
    io.ebreak.ready := true.B

    when(mie) {
        int_pending := io.outer_int
    }.otherwise {
        int_pending := false.B
    }
    when(int_pending) {
        io.fetch_hlt := true.B
        when(io.fetched === 0.U) {
            do_exception(11.U, true.B, 0.U)
        }
    }

    when(io.ret) {
        io.flush := true.B
        io.set_pc := io.ret_exception.exception_pc
        io.set_privilege := io.ret_exception.privilege
    }

    when(io.ecall.valid) {
        do_exception(11.U, false.B, io.ecall.bits)
    }

    when(io.ebreak.valid) {
        do_exception(3.U, false.B, io.ebreak.bits)
    }
}