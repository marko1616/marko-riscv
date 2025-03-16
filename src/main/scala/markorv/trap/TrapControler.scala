package markorv

import chisel3._
import chisel3.util._
import markorv.trap._

class TrapController extends Module {
    val io = IO(new Bundle {
        val outer_int = Input(Bool())
        val exceptions = Vec(1, Flipped(Decoupled(new ExceptionInfo)))

        val fetched = Input(UInt(4.W))
        val fetch_hlt = Output(Bool())

        val set_trap = Flipped(new TrapHandleInterface)

        val flush = Output(Bool())
        val pc = Input(UInt(64.W))
        val set_pc = Output(UInt(64.W))
        val privilege = Input(UInt(2.W))
        val set_privilege = Decoupled(UInt(2.W))

        val trap_ret = Input(Bool())
        val trap_ret_info = Input(new TrapState)

        val mstatus = Input(UInt(64.W))
        val mie = Input(UInt(64.W))
    })
    def do_trap(cause: UInt, is_int: Bool, inst_exc_pc: UInt) = {
        io.set_trap.set := true.B
        int_pending := false.B

        trap_info.interruption := is_int
        trap_info.cause_code := cause
        trap_info.state.privilege := io.privilege
        when(is_int) {
            trap_info.state.exception_pc := io.pc
        }.otherwise {
            trap_info.state.exception_pc := inst_exc_pc
        }

        io.flush := true.B
        io.set_pc := io.set_trap.trap_handler
        io.set_privilege.valid := true.B
        io.set_privilege.bits := io.set_trap.privilege
    }

    val trap_info = io.set_trap.trap_info
    val int_pending = RegInit(false.B)

    val mie = io.mstatus(3)
    val sie = io.mstatus(1)

    io.fetch_hlt := false.B
    io.flush := false.B
    io.set_pc := 0.U
    io.set_privilege.valid := false.B
    io.set_privilege.bits := 0.U

    io.set_trap.set := false.B
    trap_info.interruption := false.B
    trap_info.cause_code := 0.U
    trap_info.state.privilege := 0.U
    trap_info.state.exception_pc := 0.U

    for(i <- 0 until io.exceptions.length) {
        io.exceptions(i).ready := true.B
    }

    when(mie) {
        int_pending := io.outer_int
    }.otherwise {
        int_pending := false.B
    }
    when(int_pending) {
        io.fetch_hlt := true.B
        when(io.fetched === 0.U) {
            do_trap(11.U, true.B, 0.U)
        }
    }

    when(io.trap_ret) {
        io.flush := true.B
        io.set_pc := io.trap_ret_info.exception_pc
        io.set_privilege.valid := true.B
        io.set_privilege.bits := io.trap_ret_info.privilege
    }

    for(i <- 0 until io.exceptions.length) {
        when(io.exceptions(i).valid) {
            do_trap(io.exceptions(i).bits.cause, false.B, io.exceptions(i).bits.ret_addr)
        }
    }
}