package markorv.cache

import chisel3._
import chisel3.util._

import markorv.config._

class CacheTagValid(implicit val config: CacheConfig) extends Bundle {
    val tag = UInt(config.tag_bits.W)
    val valid = Bool()
}

class CacheData(implicit val config: CacheConfig) extends Bundle {
    val data = UInt((8 * config.data_bytes).W)
}

class CacheDirty extends Bundle {
    val dirty = Bool()
}
