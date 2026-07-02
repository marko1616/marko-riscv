package markorv.math.compressor

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils.UIntOperationExtension

class CompTree(
    ppNum: Int,
    ppWidth: Int,
    ppStep: Int,
    corrWidth: Int,
    corrWeight: Int,
    maxStage: Int,
    passWidth: Int
) extends Module {
    private val finalWidth = ppWidth + (ppNum - 1) * ppStep

    require(
      finalWidth >= corrWidth + corrWeight,
      "assert corrWidth + corrWeight <= ppWidth + (ppNum - 1) * ppStep"
    )
    require(ppNum >= 2, s"ppNum must be >= 2, got $ppNum")
    require(ppWidth > 0, s"ppWidth must be positive, got $ppWidth")
    require(ppStep > 0, s"ppStep must be positive, got $ppStep")
    require(corrWidth > 0, s"corrWidth must be positive, got $corrWidth")
    require(
      corrWeight >= 0,
      s"corrWeight must be non-negative, got $corrWeight"
    )
    require(maxStage >= 1, s"maxStage must be >= 1, got $maxStage")
    require(passWidth > 0, s"passWidth must be positive, got $passWidth")

    private def dbg(msg: => String): Unit =
        println(
          s"[CompTree ppNum=$ppNum ppWidth=$ppWidth ppStep=$ppStep maxStage=$maxStage] $msg"
        )

    private def fmtAddend(a: AddendInfo): String =
        s"(weight=${a.weight}, width=${a.width})"

    private def fmtAddends(xs: Seq[AddendInfo]): String =
        xs.zipWithIndex
            .map { case (a, i) => s"#$i${fmtAddend(a)}" }
            .mkString("[", ", ", "]")

    val ppio    = IO(Input(Vec(ppNum, UInt(ppWidth.W))))
    val corr    = IO(Input(UInt(corrWidth.W)))
    val passIn  = IO(Input(UInt(passWidth.W)))
    val suma    = IO(Output(UInt(finalWidth.W)))
    val sumb    = IO(Output(UInt(finalWidth.W)))
    val passOut = IO(Output(UInt(passWidth.W)))

    private def padAddend(
        info: AddendInfo,
        minWeight: Int,
        targetWidth: Int
    ): UInt = {
        val shift = info.weight - minWeight
        require(shift >= 0)
        (info.value.zextu(targetWidth) << shift)(targetWidth - 1, 0)
    }

    private def compress4to2(
        group: Seq[AddendInfo],
        tag: String
    ): Seq[AddendInfo] = {
        require(group.length == 4)

        val minWeight = group.map(_.weight).min
        val maxBit    = group.map(a => a.weight + a.width).max
        val compWidth = maxBit - minWeight

        dbg(
          s"$tag C42: minWeight=$minWeight, maxBit=$maxBit, compWidth=$compWidth, in=${fmtAddends(group)}"
        )

        val c42 = Module(new FourToTwoCompGroup(compWidth))
        c42.io.x0 := padAddend(group(0), minWeight, compWidth)
        c42.io.x1 := padAddend(group(1), minWeight, compWidth)
        c42.io.x2 := padAddend(group(2), minWeight, compWidth)
        c42.io.x3 := padAddend(group(3), minWeight, compWidth)

        val out = Seq(
          AddendInfo(c42.io.sum, minWeight, compWidth + 1),
          AddendInfo(c42.io.carry, minWeight + 1, compWidth)
        )
        dbg(s"$tag C42 out=${fmtAddends(out)}")
        out
    }

    private def compress3to2(
        group: Seq[AddendInfo],
        tag: String
    ): Seq[AddendInfo] = {
        require(group.length == 3)

        val minWeight = group.map(_.weight).min
        val maxBit    = group.map(a => a.weight + a.width).max
        val compWidth = maxBit - minWeight

        dbg(
          s"$tag C32: minWeight=$minWeight, maxBit=$maxBit, compWidth=$compWidth, in=${fmtAddends(group)}"
        )

        val c32 = Module(new ThreeToTwoCompGroup(compWidth))
        c32.io.x0 := padAddend(group(0), minWeight, compWidth)
        c32.io.x1 := padAddend(group(1), minWeight, compWidth)
        c32.io.x2 := padAddend(group(2), minWeight, compWidth)

        val out = Seq(
          AddendInfo(c32.io.sum, minWeight, compWidth),
          AddendInfo(c32.io.carry, minWeight + 1, compWidth)
        )
        dbg(s"$tag C32 out=${fmtAddends(out)}")
        out
    }

    private def pipeState(
        s: CompTreePipeState,
        tag: String
    ): CompTreePipeState = {
        dbg(
          s"$tag PIPE INSERT: addends=${fmtAddends(s.addends)}, " +
              s"corrWeight=$corrWeight, corrWidth=$corrWidth, passWidth=$passWidth"
        )

        val pipedAddends = s.addends.map { a =>
            val r = Reg(UInt(a.width.W))
            r := a.value
            a.copy(value = r)
        }

        val corrReg = Reg(UInt(corrWidth.W))
        corrReg := s.corr

        val passReg = Reg(UInt(passWidth.W))
        passReg := s.pass

        CompTreePipeState(pipedAddends, corrReg, passReg)
    }

    private def treeGenImpl(
        state: CompTreePipeState,
        currStage: Int,
        layerId: Int = 0
    ): CompTreePipeState = {
        val addendsInfo = state.addends
        require(addendsInfo.length >= 2)

        dbg(
          s"layer=$layerId enter: currStage=$currStage, " +
              s"addendNum=${addendsInfo.length}, addends=${fmtAddends(addendsInfo)}"
        )

        if (addendsInfo.length <= 2) {
            dbg(
              s"layer=$layerId stop: final addendNum=${addendsInfo.length}, " +
                  s"currStage=$currStage, addends=${fmtAddends(addendsInfo)}"
            )
            state
        } else {
            val nextLayer: Seq[AddendInfo] =
                if (addendsInfo.length == 3) {
                    dbg(s"layer=$layerId plan: one C32")
                    compress3to2(addendsInfo, s"layer=$layerId")
                } else {
                    val numGroups4 = addendsInfo.length / 4
                    val remainder  = addendsInfo.drop(numGroups4 * 4)

                    dbg(
                      s"layer=$layerId plan: C42 groups=$numGroups4, " +
                          s"remainder=${remainder.length}"
                    )

                    val compressed4 = (0 until numGroups4).flatMap { g =>
                        compress4to2(
                          addendsInfo.slice(g * 4, g * 4 + 4),
                          s"layer=$layerId group4=$g"
                        )
                    }

                    val compressedRemainder =
                        if (remainder.length == 3) {
                            compress3to2(remainder, s"layer=$layerId remainder")
                        } else {
                            if (remainder.nonEmpty) {
                                dbg(
                                  s"layer=$layerId pass remainder: ${fmtAddends(remainder)}"
                                )
                            }
                            remainder
                        }

                    compressed4 ++ compressedRemainder
                }

            val newStage = currStage + 1

            dbg(
              s"layer=$layerId exit: newStage=$newStage, " +
                  s"nextAddendNum=${nextLayer.length}, next=${fmtAddends(nextLayer)}"
            )

            if (newStage >= maxStage) {
                treeGenImpl(
                  pipeState(
                    state.copy(addends = nextLayer),
                    s"after-layer-$layerId"
                  ),
                  currStage = 0,
                  layerId = layerId + 1
                )
            } else {
                treeGenImpl(
                  state.copy(addends = nextLayer),
                  currStage = newStage,
                  layerId = layerId + 1
                )
            }
        }
    }

    val layerOneAddendsInfo: Seq[AddendInfo] = (0 until ppNum).map { i =>
        AddendInfo(value = ppio(i), weight = i * ppStep, width = ppWidth)
    }

    val initState =
        CompTreePipeState(layerOneAddendsInfo, corr, passIn)

    val finalState = treeGenImpl(initState, 0)

    val corrInfo  = AddendInfo(finalState.corr, corrWeight, corrWidth)
    val finalPair = compress3to2(finalState.addends :+ corrInfo, "final-corr")

    suma    := padAddend(finalPair(0), minWeight = 0, targetWidth = finalWidth)
    sumb    := padAddend(finalPair(1), minWeight = 0, targetWidth = finalWidth)
    passOut := finalState.pass
}
