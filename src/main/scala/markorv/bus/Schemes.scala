package markorv.bus

import chisel3._
import chisel3.util._

import markorv.config._

object AxiResp extends ChiselEnum {
    val okay  = Value("b00".U)
    val exokay = Value("b01".U)
    val slverr = Value("b10".U)
    val decerr = Value("b11".U)
}

class ReadResp(val data_width: Int) extends Bundle {
    val resp = AxiResp()
    val data = UInt(data_width.W)
}

class ReadParams(implicit val config: IOConfig) extends Bundle {
    val addr = UInt(config.addr_width.W)
    val size = UInt(log2Ceil(config.data_width / 8).W)
    val lock = if (config.atomicity) Some(Bool()) else None
}

class WriteParams(implicit val config: IOConfig) extends Bundle {
    val addr = UInt(config.addr_width.W)
    val data = UInt(config.data_width.W)
    val size = UInt(log2Ceil(config.data_width / 8).W)
    val lock = if (config.atomicity) Some(Bool()) else None
}

class ReadChannel(implicit val config: IOConfig, implicit val master: Boolean) extends Bundle {
    val params = if (master) {
        Decoupled(new ReadParams)
    } else {
        Flipped(Decoupled(new ReadParams))
    }

    // Master should always be ready for getting a response.
    val resp = if (master) {
        Flipped(Decoupled(new ReadResp(config.data_width)))
    } else {
        Decoupled(new ReadResp(config.data_width))
    }
}

class WriteChannel(implicit val config: IOConfig, implicit val master: Boolean) extends Bundle {
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

class IOInterface(implicit val config: IOConfig, implicit val master: Boolean) extends Bundle {
    val read = if(config.read) Some(new ReadChannel()) else None
    val write = if(config.write) Some(new WriteChannel()) else None
}

class AxiWriteAddressBundle(config: AxiConfig) extends Bundle {
    val addr   = UInt(config.addr_width.W)
    val size   = UInt(3.W)
    val burst  = UInt(2.W)
    val cache  = UInt(4.W)
    val id     = UInt(config.id_width.W)
    val len    = UInt(8.W)
    val lock   = Bool()
    val qos    = UInt(4.W)
    val region = UInt(4.W)
    val prot   = UInt(3.W)
    // NO AWUSER
}

class AxiWriteDataBundle(config: AxiConfig) extends Bundle {
    val data = UInt(config.data_width.W)
    val strb = UInt((config.data_width / 8).W)
    val last = Bool()
    // NO WUSER
}

class AxiWriteResponseBundle(config: AxiConfig) extends Bundle {
    val resp = UInt(2.W)
    val id   = UInt(config.id_width.W)
    // NO BUSER
}

class AxiReadAddressBundle(config: AxiConfig) extends Bundle {
    val addr   = UInt(config.addr_width.W)
    val size   = UInt(3.W)
    val burst  = UInt(2.W)
    val cache  = UInt(4.W)
    val id     = UInt(config.id_width.W)
    val len    = UInt(8.W)
    val lock   = Bool()
    val qos    = UInt(4.W)
    val region = UInt(4.W)
    val prot   = UInt(3.W)
    // NO ARUSER
}

class AxiReadDataBundle(config: AxiConfig) extends Bundle {
    val data = UInt(config.data_width.W)
    val resp = UInt(2.W)
    val id   = UInt(config.id_width.W)
    val last = Bool()
    // NO RUSER
}

class AxiInterface(config: AxiConfig) extends Bundle {
  // Master -> Slave
  val aw = Decoupled(new AxiWriteAddressBundle(config))
  val w  = Decoupled(new AxiWriteDataBundle(config))
  val ar = Decoupled(new AxiReadAddressBundle(config))
  
  // Slave -> Master
  val b  = Flipped(Decoupled(new AxiWriteResponseBundle(config)))
  val r  = Flipped(Decoupled(new AxiReadDataBundle(config)))
}