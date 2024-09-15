package markorv

import chisel3._
import chisel3.util._

object PipelineConnect {
  def apply[T <: Data](left: DecoupledIO[T], right: DecoupledIO[T], right_out_fire: Bool, is_flush: Bool) = {
    val valid = RegInit(false.B)
    when (right_out_fire) { valid := false.B }
    when (left.valid && right.ready) { valid := true.B }
    when (is_flush) { valid := false.B }

    left.ready := right.ready
    right.bits := RegEnable(left.bits, left.valid && right.ready)
    right.valid := valid
  }
}