package markorv.bus

import chisel3._
import chisel3.util._

import markorv.config._

object AxiResp extends ChiselEnum {
    val okay  = Value("b00".U)
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

class ReadChannel(implicit val c: IOConfig, implicit val master: Boolean) extends Bundle {
    val params = if (master) {
        Decoupled(new ReadParams)
    } else {
        Flipped(Decoupled(new ReadParams))
    }

    // Master should always be ready for getting a response.
    val resp = if (master) {
        Flipped(Decoupled(new ReadResp(c.dataWidth)))
    } else {
        Decoupled(new ReadResp(c.dataWidth))
    }
}

class WriteChannel(implicit val c: IOConfig, implicit val master: Boolean) extends Bundle {
    val params = if (master) {
        Decoupled(new WriteParams)
    } else {
        Flipped(Decoupled(new WriteParams))
    }

    // Master should always be ready for getting a response.
    val resp = if (master) {
        Flipped(Decoupled(AxiResp()))
    } else {
        Decoupled(AxiResp())
    }
}

class IOInterface(implicit val c: IOConfig, implicit val master: Boolean) extends Bundle {
    val read = if(c.read) Some(new ReadChannel()) else None
    val write = if(c.write) Some(new WriteChannel()) else None
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
  val b  = Flipped(Decoupled(new AxiWriteResponseBundle(c)))
  val r  = Flipped(Decoupled(new AxiReadDataBundle(c)))
}

class PhyMemAttr() extends Bundle {
    val r = Bool()
    val w = Bool()
    val x = Bool()
    val c = Bool()
    val a = Bool()
}