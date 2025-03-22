package markorv.frontend

import chisel3._
import chisel3.util._

object InstrType extends ChiselEnum {
    val R, I, S, B, U, J = Value
}