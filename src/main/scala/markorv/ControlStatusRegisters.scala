package markorv

import chisel3._
import chisel3.util._
import markorv.trap._

class ControlStatusRegistersIO extends Bundle {
    val readAddr = Input(UInt(12.W))
    val readEn = Input(Bool())
    val writeAddr = Input(UInt(12.W))
    val writeEn = Input(Bool())

    val readData = Output(UInt(64.W))
    val writeData = Input(UInt(64.W))

    val illegal = Output(Bool())
}

class ControlStatusRegisters extends Module {
    val io = IO(new Bundle {
        val csrio = new ControlStatusRegistersIO
        val setTrap = new TrapHandleInterface

        val privilege = Input(UInt(2.W))

        val trapRet = Input(Bool())
        val trapRetInfo = Output(new TrapState)

        val mstatus = Output(UInt(64.W))
        val mie = Output(UInt(64.W))

        val instret = Input(UInt(1.W))
        val time = Input(UInt(64.W))
    })
    // While read write simultaneously shuould return old value.

    def readCsr(data: UInt, requiredPrivilege: UInt) = {
        when(requiredPrivilege <= io.privilege) {
            io.csrio.readData := data
        }.otherwise {
            io.csrio.illegal := true.B
        }
    }

    def writeCsr(target: Data, data: UInt, requiredPrivilege: UInt) = {
        when(requiredPrivilege <= io.privilege) {
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
    val mcauseInterruption = RegInit(false.B)
    val mcauseCode = RegInit(0.U(6.W))
    val mtval = RegInit(0.U(64.W))

    val mcounteren = RegInit(0.U(32.W))

    io.csrio.readData := 0.U
    io.csrio.illegal := false.B
    io.mstatus := mstatus
    io.mie := mie

    cycle := cycle + 1.U
    time := io.time
    instret := instret + io.instret

    when(io.csrio.readEn) {
        when(io.csrio.readAddr === CYCLE_ADDR){
            // TODO S mode
            readCsr(cycle, Mux(mcounteren(0), "b00".U, "b11".U))
        }.elsewhen(io.csrio.readAddr === TIME_ADDR) {
            // TODO S mode
            readCsr(time, Mux(mcounteren(1), "b00".U, "b11".U))
        }.elsewhen(io.csrio.readAddr === INSTRET_ADDR) {
            // TODO S mode
            readCsr(instret, Mux(mcounteren(2), "b00".U, "b11".U))
        }.elsewhen(io.csrio.readAddr === MVENDORID_ADDR) {
            // In development.
            readCsr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.readAddr === MARCHID_ADDR) {
            // In development.
            readCsr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.readAddr === MIMPID_ADDR) {
            // In development.
            readCsr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.readAddr === MHARTID_ADDR) {
            // 0x0
            readCsr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.readAddr === MCONFIGPTR_ADDR) {
            // TODO machine config
            readCsr("h0000000000000000".U, "b11".U)
        }.elsewhen(io.csrio.readAddr === MSTATUS_ADDR) {
            readCsr(mstatus, "b11".U)
        }.elsewhen(io.csrio.readAddr === MISA_ADDR) {
            // RV64I
            readCsr(misa, "b11".U)
        }.elsewhen(io.csrio.readAddr === MEDELEG_ADDR) {
            readCsr(medeleg, "b11".U)
        }.elsewhen(io.csrio.readAddr === MIDELEG_ADDR) {
            readCsr(mideleg, "b11".U)
        }.elsewhen(io.csrio.readAddr === MIE_ADDR) {
            readCsr(mie, "b11".U)
        }.elsewhen(io.csrio.readAddr === MTVEC_ADDR) {
            readCsr(mtvec, "b11".U)
        }.elsewhen(io.csrio.readAddr === MCOUNTEREN_ADDR) {
            readCsr(mcounteren, "b11".U)
        }.elsewhen(io.csrio.readAddr === MSCRATCH_ADDR) {
            readCsr(mscratch, "b11".U)
        }.elsewhen(io.csrio.readAddr === MEPC_ADDR) {
            readCsr(mepc, "b11".U)
        }.elsewhen(io.csrio.readAddr === MCAUSE_ADDR) {
            readCsr(Cat(mcauseInterruption, 0.U(57.W), mcauseCode), "b11".U)
        }.elsewhen(io.csrio.readAddr === MTVAL_ADDR) {
            readCsr(mtval, "b11".U)
        }.elsewhen(io.csrio.readAddr === MIP_ADDR) {
            // TODO
        }
    }

    when(io.csrio.writeEn) {
        when(io.csrio.writeAddr === MSTATUS_ADDR) {
            // write mask shown that which fields is implemented.
            val writeMask = "h00000000000000aa".U
            writeCsr(mstatus, writeMask & io.csrio.writeData, "b11".U)
        }.elsewhen(io.csrio.writeAddr === MISA_ADDR) {
            // Can't write this to switch func for now.
        }.elsewhen(io.csrio.writeAddr === MEDELEG_ADDR) {
            // TODO S mode
            writeCsr(medeleg, io.csrio.writeData, "b11".U)
        }.elsewhen(io.csrio.writeAddr === MIDELEG_ADDR) {
            // TODO S mode
            writeCsr(mideleg, io.csrio.writeData, "b11".U)
        }.elsewhen(io.csrio.writeAddr === MIE_ADDR) {
            writeCsr(mie, io.csrio.writeData, "b11".U)
        }.elsewhen(io.csrio.writeAddr === MTVEC_ADDR) {
            // mtvec mode >= 2 is Reserved
            val writeMask = "hfffffffffffffffd".U
            writeCsr(mtvec, writeMask & io.csrio.writeData, "b11".U)
        }.elsewhen(io.csrio.writeAddr === MCOUNTEREN_ADDR) {
            writeCsr(mcounteren, io.csrio.writeData, "b11".U)
        }.elsewhen(io.csrio.writeAddr === MSCRATCH_ADDR) {
            writeCsr(mscratch, io.csrio.writeData, "b11".U)
        }.elsewhen(io.csrio.writeAddr === MEPC_ADDR) {
            writeCsr(mepc, io.csrio.writeData, "b11".U)
        }.elsewhen(io.csrio.writeAddr === MCAUSE_ADDR) {
            writeCsr(mcauseInterruption, io.csrio.writeData(63) === 1.U, "b11".U)
            writeCsr(mcauseCode, io.csrio.writeData(5, 0), "b11".U)
        }.elsewhen(io.csrio.writeAddr === MTVAL_ADDR) {
            writeCsr(mtval, io.csrio.writeData, "b11".U)
        }.elsewhen(io.csrio.writeAddr === MIP_ADDR) {
            // TODO
        }
    }

    val setTrap = io.setTrap
    val trapInfo = setTrap.trapInfo
    val set = setTrap.set
    setTrap.trapHandler := 0.U
    setTrap.privilege := 0.U

    when(set) {
        val privilege = trapInfo.state.privilege
        val exceptionPc = trapInfo.state.exceptionPc
        val interruption = trapInfo.interruption
        val causeCode = trapInfo.causeCode

        val intDisable = "hfffffffffffffff5".U
        val sie = mstatus(1)
        val mie = mstatus(3)
        // xie to xpie and clear xie.
        mstatus := intDisable & Cat(mstatus(63, 13), privilege, mstatus(10, 8), mie, mstatus(6), sie, mstatus(4,0))
        mepc := exceptionPc

        mcauseInterruption := interruption
        mcauseCode := causeCode

        when(mtvec(1,0) === 0.U) {
            setTrap.trapHandler := Cat(mtvec(63,2),0.U(2.W))
        }.elsewhen(mtvec(1,0) === 1.U) {
            setTrap.trapHandler := Cat(mtvec(63,2),0.U(2.W)) + 4.U*causeCode
        }
        // TODO trap delegate
        setTrap.privilege := 3.U
    }

    val retException = io.trapRetInfo
    val ret = io.trapRet
    retException := 0.U.asTypeOf(new TrapState)

    when(ret) {
        val privilege = mstatus(12, 11)
        val exceptionPc = mepc

        val spie = mstatus(5)
        val mpie = mstatus(7)

        retException.privilege := privilege
        retException.exceptionPc := mepc
        mstatus := Cat(mstatus(63,4), mpie, mstatus(2), spie, mstatus(0))
    }
}
