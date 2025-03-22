package markorv.utils

import chisel3._
import chisel3.util._

object ChiselUtils {
    implicit class DataOperationExtension[T <: Data](x: T) {
        def in(items: T*): Bool = {
            items.foldLeft(false.B)((p, n) => {
                p || x === n
            })
        }
        def in(items: Iterable[T]): Bool = {
            this.in(items.toSeq: _*)
        }
        def zero: T = 0.U.asTypeOf(x)
        def zeroAsUInt: UInt = 0.U(x.getWidth.W)
        def sextu(len: Int): UInt = x.asUInt.asSInt.pad(len).asUInt
        def zextu(len: Int): UInt = x.asUInt.pad(len).asUInt
        def sexts(len: Int): SInt = x.asUInt.asSInt.pad(len).asSInt
        def zexts(len: Int): SInt = x.asUInt.pad(len).asSInt
    }
}