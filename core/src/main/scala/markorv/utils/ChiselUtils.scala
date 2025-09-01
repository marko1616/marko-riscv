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
    }

    implicit class UIntOperationExtension(x: UInt) {
        def sextu(len: Int): UInt = x.asSInt.pad(len).asUInt
        def zextu(len: Int): UInt = x.pad(len).asUInt
        def sexts(len: Int): SInt = x.asSInt.pad(len).asSInt
        def zexts(len: Int): SInt = x.pad(len).asSInt
        def neg: UInt = (~x)+1.U
    }

    implicit class SIntOperationExtension(x: SInt) {
        def sextu(len: Int): UInt = x.asUInt.asSInt.pad(len).asUInt
        def zextu(len: Int): UInt = x.asUInt.pad(len).asUInt
        def sexts(len: Int): SInt = x.asUInt.asSInt.pad(len).asSInt
        def zexts(len: Int): SInt = x.asUInt.pad(len).asSInt
        def neg: SInt = -x
    }
}