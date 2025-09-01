package markorv.config

import scala.io.Source

import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._

import chisel3._
import chisel3.util._

case class IOConfig(
    val read: Boolean,
    val write: Boolean,
    val atomicity: Boolean,
    val addrWidth: Int,
    val dataWidth: Int
) {
    require(addrWidth > 0, "Addr width must be positive")
    require(dataWidth > 0, "Data width must be positive")
    require(addrWidth % 8 == 0, "Addr width must be a multiple of 8 (byte-aligned)")
    require(dataWidth % 8 == 0, "Data width must be a multiple of 8 (byte-aligned)")
    require((atomicity && dataWidth == 64) | !atomicity, "Atomic operations are only supported when the data width equals 64 bits (8 bytes)")

    def burstLen(bus_width: Int): Int = {
        require(bus_width > 0, "Bus width must be positive")
        require(bus_width % 8 == 0, "Bus width must be a multiple of 8 (byte-aligned)")
        math.ceil(dataWidth.toDouble / bus_width).toInt - 1
    }
}

case class AxiConfig(
    addrWidth: Int,
    dataWidth: Int,
    idWidth: Int
) {
    require(addrWidth > 0, "Addr width must be positive")
    require(dataWidth > 0, "Data width must be positive")
    require(addrWidth % 8 == 0, "Addr width must be a multiple of 8 (byte-aligned)")
    require(dataWidth % 8 == 0, "Data width must be a multiple of 8 (byte-aligned)")

    def size_width: Int = log2Ceil(this.dataWidth / 8)
}

case class CacheConfig(
    addrWidth: Int,
    wayNum: Int,
    setNum: Int,
    offsetBits: Int
) {
    require(addrWidth > 0, "addrWidth must be positive")
    require(wayNum >= 0, "wayBits must be non-negative")
    require(setNum >= 0, "setBits must be non-negative")
    require(offsetBits >= 0, "offsetBits must be non-negative")

    def setBits: Int = log2Ceil(setNum)
    def indexBits: Int = this.setBits + this.offsetBits
    def tagBits: Int = this.addrWidth - this.indexBits
    def dataBytes: Int = 1 << this.offsetBits
    def setStart: Int = this.offsetBits
    def setEnd: Int = this.offsetBits + this.setBits - 1
    def tagStart: Int = this.offsetBits + this.setBits
    def tagEnd: Int = this.addrWidth - 1
    def offsetMask: UInt = (~(0.U(addrWidth.W))) << this.offsetBits
}

case class CoreConfig(
    simulate: Boolean,
    resetVector: Int,
    fetchQueueSize: Int,
    axiConfig: AxiConfig,
    icacheConfig: CacheConfig,
    robSize: Int,
    rsSize: Int,
    renameTableSize: Int,
    regFileSize: Int,
    fetchIoConfig: IOConfig,
    lsuIoConfig: IOConfig
) {
    private def isPowerOf2(x: Int): Boolean = (x > 0) && ((x & (x - 1)) == 0)

    require(isPowerOf2(robSize), "ROB size must be a positive power of 2")
    require(isPowerOf2(rsSize), "Reservation station size must be a positive power of 2")
    require(isPowerOf2(renameTableSize), "RenameTable size must be a positive power of 2")
    require(isPowerOf2(regFileSize), "Physical register number must be a positive power of 2")
    require(regFileSize >= 32, "Physical register number must be at least 32")
}

object ConfigLoader {
    implicit val ioConfigDecoder: Decoder[IOConfig] = deriveDecoder
    implicit val axiConfigDecoder: Decoder[AxiConfig] = deriveDecoder
    implicit val cacheConfigDecoder: Decoder[CacheConfig] = deriveDecoder
    implicit val coreConfigDecoder: Decoder[CoreConfig] = deriveDecoder

    def loadCoreConfigFromFile(path: String): Either[Error, CoreConfig] = {
        val source = Source.fromFile(path)
        val content = try source.mkString finally source.close()
        decode[CoreConfig](content)
    }
}
