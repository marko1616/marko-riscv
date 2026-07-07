package markorv.trap

import chisel3._
import chisel3.util._

import markorv.config.CoreConfig

class TrapUnit(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        // Interrupt signals
        // ========================
        val meip = Input(Bool())
        val mtip = Input(Bool())
        val msip = Input(Bool())
        val seip = Input(Bool())
        val stip = Input(Bool())
        val ssip = Input(Bool())

        // Exception signals
        // ========================
        val exception   = Flipped(Valid(new ExceptionInfo))
        val handleTrap  = Flipped(new TrapHandleInterface)
        val trapRet     = Flipped(Valid(new TrapReturnType.Type))
        val trapRetInfo = Input(new TrapRetState)

        // Flush control signals
        // ========================
        val flush   = Output(Bool())
        val flushPc = Output(UInt(64.W))
        val pc      = Input(UInt(64.W))

        // Privilege control signals
        // ========================
        val privilege    = Input(UInt(2.W))
        val setPrivilege = Valid(UInt(2.W))

        // CSR status signals
        // ========================
        val mstatus = Input(UInt(64.W))
        val mie     = Input(UInt(64.W))
        val medeleg = Input(UInt(64.W))
        val mideleg = Input(UInt(64.W))
        val sie     = Input(UInt(64.W))

        // Pipeline control signals
        // ========================
        val interruptHlt  = Output(Bool())
        val interruptXepc = Flipped(Valid(UInt(64.W)))
    })
    val interruptCode = WireInit(0.U(4.W))
    val trapInfo      = io.handleTrap.trapInfo

    val globalMie = io.mstatus(3)
    val globalSie = io.mstatus(1)

    val sInterruptEnable =
        (io.privilege < "b01".U) || (io.privilege === "b01".U && globalSie)
    val mInterruptEnable =
        (io.privilege < "b11".U) || (io.privilege === "b11".U && globalMie)

    def doInterrupt(code: UInt) = {
        interruptCode   := code
        io.interruptHlt := true.B
    }

    io.interruptHlt       := false.B
    io.flush              := false.B
    io.flushPc            := 0.U
    io.setPrivilege.valid := false.B
    io.setPrivilege.bits  := 0.U

    io.handleTrap.set        := false.B
    trapInfo.interruption    := false.B
    trapInfo.causeCode       := 0.U
    trapInfo.state.trapPc    := 0.U
    trapInfo.state.xtval     := 0.U

    // S-mode: SEI(9) > STI(5) > SSI(1)
    when(sInterruptEnable) {
        when(io.seip && io.sie(9) && io.mideleg(9))(doInterrupt(9.U))
            .elsewhen(io.stip && io.sie(5) && io.mideleg(5)) {
                doInterrupt(5.U)
            }
            .elsewhen(io.ssip && io.sie(1) && io.mideleg(1)) {
                doInterrupt(1.U)
            }
    }

    // M-mode: MEI(11) > MSI(3) > MTI(7) > SEI(9) > SSI(1) > STI(5)
    when(mInterruptEnable) {
        when(io.meip && io.mie(11) && ~io.mideleg(11))(doInterrupt(11.U))
            .elsewhen(io.msip && io.mie(3) && ~io.mideleg(3)) {
                doInterrupt(3.U)
            }
            .elsewhen(io.mtip && io.mie(7) && ~io.mideleg(7)) {
                doInterrupt(7.U)
            }
            .elsewhen(io.seip && io.mie(9) && ~io.mideleg(9)) {
                doInterrupt(9.U)
            }
            .elsewhen(io.ssip && io.mie(1) && ~io.mideleg(1)) {
                doInterrupt(1.U)
            }
            .elsewhen(io.stip && io.mie(5) && ~io.mideleg(5)) {
                doInterrupt(5.U)
            }
    }

    when(io.trapRet.valid) {
        io.flush              := true.B
        io.flushPc            := io.trapRetInfo.pc
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits  := io.trapRetInfo.priv
    }

    when(interruptCode =/= 0.U && io.interruptXepc.valid) {
        io.handleTrap.set := true.B

        trapInfo.interruption    := true.B
        trapInfo.causeCode       := interruptCode
        trapInfo.state.trapPc    := io.interruptXepc.bits
        trapInfo.state.xtval     := 0.U

        io.flush              := true.B
        io.flushPc            := io.handleTrap.trapHandler
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits  := io.handleTrap.privilege
    }

    when(io.exception.valid) {
        io.handleTrap.set := true.B

        trapInfo.interruption    := false.B
        trapInfo.causeCode       := io.exception.bits.cause
        trapInfo.state.trapPc    := io.exception.bits.xepc
        trapInfo.state.xtval     := io.exception.bits.xtval

        io.flush              := true.B
        io.flushPc            := io.handleTrap.trapHandler
        io.setPrivilege.valid := true.B
        io.setPrivilege.bits  := io.handleTrap.privilege
    }
}
