package markorv

import chisel3._
import _root_.circt.stage.ChiselStage
import markorv._

class MarkoRvCore extends Module {
    val io = IO(new Bundle {
    })

    val mem = Module(new Memory(64, 64, 1024))
    val instruction_buffer = RegInit(0.U(64.W))

    mem.io.addr_in := 0.U(64.W)
    mem.io.data_out.ready := true.B
    when(mem.io.data_out.valid){
    }
}

object MarkoRvCore extends App {
  ChiselStage.emitSystemVerilogFile(
    new MarkoRvCore,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}