package markorv.bus

import chisel3._
import chisel3.util._

import markorv.config.PmaConfig
import markorv.utils.ChiselUtils.DataOperationExtension

class PMAChecker(pmaList: List[PmaConfig]) extends Module {
    val io = IO(new Bundle {
        val addr = Input(UInt(64.W))
        val size = Input(UInt(3.W))
        val attr = Output(new PhyMemAttr)
    })

    val defaultAttr = new PhyMemAttr().zero

    val hits = pmaList.map { pma =>
        io.addr >= pma.addrLow.U && (io.addr + (1.U << io.size)) <= pma.addrHigh.U
    }

    val attrs = pmaList.map { pma =>
        val a = Wire(new PhyMemAttr)
        a   := defaultAttr
        a.r := pma.r.B
        a.w := pma.w.B
        a.x := pma.x.B
        a.c := pma.c.B
        a.a := pma.a.B
        a
    }

    io.attr := Mux(hits.reduce(_ || _), Mux1H(hits zip attrs), defaultAttr)
}
