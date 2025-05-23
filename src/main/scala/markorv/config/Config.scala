package markorv.config

import chisel3._
import chisel3.util._

case class IOConfig(
    val read: Boolean = true,
    val write: Boolean = true,
    val atomicity: Boolean = true,
    val addrWidth: Int = 64,
    val dataWidth: Int = 64
) {
    require(addrWidth > 0, "Addr width must be positive")
    require(dataWidth > 0, "Data width must be positive")
    require(addrWidth % 8 == 0, "Addr width must be a multiple of 8 (byte-aligned)")
    require(dataWidth % 8 == 0, "Data width must be a multiple of 8 (byte-aligned)")
    require((atomicity && dataWidth == 64) | !atomicity, "Atomic operations are only supported when the data width equals 64 bits (8 bytes)")
    def burstLen(bus_width: Int) = {
        require(bus_width > 0, "Bus width must be positive")
        require(bus_width % 8 == 0, "Bus width must be a multiple of 8 (byte-aligned)")
        math.ceil(dataWidth.toDouble / bus_width).toInt - 1
    }
}

case class AxiConfig(
    addrWidth: Int = 64,
    dataWidth: Int = 64,
    idWidth: Int = 2
) {
    require(addrWidth > 0, "Addr width must be positive")
    require(dataWidth > 0, "Data width must be positive")
    require(addrWidth % 8 == 0, "Addr width must be a multiple of 8 (byte-aligned)")
    require(dataWidth % 8 == 0, "Data width must be a multiple of 8 (byte-aligned)")
    def size_width = log2Ceil(this.dataWidth/8)
}

case class CacheConfig(
    addrWidth: Int = 64,
    wayNum: Int = 2,
    setNum: Int = 4,
    offsetBits: Int = 6
) {
    require(addrWidth > 0, "addrWidth must be positive")
    require(wayNum >= 0, "wayBits must be non-negative")
    require(setNum >= 0, "setBits must be non-negative")
    require(offsetBits >= 0, "offsetBits must be non-negative")
    def setBits = log2Ceil(setNum)
    def indexBits = this.setBits + this.offsetBits
    def tagBits = this.addrWidth - this.indexBits
    def dataBytes = 1 << this.offsetBits
    def setStart = this.offsetBits
    def setEnd = this.offsetBits + this.setBits - 1
    def tagStart = this.offsetBits + this.setBits
    def tagEnd = this.addrWidth - 1
    def offsetMask = (~(0.U(addrWidth.W))) << this.offsetBits
}

case class ReorderBufferConfig(
    entries: Int
) {
    require(entries > 0 && (entries & (entries - 1)) == 0, "ROB Entries must be a positive power of 2")
}

case class CoreConfig(
    simulate: Boolean = true,
    ifqSize: Int = 4,
    axiConfig: AxiConfig = new AxiConfig,
    icacheConfig: CacheConfig = new CacheConfig,
    InstrFetchIoConfig: IOConfig = new IOConfig(
        read = true,
        write = false,
        atomicity = false,
        addrWidth = 64,
        dataWidth = 512
    ),
    loadStoreIoConfig: IOConfig = new IOConfig(
        read = true,
        write = true,
        atomicity = true,
        addrWidth = 64,
        dataWidth = 64
    )
)