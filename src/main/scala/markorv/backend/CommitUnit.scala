package markorv.backend

import chisel3._
import chisel3.util._

class CommitUnit extends Module {
    val io = IO(new Bundle {
        val register_commits = Vec(5, Flipped(Decoupled(new RegisterCommit))
        )

        val reg_write = Output(UInt(5.W))
        val write_data = Output(UInt(64.W))
        val instret = Output(UInt(1.W))
    })

    for (i <- 0 until io.register_commits.length) {
        io.register_commits(i).ready := true.B
    }

    io.reg_write := 0.U
    io.write_data := 0.U
    io.instret := 0.U

    // Impossible to write back multiple times in one cycle.
    for (i <- 0 until io.register_commits.length) {
        when(io.register_commits(i).valid) {
            io.instret := 1.U
            io.reg_write := io.register_commits(i).bits.reg
            io.write_data := io.register_commits(i).bits.data
        }
    }
}
