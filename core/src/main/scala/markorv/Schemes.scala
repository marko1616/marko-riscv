package markorv

import chisel3._
import chisel3.util._

object ControlStatusRegistersConstants {
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

    // Machine Counter Setup(MRW)
    val MCOUNTINHIBIT_ADDR = "h320".U(12.W)

    // Machine trap handling
    val MSCRATCH_ADDR = "h340".U(12.W)
    val MEPC_ADDR = "h341".U(12.W)
    val MCAUSE_ADDR = "h342".U(12.W)
    val MTVAL_ADDR = "h343".U(12.W)
    val MIP_ADDR = "h344".U(12.W)
}