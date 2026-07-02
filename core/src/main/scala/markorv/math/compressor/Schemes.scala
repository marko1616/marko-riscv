package markorv.math.compressor

import chisel3._

final case class AddendInfo(
    value: UInt,
    weight: Int,
    width: Int
)

final case class CompTreePipeState(
    addends: Seq[AddendInfo],
    corr: UInt,
    pass: UInt
)
