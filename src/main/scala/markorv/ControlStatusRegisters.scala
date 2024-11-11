package markorv

import chisel3._
import chisel3.util._

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

    val mcustomtst = RegInit("h0000000000000000".U(64.W))

    val mstatus = RegInit("h0000000000000000".U(64.W))
    val misa = RegInit("h8000000000000100".U(64.W))
    val medeleg = RegInit("h0000000000000000".U(64.W))
    val mideleg = RegInit("h0000000000000000".U(64.W))
    val mie = RegInit("h0000000000000000".U(64.W))
    val mtvec = RegInit("h0000000000000000".U(64.W))

    val mcounteren = RegInit("h00000000".U(32.W))

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
        is(MCOUNTEREN_ADDR) {
            io.csrio.read_data := mcounteren
        }
    }

    when(io.csrio.write_en) {
        switch(io.csrio.write_addr) {
            is(MCUSTOMTST_ADDR) {
                mcustomtst := io.csrio.write_data
            }

            is(MSTATUS_ADDR) {
                // write mask shown that which fields is implemented.
                val write_mask = "h0000000000000000".U
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
            is(MTVEC_ADDR) {
                // mtvec mode >= 2 is Reserved
                val write_mask = "hfffffffffffffffd".U
                mtvec := write_mask & io.csrio.write_data
            }
            is(MCOUNTEREN_ADDR) {
                // TODO counter
                mcounteren := io.csrio.write_data
            }
        }
    }
}
