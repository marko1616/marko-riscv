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

case class CoreConfig(
    simulate: Boolean = true,
    fetchQueueSize: Int = 4,
    axiConfig: AxiConfig = AxiConfig(),
    icacheConfig: CacheConfig = CacheConfig(),
    robSize: Int = 16,
    renameTableSize: Int = 4,
    regFileSize: Int = 64,
    fetchIoConfig: IOConfig = IOConfig(
        read = true,
        write = false,
        atomicity = false,
        addrWidth = 64,
        dataWidth = 512
    ),
    lsuIoConfig: IOConfig = IOConfig(
        read = true,
        write = true,
        atomicity = true,
        addrWidth = 64,
        dataWidth = 64
    )
) {
    private def isPowerOf2(x: Int): Boolean = (x > 0) && ((x & (x - 1)) == 0)
    require(isPowerOf2(robSize), "ROB size must be a positive power of 2")
    require(isPowerOf2(renameTableSize), "RenameTable size must be a positive power of 2")
    require(isPowerOf2(regFileSize), "Physical register number must be a positive power of 2")
    require(regFileSize >= 32, "Physical register number must be at least 32")
}