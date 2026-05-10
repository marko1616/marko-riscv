package markorv.bus

import chisel3._
import chisel3.util._

import markorv.config.{AxiConfig, IOConfig}

object AxiResp extends ChiselEnum {
    val okay   = Value("b00".U)
    val exokay = Value("b01".U)
    val slverr = Value("b10".U)
    val decerr = Value("b11".U)
    implicit class AxiRespOps(x: AxiResp.Type) {
        def isOk(): Bool = x === okay || x === exokay
    }
}

class ReadResp(val dataWidth: Int) extends Bundle {
    val resp = AxiResp()
    val data = UInt(dataWidth.W)
}

class ReadParams(implicit val c: IOConfig) extends Bundle {
    val addr = UInt(c.addrWidth.W)
    val size = UInt(log2Ceil(c.dataWidth / 8).W)
    val lock = if (c.atomicity) Some(Bool()) else None
}

class WriteParams(implicit val c: IOConfig) extends Bundle {
    val addr = UInt(c.addrWidth.W)
    val data = UInt(c.dataWidth.W)
    val size = UInt(log2Ceil(c.dataWidth / 8).W)
    val lock = if (c.atomicity) Some(Bool()) else None
}

class ReadChannel(implicit val c: IOConfig, implicit val master: Boolean)
    extends Bundle {
    val params = if (master) {
        Valid(new ReadParams)
    } else {
        Flipped(Valid(new ReadParams))
    }

    // Master should always be ready for getting a response.
    val resp = if (master) {
        Flipped(Valid(new ReadResp(c.dataWidth)))
    } else {
        Valid(new ReadResp(c.dataWidth))
    }
}

class WriteChannel(implicit val c: IOConfig, implicit val master: Boolean)
    extends Bundle {
    val params = if (master) {
        Valid(new WriteParams)
    } else {
        Flipped(Valid(new WriteParams))
    }

    // Master should always be ready for getting a response.
    val resp = if (master) {
        Flipped(Valid(AxiResp()))
    } else {
        Valid(AxiResp())
    }
}

class IOInterface(implicit val c: IOConfig, implicit val master: Boolean)
    extends Bundle {
    // This interface assumed master should hold valid params until transaction finished
    val read  = if (c.read) Some(new ReadChannel()) else None
    val write = if (c.write) Some(new WriteChannel()) else None
}

class AxiWriteAddressBundle(c: AxiConfig) extends Bundle {
    val addr   = UInt(c.addrWidth.W)
    val size   = UInt(3.W)
    val burst  = UInt(2.W)
    val cache  = UInt(4.W)
    val id     = UInt(c.idWidth.W)
    val len    = UInt(8.W)
    val lock   = Bool()
    val qos    = UInt(4.W)
    val region = UInt(4.W)
    val prot   = UInt(3.W)
    // NO AWUSER
}

class AxiWriteDataBundle(c: AxiConfig) extends Bundle {
    val data = UInt(c.dataWidth.W)
    val strb = UInt((c.dataWidth / 8).W)
    val last = Bool()
    // NO WUSER
}

class AxiWriteResponseBundle(c: AxiConfig) extends Bundle {
    val resp = UInt(2.W)
    val id   = UInt(c.idWidth.W)
    // NO BUSER
}

class AxiReadAddressBundle(c: AxiConfig) extends Bundle {
    val addr   = UInt(c.addrWidth.W)
    val size   = UInt(3.W)
    val burst  = UInt(2.W)
    val cache  = UInt(4.W)
    val id     = UInt(c.idWidth.W)
    val len    = UInt(8.W)
    val lock   = Bool()
    val qos    = UInt(4.W)
    val region = UInt(4.W)
    val prot   = UInt(3.W)
    // NO ARUSER
}

class AxiReadDataBundle(c: AxiConfig) extends Bundle {
    val data = UInt(c.dataWidth.W)
    val resp = UInt(2.W)
    val id   = UInt(c.idWidth.W)
    val last = Bool()
    // NO RUSER
}

class AxiInterface(c: AxiConfig) extends Bundle {
    // Master -> Slave
    val aw = Decoupled(new AxiWriteAddressBundle(c))
    val w  = Decoupled(new AxiWriteDataBundle(c))
    val ar = Decoupled(new AxiReadAddressBundle(c))

    // Slave -> Master
    val b = Flipped(Decoupled(new AxiWriteResponseBundle(c)))
    val r = Flipped(Decoupled(new AxiReadDataBundle(c)))
}

class PhyMemAttr() extends Bundle {
    val r = Bool()
    val w = Bool()
    val x = Bool()
    val c = Bool()
    val a = Bool()
}

class Pte extends Bundle {
    val n    = Bool()
    val pbmt = UInt(2.W)
    val pad  = UInt(7.W)
    val ppn2 = UInt(26.W)
    val ppn1 = UInt(9.W)
    val ppn0 = UInt(9.W)
    val rsw  = UInt(2.W)
    val d    = Bool()
    val a    = Bool()
    val g    = Bool()
    val u    = Bool()
    val x    = Bool()
    val w    = Bool()
    val r    = Bool()
    val v    = Bool()

    def fromRaw(raw: UInt) = {
        this.n    := raw(63)
        this.pbmt := raw(62, 61)
        this.pad  := raw(60, 54)
        this.ppn2 := raw(53, 28)
        this.ppn1 := raw(27, 19)
        this.ppn0 := raw(18, 10)
        this.rsw  := raw(9, 8)
        this.d    := raw(7)
        this.a    := raw(6)
        this.g    := raw(5)
        this.u    := raw(4)
        this.x    := raw(3)
        this.w    := raw(2)
        this.r    := raw(1)
        this.v    := raw(0)
    }
}

class MmuReq extends Bundle {
    val va   = UInt(64.W)
    val mode = new MmuMode.Type
}

class MmuResp extends Bundle {
    val pa           = UInt(64.W)
    val valid        = Bool()
    val walkPmaFault = Bool()
    val pmaRead      = Bool()
    val pmaWrite     = Bool()
    val pmaExec      = Bool()
    val pteRead      = Bool()
    val pteWrite     = Bool()
    val pteExec      = Bool()
    val user         = Bool()
    val global       = Bool()
    val dirty        = Bool()
    val accessed     = Bool()
    val cache        = Bool()
    val atomic       = Bool()
}

// MMU Mode
object MmuMode extends ChiselEnum {
    val bare = Value("b0".U)
    val sv39 = Value("b1".U)
}
