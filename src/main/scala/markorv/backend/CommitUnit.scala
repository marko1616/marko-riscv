package markorv.backend

import chisel3._
import chisel3.util._

class CommitUnit extends Module {
    val io = IO(new Bundle {
        val registerCommits = Vec(5, Flipped(Decoupled(new CommitBundle))
        )

        val regWrite = Output(UInt(5.W))
        val writeData = Output(UInt(64.W))
        val instret = Output(UInt(1.W))
    })

    for (i <- 0 until io.registerCommits.length) {
        io.registerCommits(i).ready := true.B
    }

    io.regWrite := 0.U
    io.writeData := 0.U
    io.instret := 0.U

    // Impossible to write back multiple times in one cycle.
    for (i <- 0 until io.registerCommits.length) {
        when(io.registerCommits(i).valid) {
            io.instret := 1.U
            io.regWrite := io.registerCommits(i).bits.reg
            io.writeData := io.registerCommits(i).bits.data
        }
    }
}
