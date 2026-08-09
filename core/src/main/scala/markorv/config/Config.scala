package markorv.config

import scala.io.Source

import io.circe._
import io.circe.generic.semiauto._
import io.circe.yaml.parser

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
    require(
      (atomicity && dataWidth == 64) | !atomicity,
      "Atomic operations are only supported when the data width equals 64 bits (8 bytes)"
    )

    def burstLen(bus_width: Int): Int = {
        require(bus_width > 0, "Bus width must be positive")
        require(
          bus_width % 8 == 0,
          "Bus width must be a multiple of 8 (byte-aligned)"
        )
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

    def size_width: Int = log2Ceil(this.dataWidth / 8)
}

case class CacheConfig(
    addrWidth: Int,
    wayNum: Int,
    setNum: Int,
    byteNum: Int,
    tagWidth: Option[Int] = None
) {

    require(addrWidth > 0, "addrWidth must be positive")
    require(wayNum >= 0, "wayNum must be non-negative")
    require(setNum >= 0, "setNum must be non-negative")
    require(byteNum >= 0, "byteNum must be non-negative")
    require(isPow2(wayNum), "wayNum must be power of 2")
    require(isPow2(setNum), "setNum must be power of 2")
    require(isPow2(byteNum), "byteNum must be power of 2")
    require(
      log2Ceil(byteNum) + log2Ceil(setNum) <= 12,
      "log2(byteNum) + log2(setNum) must <= 12"
    )

    def setBits: Int    = log2Ceil(setNum)
    def wayBits: Int    = log2Ceil(wayNum)
    def offsetBits: Int = log2Ceil(byteNum)
    def indexBits: Int  = this.setBits + this.offsetBits

    def naturalTagBits: Int = this.addrWidth - this.indexBits
    def tagBits: Int        = tagWidth.getOrElse(naturalTagBits)

    tagWidth.foreach { tw =>
        require(tw > 0, s"tagWidth ($tw) must be positive")
        require(
          tw <= naturalTagBits,
          s"tagWidth ($tw) must not exceed natural tag bits ($naturalTagBits = addrWidth($addrWidth) - indexBits($indexBits))"
        )
    }

    def dataBytes: Int               = 1 << this.offsetBits
    def setStart: Int                = this.offsetBits
    def setEnd: Int                  = this.offsetBits + this.setBits - 1
    def tagStart: Int                = this.offsetBits + this.setBits
    def tagEnd: Int                  = this.tagStart + this.tagBits - 1
    def offsetMask: UInt             = (~0.U(addrWidth.W)) << this.offsetBits
    def maxRepresentableAddr: BigInt = (BigInt(1) << (tagBits + indexBits)) - 1
}

case class TlbConfig(
    addrWidth: Int,
    entryNum: Int,
    asidWidth: Int
) {
    require(
      addrWidth >= 39,
      "TLB currently assumes SV39 and addrWidth must be >= 39"
    )
    require(entryNum > 0, "TLB entryNum must be positive")
    require(isPow2(entryNum), "TLB entryNum must be power of 2")
    require(asidWidth > 0, "TLB asidWidth must be positive")
    require(asidWidth <= 16, "TLB asidWidth must be <= 16")

    def pageOffsetBits: Int = 12
    def sv39VpnHigh: Int    = 38
    def vpnBits: Int        = 27
    def ppnBits: Int        = addrWidth - pageOffsetBits
    def entryIdxBits: Int   = math.max(1, log2Ceil(entryNum))
}

case class PmaConfig(
    addrLow: BigInt,
    addrHigh: BigInt,
    r: Boolean,
    w: Boolean,
    x: Boolean,
    c: Boolean,
    a: Boolean
) {
    require(addrLow >= 0, "addrLow must be non-negative")
    require(addrHigh >= 0, "addrHigh must be non-negative")
    require(
      addrLow <= addrHigh,
      "addrLow must be less than or equal to addrHigh"
    )
    require(r || w || x, "r or w or x must be true")
    require(!x || (r && x), "When x is set r must be set")
    require((x && c) || !x, "When x is set c must be set")
}

case class CoreConfig(
    simulate: Boolean,
    resetVector: BigInt,
    fetchQueueSize: Int,
    asidWidth: Int,
    axiConfig: AxiConfig,
    icacheConfig: CacheConfig,
    dcacheConfig: CacheConfig,
    lsuIoConfig: IOConfig,
    mmuIoConfig: IOConfig,
    robSize: Int,
    rsSize: Int,
    renameTableSize: Int,
    regFileSize: Int,
    pma: List[PmaConfig],
    mulCompTreeMaxStage: Int,
    dividerBase: Int,
    dividerRemLeadBits: Int,
    dividerDivisorLeadBits: Int,
    dividerMaxStage: Int,
    tlb4KEntries: Int,
    tlb2MEntries: Int,
    tlb1GEntries: Int
) {
    require(asidWidth > 0, "Asid width must > 0")
    require(asidWidth <= 16, "Asid width must <= 16")
    require(isPow2(robSize), "ROB size must be a positive power of 2")
    require(
      isPow2(rsSize),
      "Reservation station size must be a positive power of 2"
    )
    require(
      isPow2(renameTableSize),
      "RenameTable size must be a positive power of 2"
    )
    require(
      isPow2(regFileSize),
      "Physical register number must be a positive power of 2"
    )
    require(regFileSize >= 32, "Physical register number must be at least 32")

    require(
      tlb4KEntries > 0 && isPow2(tlb4KEntries),
      "tlb4KEntries must be a positive power of 2"
    )
    require(
      tlb2MEntries > 0 && isPow2(tlb2MEntries),
      "tlb2MEntries must be a positive power of 2"
    )
    require(
      tlb1GEntries > 0 && isPow2(tlb1GEntries),
      "tlb1GEntries must be a positive power of 2"
    )

    def tlb4KConfig: TlbConfig = TlbConfig(
      addrWidth = dcacheConfig.addrWidth,
      entryNum = tlb4KEntries,
      asidWidth = asidWidth
    )
    def tlb2MConfig: TlbConfig = TlbConfig(
      addrWidth = dcacheConfig.addrWidth,
      entryNum = tlb2MEntries,
      asidWidth = asidWidth
    )
    def tlb1GConfig: TlbConfig = TlbConfig(
      addrWidth = dcacheConfig.addrWidth,
      entryNum = tlb1GEntries,
      asidWidth = asidWidth
    )

    pma.combinations(2).foreach {
        case List(a, b) =>
            require(
              a.addrHigh < b.addrLow || b.addrHigh < a.addrLow,
              s"PMA regions overlap: $a and $b"
            )
        case _ => // This case will never happen given combinations(2)
    }

    private val icacheMax = icacheConfig.maxRepresentableAddr
    private val dcacheMax = dcacheConfig.maxRepresentableAddr

    pma.filter(_.c).foreach { p =>
        require(
          p.addrHigh <= icacheMax,
          s"Cacheable PMA region [0x${p.addrLow.toString(16)}, 0x${p.addrHigh.toString(16)}] " +
              s"exceeds icache tag representable range " +
              s"(tagBits=${icacheConfig.tagBits}, indexBits=${icacheConfig.indexBits}, " +
              s"max=0x${icacheMax.toString(16)}). " +
              s"Addresses beyond the tag width must be guaranteed to trigger a PMA error."
        )
        require(
          p.addrHigh <= dcacheMax,
          s"Cacheable PMA region [0x${p.addrLow.toString(16)}, 0x${p.addrHigh.toString(16)}] " +
              s"exceeds dcache tag representable range " +
              s"(tagBits=${dcacheConfig.tagBits}, indexBits=${dcacheConfig.indexBits}, " +
              s"max=0x${dcacheMax.toString(16)}). " +
              s"Addresses beyond the tag width must be guaranteed to trigger a PMA error."
        )
    }
}

object ConfigLoader {
    implicit val ioConfigDecoder: Decoder[IOConfig]       = deriveDecoder
    implicit val axiConfigDecoder: Decoder[AxiConfig]     = deriveDecoder
    implicit val cacheConfigDecoder: Decoder[CacheConfig] = deriveDecoder
    implicit val pmaConfigDecoder: Decoder[PmaConfig]     = deriveDecoder
    implicit val coreConfigDecoder: Decoder[CoreConfig]   = deriveDecoder

    def loadCoreConfigFromFile(path: String): Either[Error, CoreConfig] = {
        val source = Source.fromFile(path)
        val content =
            try source.mkString
            finally source.close()
        parser.decode[CoreConfig](content)
    }
}
