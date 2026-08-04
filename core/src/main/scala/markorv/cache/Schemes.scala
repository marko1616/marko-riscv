package markorv.cache

import chisel3._
import chisel3.util._

import markorv.config.{CacheConfig, TlbConfig}
import markorv.bus.AxiResp
import markorv.bus.Pte

object CacheType extends Enumeration {
    val Icache, Dcache = Value
}

object DCacheCode extends ChiselEnum {
    val cacheHitOk, cacheMissOk, upstreamSlvErr, upstreamDecErr, pmaMmuWalkErr,
        pmaCacheErr, pmaLoadErr, pmaStorErr, pageLoadErr, pageStorErr = Value
    implicit class CacheCodeOps(private val x: DCacheCode.Type) extends AnyVal {
        def isOk(): Bool =
            x === DCacheCode.cacheHitOk || x === DCacheCode.cacheMissOk

        def fromAxiResp(resp: AxiResp.Type, hit: Bool) = {
            val okResp = Mux(hit, DCacheCode.cacheHitOk, DCacheCode.cacheMissOk)
            x := MuxLookup(resp, okResp)(
              Seq(
                AxiResp.slverr -> DCacheCode.upstreamSlvErr,
                AxiResp.decerr -> DCacheCode.upstreamDecErr
              )
            )
        }
    }
}

object ICacheCode extends ChiselEnum {
    val cacheHitOk, cacheMissOk, upstreamSlvErr, upstreamDecErr, pmaMmuWalkErr,
        pmaCacheErr, pmaInstErr, pageInstErr = Value
    implicit class ICacheCodeOps(private val x: ICacheCode.Type)
        extends AnyVal {
        def isOk(): Bool =
            x === ICacheCode.cacheHitOk || x === ICacheCode.cacheMissOk

        def fromAxiResp(resp: AxiResp.Type, hit: Bool) = {
            val okResp = Mux(hit, ICacheCode.cacheHitOk, ICacheCode.cacheMissOk)
            x := MuxLookup(resp, okResp)(
              Seq(
                AxiResp.slverr -> ICacheCode.upstreamSlvErr,
                AxiResp.decerr -> ICacheCode.upstreamDecErr
              )
            )
        }
    }
}

class CacheTagValid(implicit val c: CacheConfig) extends Bundle {
    val tag   = UInt(c.tagBits.W)
    val valid = Bool()
}

class CacheData(implicit val c: CacheConfig) extends Bundle {
    val data = UInt((8 * c.dataBytes).W)
}

class CacheDirty extends Bundle {
    val dirty = Bool()
}

class ICacheReadReq extends Bundle {
    val vaddr = UInt(64.W)
}

class ICacheReadResp(implicit val c: CacheConfig) extends Bundle {
    val code = new ICacheCode.Type
    val data = UInt((8 * c.dataBytes).W)
}

class DCacheReadReq extends Bundle {
    val vaddr = UInt(64.W)
}

class DCacheReadResp(implicit val c: CacheConfig) extends Bundle {
    val code = new DCacheCode.Type
    val data = UInt(64.W)
}

class DCacheWriteReq(implicit val c: CacheConfig) extends Bundle {
    val vaddr = UInt(64.W)
    val data  = UInt(64.W)
    val mask  = UInt(8.W)
}

class DCacheWriteResp extends Bundle {
    val code = new DCacheCode.Type
}

class IcacheInterface(implicit val c: CacheConfig) extends Bundle {
    val readReq  = Flipped(Decoupled(new ICacheReadReq))
    val readResp = Decoupled(new ICacheReadResp())
    val invalidateAllReq  = Flipped(Decoupled.empty)
    val invalidateAllResp = Output(Bool())
}

class DCacheCleanReq extends Bundle {
    val vaddr = UInt(64.W)
}

class DCacheCleanResp extends Bundle {
    val code = new DCacheCode.Type
}

class DCacheInvalidateReq extends Bundle {
    val vaddr = UInt(64.W)
}

class DCacheInvalidateResp extends Bundle {
    val code = new DCacheCode.Type
}

class DCacheAmoFlushReq extends Bundle {
    val vaddr    = UInt(64.W)
    val readLike = Bool()
}

class DCacheAmoFlushResp extends Bundle {
    val code = new DCacheCode.Type
}

class DCachePaReadReq extends Bundle {
    val paddr = UInt(64.W)
}

class DCachePaReadResp(implicit val c: CacheConfig) extends Bundle {
    val code = new DCacheCode.Type
    val data = UInt(64.W)
}

class DcacheInterface(implicit val c: CacheConfig) extends Bundle {
    val readReq           = Flipped(Decoupled(new DCacheReadReq))
    val readResp          = Valid(new DCacheReadResp())
    val writeReq          = Flipped(Decoupled(new DCacheWriteReq))
    val writeResp         = Valid(new DCacheWriteResp())
    val cleanReq          = Flipped(Decoupled(new DCacheCleanReq))
    val cleanResp         = Valid(new DCacheCleanResp)
    val invalidateReq     = Flipped(Decoupled(new DCacheInvalidateReq))
    val invalidateResp    = Valid(new DCacheInvalidateResp)
    val amoFlushReq       = Flipped(Decoupled(new DCacheAmoFlushReq))
    val amoFlushResp      = Valid(new DCacheAmoFlushResp)
    val invalidateAllReq  = Flipped(Decoupled.empty)
    val invalidateAllResp = Output(Bool())
    val cleanAllReq       = Flipped(Decoupled.empty)
    val cleanAllResp      = Output(Bool())
    val paReadReq         = Flipped(Decoupled(new DCachePaReadReq))
    val paReadResp        = Valid(new DCachePaReadResp())
    val paddr             = UInt(64.W)
}

object TlbPageLevel extends ChiselEnum {
    val page4KiB, page2MiB, page1GiB, pad = Value
}

object TlbInvalidateMode extends ChiselEnum {
    val byAll, byAsid, byVaddr, byAsidAndVaddr = Value
}

class TlbPte extends Bundle {
    val n    = Bool()
    val pbmt = UInt(2.W)
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

    def ppn: UInt = Cat(ppn2, ppn1, ppn0)

    def fromPte(pte: Pte): Unit = {
        this.n    := pte.n
        this.pbmt := pte.pbmt
        this.ppn2 := pte.ppn2
        this.ppn1 := pte.ppn1
        this.ppn0 := pte.ppn0
        this.rsw  := pte.rsw
        this.d    := pte.d
        this.a    := pte.a
        this.g    := pte.g
        this.u    := pte.u
        this.x    := pte.x
        this.w    := pte.w
        this.r    := pte.r
        this.v    := pte.v
    }
}

class TlbEntry(implicit val c: TlbConfig) extends Bundle {
    val valid = Bool()
    val asid  = UInt(c.asidWidth.W)
    val vpn   = UInt(c.vpnBits.W)
    val level = new TlbPageLevel.Type
    val pte   = new TlbPte
}

class TlbLookupReq(val asidWidth: Int) extends Bundle {
    val vaddr = UInt(64.W)
    val asid  = UInt(asidWidth.W)
}

class TlbLookupResp(val asidWidth: Int) extends Bundle {
    val hit   = Bool()
    val vaddr = UInt(64.W)
    val asid  = UInt(asidWidth.W)
    val paddr = UInt(64.W)
    val level = new TlbPageLevel.Type
    val pte   = new TlbPte
}

class TlbAllocateReq(val asidWidth: Int) extends Bundle {
    val vaddr = UInt(64.W)
    val asid  = UInt(asidWidth.W)
    val level = new TlbPageLevel.Type
    val pte   = new TlbPte
}

class TlbInvalidateReq(val asidWidth: Int) extends Bundle {
    val mode  = new TlbInvalidateMode.Type
    val asid  = UInt(asidWidth.W)
    val vaddr = UInt(64.W)
}

class TlbInvalidateResp extends Bundle {
    val done = Bool()
}
