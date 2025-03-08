package markorv.cache

import chisel3._
import chisel3.util._

class CacheLine(n_set: Int, n_way: Int, n_byte: Int) extends Bundle {
    val data = UInt((8 * n_byte).W)
    val tag = UInt(
      (64 - log2Ceil(n_set) - log2Ceil(n_way) - log2Ceil(n_byte)).W
    )
    val valid = Bool()
    val dirty = Bool()
}

class CacheWay(n_set: Int, n_way: Int, n_byte: Int) extends Bundle {
    val data = Vec(n_way, new CacheLine(n_set, n_way, n_byte))
}
