package markorv.manage

import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import markorv.utils.ChiselUtils._
import markorv.config._

class RenameTableDebug extends DPIClockedVoidFunctionImport {
    val functionName = "update_rt"
    override val inputNames = Some(Seq("table", "index"))
}

class RenameTable(implicit val c: CoreConfig) extends Module {
    private val phyRegWidth = log2Ceil(c.regFileSize)
    private val renameIndexWidth = log2Ceil(c.renameTableSize)

    val io = IO(new Bundle {
        val readIndices = Input(Vec(2, UInt(renameIndexWidth.W)))
        val readEntries = Output(Vec(2, Vec(31,UInt(phyRegWidth.W))))
        val tailIndex = Output(UInt(renameIndexWidth.W))
        val tailEntry = Output(Vec(31,UInt(phyRegWidth.W)))

        val createCkpt = Flipped(Decoupled(Vec(31,UInt(phyRegWidth.W))))
        val rmLastCkpt = Input(Bool())
        val restoreIndex = Flipped(Valid(UInt(renameIndexWidth.W)))
    })

    val table = RegInit(VecInit.tabulate(c.renameTableSize, 31){
        (x, y) => (if(x == 0) y else 0).U(phyRegWidth.W)
    })
    val enqPtr = RegInit(1.U(renameIndexWidth.W))
    val deqPtr = RegInit(0.U(renameIndexWidth.W))
    val mayFull = RegInit(false.B)
    val ptrMatch = enqPtr === deqPtr
    val full = ptrMatch && mayFull
    val tailIndex = enqPtr - 1.U

    for((readIndex, readEntry) <- io.readIndices.zip(io.readEntries)) {
        readEntry := table(readIndex)
    }

    io.tailIndex := tailIndex
    io.tailEntry := table(tailIndex)

    io.createCkpt.ready := !full
    when(io.createCkpt.valid && !full) {
        table(enqPtr) := io.createCkpt.bits
        enqPtr := enqPtr + 1.U
        mayFull := true.B
    }

    when(io.rmLastCkpt) {
        deqPtr := deqPtr + 1.U
        mayFull := false.B
    }

    when(io.restoreIndex.valid) {
        enqPtr := 1.U
        deqPtr := 0.U
        table(0) := table(io.restoreIndex.bits)
        mayFull := false.B
    }

    // Debug
    if(c.simulate) {
        for (i <- 0 until c.renameTableSize) {
            val paddedTable = VecInit.tabulate(31){
                x => table(i)(x).asTypeOf(UInt(32.W))
            }
            val debugger = new RenameTableDebug
            debugger.call(paddedTable, i.U(32.W))
        }
    }
}