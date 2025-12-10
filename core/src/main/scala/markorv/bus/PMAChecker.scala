package markorv.bus

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._

class PMAChecker(pmaList: List[PmaConfig]) extends Module {
    val io = IO(new Bundle {
        val addr = Input(UInt(64.W))
        val attr = Output(new PhyMemAttr)
    })

    io.attr := new PhyMemAttr().zero

    for (pma <- pmaList) {
        when (io.addr >= pma.addrLow.U && io.addr <= pma.addrHigh.U) {
            io.attr.r := pma.r.B
            io.attr.w := pma.w.B
            io.attr.x := pma.x.B
            io.attr.c := pma.c.B
            io.attr.a := pma.a.B
        }
    }
}