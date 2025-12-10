package markorv.cache

import chisel3._
import chisel3.util._

import markorv.config._

object CacheType extends Enumeration {
    val Icache, Dcache = Value
}

object CacheCode extends ChiselEnum {
    val CacheHitOk, CacheMissOk, UpstreamSlvErr, UpstreamDecErr = Value
    implicit class CacheCodeOps(x: CacheCode.Type) {
        def isOk(): Bool = x === CacheCode.CacheHitOk || x === CacheCode.CacheMissOk
    }
}

class CacheTagValid(implicit val c: CacheConfig) extends Bundle {
    val tag = UInt(c.tagBits.W)
    val valid = Bool()
}

class CacheData(implicit val c: CacheConfig) extends Bundle {
    val data = UInt((8 * c.dataBytes).W)
}

class CacheDirty extends Bundle {
    val dirty = Bool()
}

class CacheReadReq extends Bundle {
    val addr = UInt(64.W)
}

class CacheReadResp(implicit val c: CacheConfig) extends Bundle {
    val code = new CacheCode.Type
    val data = UInt((8 * c.dataBytes).W)
}

class CacheWriteReq(implicit val c: CacheConfig) extends Bundle {
    val addr = UInt(64.W)
    val data = UInt((8 * c.dataBytes).W)
    val mask = UInt(c.dataBytes.W)
}

class CacheWriteResp extends Bundle {
    val code = new CacheCode.Type
}

class IcacheInterface(implicit val c: CacheConfig) extends Bundle {
    val readReq = Flipped(Decoupled(new CacheReadReq()))
    val readResp = Decoupled(new CacheReadResp())
}

class CacheCleanReq extends Bundle {
    val addr = UInt(64.W)
}

class DcacheInterface(implicit val c: CacheConfig) extends Bundle {
    val readReq = Flipped(Decoupled(new CacheReadReq()))
    val readResp = Decoupled(new CacheReadResp())
    val writeReq = Flipped(Decoupled(new CacheWriteReq()))
    val writeResp = Decoupled(new CacheWriteResp())
    val cleanReq = Flipped(Decoupled(new CacheCleanReq()))
    val cleanResp = Output(Bool())
}