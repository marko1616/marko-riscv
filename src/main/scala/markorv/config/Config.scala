package markorv.config

import chisel3._
import chisel3.util._

case class IOConfig(
    val read: Boolean = true,
    val write: Boolean = true,
    val atomicity: Boolean = true,
    val addr_width: Int = 64,
    val data_width: Int = 64
) {
    require(addr_width > 0, "Addr width must be positive")
    require(data_width > 0, "Data width must be positive")
    require(addr_width % 8 == 0, "Addr width must be a multiple of 8 (byte-aligned)")
    require(data_width % 8 == 0, "Data width must be a multiple of 8 (byte-aligned)")
    require((atomicity && data_width == 64) | !atomicity, "Atomic operations are only supported when the data width equals 64 bits (8 bytes)")
    def burst_len(bus_width: Int) = {
        require(bus_width > 0, "Bus width must be positive")
        require(bus_width % 8 == 0, "Bus width must be a multiple of 8 (byte-aligned)")
        math.ceil(data_width.toDouble / bus_width).toInt - 1
    }
}

case class AxiConfig(
    addr_width: Int = 64,
    data_width: Int = 64,
    id_width: Int = 2
) {
    require(addr_width > 0, "Addr width must be positive")
    require(data_width > 0, "Data width must be positive")
    require(addr_width % 8 == 0, "Addr width must be a multiple of 8 (byte-aligned)")
    require(data_width % 8 == 0, "Data width must be a multiple of 8 (byte-aligned)")
    def size_width = log2Ceil(this.data_width/8)
}

case class CacheConfig(
    addr_width: Int = 64,
    way_num: Int = 4,
    set_num: Int = 4,
    offset_bits: Int = 4
) {
    require(addr_width > 0, "addr_width must be positive")
    require(way_num >= 0, "way_bits must be non-negative")
    require(set_num >= 0, "set_bits must be non-negative")
    require(offset_bits >= 0, "offset_bits must be non-negative")
    def set_bits = log2Ceil(set_num)
    def index_bits = this.set_bits + this.offset_bits
    def tag_bits = this.addr_width - this.index_bits
    def data_bytes = 1 << this.offset_bits
    def set_start = this.offset_bits
    def set_end = this.offset_bits + this.set_bits - 1
    def tag_start = this.offset_bits + this.set_bits
    def tag_end = this.addr_width - 1
    def offset_mask = (~(0.U(addr_width.W))) << this.offset_bits
}

case class CoreConfig(
    simulate: Boolean = true,
    data_width: Int = 64,
    ifq_size: Int = 4,
    axi_config: AxiConfig = new AxiConfig,
    icache_config: CacheConfig = new CacheConfig,
    if_io_config: IOConfig = new IOConfig(
        read = true,
        write = false,
        atomicity = false,
        addr_width = 64,
        data_width = 128
    ),
    ls_io_config: IOConfig = new IOConfig(
        read = true,
        write = true,
        atomicity = true,
        addr_width = 64,
        data_width = 64
    )
)