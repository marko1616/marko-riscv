package markorv.math.multiplier

import chisel3._
import chisel3.util._

import markorv.math.compressor.CompTree

object BoothRadix4Multiplier {
    private def nextAddendNum(n: Int): Int = {
        require(n >= 2, s"addend number must be >= 2, got $n")

        if (n == 3) {
            2
        } else {
            val groups4   = n / 4
            val remainder = n % 4
            groups4 * 2 + (if (remainder == 3) 2 else remainder)
        }
    }

    def compTreePipeCount(ppNum: Int, maxStage: Int): Int = {
        require(ppNum >= 2, s"ppNum must be >= 2, got $ppNum")
        require(maxStage >= 1, s"maxStage must be >= 1, got $maxStage")

        var n         = ppNum
        var currStage = 0
        var pipes     = 0

        while (n > 2) {
            n = nextAddendNum(n)
            currStage += 1

            if (currStage >= maxStage) {
                pipes += 1
                currStage = 0
            }
        }

        pipes
    }

    def latency(width: Int, maxStage: Int): Int = {
        require(width >= 2, s"operand width must be >= 2, got $width")
        require(maxStage >= 1, s"maxStage must be >= 1, got $maxStage")

        val numGroups = (width + 2) / 2

        // 1 cycle ppStageReg + N CompTree pipeState insertions + 1 cycle finalReg.
        2 + compTreePipeCount(numGroups, maxStage)
    }
}

class BoothRadix4Multiplier(width: Int, maxStage: Int) extends Module {
    require(width >= 2, s"operand width must be >= 2, got $width")
    require(maxStage >= 1, s"maxStage must be >= 1, got $maxStage")

    private def hex(x: BigInt): String =
        "0x" + x.toString(16)

    private def signedHex(x: BigInt): String =
        if (x < 0) "-0x" + x.abs.toString(16) else hex(x)

    private def dbg(msg: => String): Unit =
        println(s"[BoothRadix4Multiplier width=$width maxStage=$maxStage] $msg")

    val io = IO(new Bundle {
        val in = Input(new Bundle {
            val x1 = UInt(width.W)
            val x2 = UInt(width.W)
        })

        val out = Output(new Bundle {
            val lo   = UInt(width.W)
            val hi_u = UInt(width.W)
            val hi_s = UInt(width.W)
        })
    })

    private def pipeData[T <: Data](data: T): T = {
        val dataReg = Reg(chiselTypeOf(data))
        dataReg := data
        dataReg
    }

    private val numGroups   = (width + 2) / 2
    private val ppWidth     = width + 2
    private val ppStep      = 2
    private val finalWidth  = ppWidth + (numGroups - 1) * ppStep
    private val resultWidth = 2 * width

    private val x1 = io.in.x1
    private val x2 = io.in.x2

    private val padX1 = Cat(0.U(1.W), x1, 0.U(1.W))

    private val m    = x2.pad(ppWidth)
    private val negM = ~m + 1.U

    private val mShl1    = (m << 1)(ppWidth - 1, 0)
    private val negMShl1 = (negM << 1)(ppWidth - 1, 0)

    private val rawTable = Seq(
      0.U(ppWidth.W),
      m,
      m,
      mShl1,
      negMShl1,
      negM,
      negM,
      0.U(ppWidth.W)
    )

    private val msbFlip  = (BigInt(1) << (ppWidth - 1)).U(ppWidth.W)
    private val boothMap = VecInit(rawTable.map(_ ^ msbFlip))

    private val pps = Wire(Vec(numGroups, UInt(ppWidth.W)))

    for (i <- 0 until numGroups) {
        val bitLo = i * 2
        val bitHi = bitLo + 2

        val encoding =
            if (bitHi < padX1.getWidth) {
                padX1(bitHi, bitLo)
            } else {
                padX1(padX1.getWidth - 1, bitLo).pad(3)(2, 0)
            }

        pps(i) := boothMap(encoding)
    }

    private val biasTerms = (0 until numGroups).map { i =>
        BigInt(1) << (ppWidth - 1 + ppStep * i)
    }

    private val biasSum    = biasTerms.sum
    private val corrSigned = -biasSum
    private val corrMod = {
        val modulus = BigInt(1) << finalWidth
        ((corrSigned % modulus) + modulus) % modulus
    }

    private val (corrWeight, corrWidth, corrVal): (Int, Int, BigInt) =
        if (corrMod == 0) {
            (0, 1, BigInt(0))
        } else {
            val w       = corrMod.lowestSetBit
            val shifted = corrMod >> w
            (w, shifted.bitLength, shifted)
        }

    require((corrVal << corrWeight) == corrMod)

    dbg(
      s"""
         |static params:
         |  width       = $width
         |  numGroups   = $numGroups
         |  ppWidth     = $ppWidth
         |  ppStep      = $ppStep
         |  finalWidth  = $finalWidth
         |  resultWidth = $resultWidth
         |  maxStage    = $maxStage
         |  latency     = ${BoothRadix4Multiplier.latency(width, maxStage)}
         |
         |booth bias:
         |  biasSum     = ${hex(biasSum)}
         |  corrSigned  = ${signedHex(corrSigned)}
         |  corrMod     = ${hex(corrMod)}
         |
         |corr compressed:
         |  corrWeight  = $corrWeight
         |  corrWidth   = $corrWidth
         |  corrVal     = ${hex(corrVal)}
         |  expanded    = ${hex(corrVal << corrWeight)}
         |""".stripMargin
    )

    private val signCorr =
        0.U(width.W) -
            Mux(x1(width - 1), x2, 0.U(width.W)) -
            Mux(x2(width - 1), x1, 0.U(width.W))

    private val ppStageData = Wire(new Bundle {
        val pps      = Vec(numGroups, UInt(ppWidth.W))
        val signCorr = UInt(width.W)
    })

    ppStageData.pps      := pps
    ppStageData.signCorr := signCorr

    private val ppStageReg = pipeData(ppStageData)

    private val tree = Module(
      new CompTree(
        ppNum = numGroups,
        ppWidth = ppWidth,
        ppStep = ppStep,
        corrWidth = corrWidth,
        corrWeight = corrWeight,
        maxStage = maxStage,
        passWidth = width
      )
    )

    for (i <- 0 until numGroups)
        tree.ppio(i) := ppStageReg.pps(i)

    tree.corr   := corrVal.U(corrWidth.W)
    tree.passIn := ppStageReg.signCorr

    private val product = (tree.suma + tree.sumb)(resultWidth - 1, 0)

    private val finalData = Wire(new Bundle {
        val lo   = UInt(width.W)
        val hi_u = UInt(width.W)
        val hi_s = UInt(width.W)
    })

    finalData.lo   := product(width - 1, 0)
    finalData.hi_u := product(resultWidth - 1, width)
    finalData.hi_s := product(resultWidth - 1, width) + tree.passOut

    private val finalReg = pipeData(finalData)

    io.out := finalReg
}
