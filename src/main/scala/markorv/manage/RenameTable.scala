package markorv.manage

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._

class RenameTable(implicit val c: CoreConfig) extends Module {
    private val phyRegWidth = log2Ceil(c.regFileSize)
    private val renameIndexWidth = log2Ceil(c.renameTableSize)

    val io = IO(new Bundle {
        val readIndex = Input(UInt(renameIndexWidth.W))
        val readEntry = Output(Vec(31,UInt(phyRegWidth.W)))
        val tailIndex = Output(UInt(renameIndexWidth.W))
        val tailEntry = Output(Vec(31,UInt(phyRegWidth.W)))

        val createCkpt = Flipped(Decoupled(Vec(31,UInt(phyRegWidth.W))))
        val rmLastCkpt = Input(Bool())
        val restoreEntry = Flipped(Valid(Vec(31,UInt(phyRegWidth.W))))
    })

    val renameTable = RegInit(VecInit.tabulate(c.renameTableSize, 31){
        (x, y) => (if(x == 0) y else 0).U(phyRegWidth.W)
    })
    val enqPtr = RegInit(1.U(renameIndexWidth.W))
    val deqPtr = RegInit(0.U(renameIndexWidth.W))
    val mayFull = RegInit(false.B)
    val ptrMatch = enqPtr === deqPtr
    val full = ptrMatch && mayFull
    val tailIndex = enqPtr - 1.U

    io.readEntry := renameTable(io.readIndex)
    io.tailIndex := tailIndex
    io.tailEntry := renameTable(tailIndex)

    io.createCkpt.ready := !full
    when(io.createCkpt.valid && !full) {
        renameTable(enqPtr) := io.createCkpt.bits
        enqPtr := enqPtr + 1.U
        mayFull := true.B
    }

    when(io.rmLastCkpt) {
        deqPtr := deqPtr + 1.U
        mayFull := false.B
    }

    when(io.restoreEntry.valid) {
        enqPtr := 1.U
        deqPtr := 0.U
        renameTable(0) := io.restoreEntry.bits
        mayFull := false.B
    }
}