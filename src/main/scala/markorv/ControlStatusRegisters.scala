package markorv

import chisel3._
import chisel3.util._
import markorv.trap._

class ControlStatusRegistersIO extends Bundle {
    val read_addr = Input(UInt(12.W))
    val read_en = Input(Bool())
    val write_addr = Input(UInt(12.W))
    val write_en = Input(Bool())

    val read_data = Output(UInt(64.W))
    val write_data = Input(UInt(64.W))

    val illegal = Output(Bool())
}

class ControlStatusRegisters extends Module {
    val io = IO(new Bundle {
        val csrio = new ControlStatusRegistersIO
        val set_trap = new TrapHandleInterface

        val privilege = Input(UInt(2.W))

        val trap_ret = Input(Bool())
        val trap_ret_info = Output(new TrapState)

        val mstatus = Output(UInt(64.W))
        val mie = Output(UInt(64.W))

        val instret = Input(UInt(1.W))
        val time = Input(UInt(64.W))
    })
    // While read write simultaneously shuould return old value.

    def read_csr(data: UInt, required_privilege: UInt) = {
        when(required_privilege <= io.privilege) {
            io.csrio.read_data := data
        }.otherwise {
            io.csrio.illegal := true.B
        }
    }

    def write_csr(target: Data, data: UInt, required_privilege: UInt) = {
        when(required_privilege <= io.privilege) {
            target := data
        }.otherwise {
            io.csrio.illegal := true.B
        }
    }

    // Unprivileged Counter/Timers(URO)
    val CYCLE_ADDR = "hc00".U(12.W)
    val TIME_ADDR = "hc01".U(12.W)
    val INSTRET_ADDR = "hc02".U(12.W)

    // Machine infomations(MRO).
    val MVENDORID_ADDR = "hf11".U(12.W)
    val MARCHID_ADDR = "hf12".U(12.W)
    val MIMPID_ADDR = "hf13".U(12.W)
    val MHARTID_ADDR = "hf14".U(12.W)
    val MCONFIGPTR_ADDR = "hf15".U(12.W)

    // Machine trap setup(MRW).
    val MSTATUS_ADDR = "h300".U(12.W)
    val MISA_ADDR = "h301".U(12.W)
    val MEDELEG_ADDR = "h302".U(12.W)
    val MIDELEG_ADDR = "h303".U(12.W)
    val MIE_ADDR = "h304".U(12.W)
    val MTVEC_ADDR = "h305".U(12.W)
    val MCOUNTEREN_ADDR = "h306".U(12.W)

    // Machine trap handling
    val MSCRATCH_ADDR = "h340".U(12.W)
    val MEPC_ADDR = "h341".U(12.W)
    val MCAUSE_ADDR = "h342".U(12.W)
    val MTVAL_ADDR = "h343".U(12.W)
    val MIP_ADDR = "h344".U(12.W)

    val cycle = RegInit(0.U(64.W))
    val time = WireInit(0.U(64.W))
    val instret = RegInit(0.U(64.W))

    val mstatus = RegInit(0.U(64.W))
    val misa = RegInit("h8000000000000100".U(64.W))
    val medeleg = RegInit(0.U(64.W))
    val mideleg = RegInit(0.U(64.W))
    val mie = RegInit(0.U(64.W))
    val mtvec = RegInit(0.U(64.W))

    val mscratch = RegInit(0.U(64.W))
    val mepc = RegInit(0.U(64.W))
    val mcause_interruption = RegInit(false.B)
    val mcause_code = RegInit(0.U(6.W))
    val mtval = RegInit(0.U(64.W))

    val mcounteren = RegInit(0.U(32.W))

    io.csrio.read_data := 0.U
    io.csrio.illegal := false.B
    io.mstatus := mstatus
    io.mie := mie

    cycle := cycle + 1.U
    time := io.time
    instret := instret + io.instret

    when(io.csrio.read_en) {
        when(io.csrio.read_addr === CYCLE_ADDR){
            // TODO S mode
            read_csr(cycle, Mux(mcounteren(0), "b00".U, "b11".U))
        }.elsewhen(io.csrio.read_addr === TIME_ADDR) {
            // TODO S mode
            read_csr(time, Mux(mcounteren(1), "b00".U, "b11".U))
        }.elsewhen(io.csrio.read_addr === INSTRET_ADDR) {
            // TODO S mode
            read_csr(instret, Mux(mcounteren(2), "b00".U, "b11".U))
        }.elsewhen(io.csrio.read_addr === MVENDORID_ADDR) {
            // In development.
            read_csr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.read_addr === MARCHID_ADDR) {
            // In development.
            read_csr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.read_addr === MIMPID_ADDR) {
            // In development.
            read_csr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.read_addr === MHARTID_ADDR) {
            // 0x0
            read_csr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.read_addr === MCONFIGPTR_ADDR) {
            // TODO machine config
            read_csr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.read_addr === MSTATUS_ADDR) {
            read_csr(mstatus, "b11".U)
        }.elsewhen(io.csrio.read_addr === MISA_ADDR) {
            // RV64I
            read_csr(misa, "b11".U)
        }.elsewhen(io.csrio.read_addr === MEDELEG_ADDR) {
            read_csr(medeleg, "b11".U)
        }.elsewhen(io.csrio.read_addr === MIDELEG_ADDR) {
            read_csr(mideleg, "b11".U)
        }.elsewhen(io.csrio.read_addr === MIE_ADDR) {
            read_csr(mie, "b11".U)
        }.elsewhen(io.csrio.read_addr === MTVEC_ADDR) {
            read_csr(mtvec, "b11".U)
        }.elsewhen(io.csrio.read_addr === MCOUNTEREN_ADDR) {
            read_csr(mcounteren, "b11".U)
        }.elsewhen(io.csrio.read_addr === MSCRATCH_ADDR) {
            read_csr(mscratch, "b11".U)
        }.elsewhen(io.csrio.read_addr === MEPC_ADDR) {
            read_csr(mepc, "b11".U)
        }.elsewhen(io.csrio.read_addr === MCAUSE_ADDR) {
            read_csr(Cat(mcause_interruption, 0.U(57.W), mcause_code), "b11".U)
        }.elsewhen(io.csrio.read_addr === MTVAL_ADDR) {
            read_csr(mtval, "b11".U)
        }.elsewhen(io.csrio.read_addr === MIP_ADDR) {
            // TODO
        }
    }

    when(io.csrio.write_en) {
        when(io.csrio.write_addr === MSTATUS_ADDR) {
            // write mask shown that which fields is implemented.
            val write_mask = "h00000000000000aa".U
            write_csr(mstatus, write_mask & io.csrio.write_data, "b11".U)
        }.elsewhen(io.csrio.write_addr === MISA_ADDR) {
            // Can't write this to switch func for now.
        }.elsewhen(io.csrio.write_addr === MEDELEG_ADDR) {
            // TODO S mode
            write_csr(medeleg, io.csrio.write_data, "b11".U)
        }.elsewhen(io.csrio.write_addr === MIDELEG_ADDR) {
            // TODO S mode
            write_csr(mideleg, io.csrio.write_data, "b11".U)
        }.elsewhen(io.csrio.write_addr === MIE_ADDR) {
            write_csr(mie, io.csrio.write_data, "b11".U)
        }.elsewhen(io.csrio.write_addr === MTVEC_ADDR) {
            // mtvec mode >= 2 is Reserved
            val write_mask = "hfffffffffffffffd".U
            write_csr(mtvec, write_mask & io.csrio.write_data, "b11".U)
        }.elsewhen(io.csrio.write_addr === MCOUNTEREN_ADDR) {
            write_csr(mcounteren, io.csrio.write_data, "b11".U)
        }.elsewhen(io.csrio.write_addr === MSCRATCH_ADDR) {
            write_csr(mscratch, io.csrio.write_data, "b11".U)
        }.elsewhen(io.csrio.write_addr === MEPC_ADDR) {
            write_csr(mepc, io.csrio.write_data, "b11".U)
        }.elsewhen(io.csrio.write_addr === MCAUSE_ADDR) {
            write_csr(mcause_interruption, io.csrio.write_data(63) === 1.U, "b11".U)
            write_csr(mcause_code, io.csrio.write_data(5, 0), "b11".U)
        }.elsewhen(io.csrio.write_addr === MTVAL_ADDR) {
            write_csr(mtval, io.csrio.write_data, "b11".U)
        }.elsewhen(io.csrio.write_addr === MIP_ADDR) {
            // TODO
        }
    }

    val set_trap = io.set_trap
    val trap_info = set_trap.trap_info
    val set = set_trap.set
    set_trap.trap_handler := 0.U
    set_trap.privilege := 0.U

    when(set) {
        val privilege = trap_info.state.privilege
        val exception_pc = trap_info.state.exception_pc
        val interruption = trap_info.interruption
        val cause_code = trap_info.cause_code

        val int_disable = "hfffffffffffffff5".U
        val sie = mstatus(1)
        val mie = mstatus(3)
        // xie to xpie and clear xie.
        mstatus := int_disable & Cat(mstatus(63, 13), privilege, mstatus(10, 8), mie, mstatus(6), sie, mstatus(4,0))
        mepc := exception_pc

        mcause_interruption := interruption
        mcause_code := cause_code

        when(mtvec(1,0) === 0.U) {
            set_trap.trap_handler := Cat(mtvec(63,2),0.U(2.W))
        }.elsewhen(mtvec(1,0) === 1.U) {
            set_trap.trap_handler := Cat(mtvec(63,2),0.U(2.W)) + 4.U*cause_code
        }
        // TODO trap delegate
        set_trap.privilege := 3.U
    }

    val ret_exception = io.trap_ret_info
    val ret = io.trap_ret
    ret_exception := 0.U.asTypeOf(new TrapState)

    when(ret) {
        val privilege = mstatus(12, 11)
        val exception_pc = mepc

        val spie = mstatus(5)
        val mpie = mstatus(7)

        ret_exception.privilege := privilege
        ret_exception.exception_pc := mepc
        mstatus := Cat(mstatus(63,4), mpie, mstatus(2), spie, mstatus(0))
    }
}
