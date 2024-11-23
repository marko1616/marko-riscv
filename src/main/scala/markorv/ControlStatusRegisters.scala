package markorv

import chisel3._
import chisel3.util._

class ExceptionState extends Bundle {
    val privilege = UInt(2.W)
    val exception_pc = UInt(64.W)
}

class ExceptionInfo extends Bundle {
    val interruption = Bool()
    val cause_code = UInt(6.W)
    val state = new ExceptionState
}

class ExceptionHandleIO extends Bundle {
    val set = Input(Bool())
    val exception_info = Input(new ExceptionInfo)
    val exception_handler = Output(UInt(64.W))
}

class ControlStatusRegistersIO extends Bundle {
    val read_addr = Input(UInt(12.W))
    val write_addr = Input(UInt(12.W))
    val write_en = Input(Bool())

    val read_data = Output(UInt(64.W))
    val write_data = Input(UInt(64.W))
}

class ControlStatusRegisters extends Module {
    val io = IO(new Bundle {
        val csrio = new ControlStatusRegistersIO
        val set_exception = new ExceptionHandleIO

        val ret = Input(Bool())
        val ret_exception = Output(new ExceptionState)

        val mstatus = Output(UInt(64.W))
        val mie = Output(UInt(64.W))
    })
    // While read write simultaneously shuould return old value.

    // Machine custom register(MRW)
    // For Zicsr test.
    val MCUSTOMTST_ADDR = "h800".U(12.W)

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

    val mcustomtst = RegInit(0.U(64.W))

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

    io.mstatus := mstatus
    io.mie := mie

    io.csrio.read_data := 0.U
    switch(io.csrio.read_addr) {
        is(MCUSTOMTST_ADDR) {
            io.csrio.read_data := mcustomtst
        }

        is(MVENDORID_ADDR) {
            // Non-commercial.
            io.csrio.read_data := "h0000000000000000".U
        }
        is(MARCHID_ADDR) {
            // In development.
            io.csrio.read_data := "h0000000000000000".U
        }
        is(MIMPID_ADDR) {
            // In development.
            io.csrio.read_data := "h0000000000000000".U
        }
        is(MHARTID_ADDR) {
            // 0x0
            io.csrio.read_data := "h0000000000000000".U
        }
        is(MCONFIGPTR_ADDR) {
            // TODO machine config
            io.csrio.read_data := "h0000000000000000".U
        }

        is(MSTATUS_ADDR) {
            io.csrio.read_data := mstatus
        }
        is(MISA_ADDR) {
            // RV64I
            io.csrio.read_data := misa
        }
        is(MEDELEG_ADDR) {
            io.csrio.read_data := medeleg
        }
        is(MIDELEG_ADDR) {
            io.csrio.read_data := mideleg
        }
        is(MIE_ADDR) {
            io.csrio.read_data := mie
        }
        is(MTVEC_ADDR) {
            io.csrio.read_data := mtvec
        }
        is(MCOUNTEREN_ADDR) {
            io.csrio.read_data := mcounteren
        }

        is(MSCRATCH_ADDR) {
            io.csrio.read_data := mscratch
        }
        is(MEPC_ADDR) {
            io.csrio.read_data := mepc
        }
        is(MCAUSE_ADDR) {
            io.csrio.read_data := Cat(mcause_interruption, 0.U(57.W), mcause_code)
        }
        is(MTVAL_ADDR) {
            io.csrio.read_data := mtval
        }
        is(MIP_ADDR) {
            // TODO
        }
    }

    when(io.csrio.write_en) {
        switch(io.csrio.write_addr) {
            is(MCUSTOMTST_ADDR) {
                mcustomtst := io.csrio.write_data
            }

            is(MSTATUS_ADDR) {
                // write mask shown that which fields is implemented.
                val write_mask = "h00000000000000aa".U
                mstatus := write_mask & io.csrio.write_data
            }
            is(MISA_ADDR) {
                // Can't write this to switch func for now.
            }
            is(MEDELEG_ADDR) {
                // TODO S mode
                medeleg := io.csrio.write_data
            }
            is(MIDELEG_ADDR) {
                // TODO S mode
                mideleg := io.csrio.write_data
            }
            is(MIE_ADDR) {
                mie := io.csrio.write_data
            }
            is(MTVEC_ADDR) {
                // mtvec mode >= 2 is Reserved
                val write_mask = "hfffffffffffffffd".U
                mtvec := write_mask & io.csrio.write_data
            }
            is(MCOUNTEREN_ADDR) {
                // TODO counter
                mcounteren := io.csrio.write_data
            }

            is(MSCRATCH_ADDR) {
                mscratch := io.csrio.write_data
            }
            is(MEPC_ADDR) {
                mepc := io.csrio.write_data
            }
            is(MCAUSE_ADDR) {
                mcause_interruption := io.csrio.write_data(63) === 1.U
                mcause_code := io.csrio.write_data(5, 0)
            }
            is(MTVAL_ADDR) {
                mtval := io.csrio.write_data
            }
            is(MIP_ADDR) {
                // TODO
            }
        }
    }

    val set_exception = io.set_exception
    val exception_info = set_exception.exception_info
    val set = set_exception.set
    set_exception.exception_handler := 0.U

    when(set) {
        val privilege = exception_info.state.privilege
        val exception_pc = exception_info.state.exception_pc
        val interruption = exception_info.interruption
        val cause_code = exception_info.cause_code

        val int_disable = "hfffffffffffffff5".U
        val sie = mstatus(1)
        val mie = mstatus(3)
        // xie to xpie and clear xie.
        mstatus := int_disable & Cat(mstatus(63, 13), privilege, mstatus(10, 8), mie, mstatus(6), sie, mstatus(4,0))
        mepc := exception_pc

        mcause_interruption := interruption
        mcause_code := cause_code

        when(mtvec(1,0) === 0.U) {
            set_exception.exception_handler := Cat(mtvec(63,2),0.U(2.W))
        }.elsewhen(mtvec(1,0) === 1.U) {
            set_exception.exception_handler := Cat(mtvec(63,2),0.U(2.W)) + 4.U*cause_code
        }
    }

    val ret_exception = io.ret_exception
    val ret = io.ret
    ret_exception := 0.U.asTypeOf(new ExceptionState)

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
