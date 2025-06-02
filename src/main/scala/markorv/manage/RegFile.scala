package markorv.manage

import chisel3._
import chisel3.util._

import markorv.config._

class RegFile(implicit val c: CoreConfig) extends Module {
    val addrWidth = log2Ceil(c.regFileSize)
    val io = IO(new Bundle {
        val readAddrs = Vec(2, Input(UInt(addrWidth.W)))
        val readDatas = Vec(2, Output(UInt(64.W)))
        val writePorts = Vec(5, Flipped(Valid(new RegWriteBundle)))

        val setStates = Flipped(Valid(Vec(c.regFileSize,new PhyRegState.Type)))
        val getStates = Output(Vec(c.regFileSize,new PhyRegState.Type))
    })
    val regs = RegInit(VecInit.fill(c.regFileSize)(0.U(64.W)))
    val states = RegInit(VecInit.tabulate(c.regFileSize) {
        x => if (x > 30) PhyRegState.Free else PhyRegState.Allocated
    })

    for ((addr, rport) <- io.readAddrs.zip(io.readDatas)) {
        rport := regs(addr)
    }

    for (wport <- io.writePorts) {
        when(wport.valid) {
            regs(wport.bits.addr) := wport.bits.data
        }
    }

    when(io.setStates.valid) {
        states := io.setStates.bits
    }

    io.getStates := states
}
