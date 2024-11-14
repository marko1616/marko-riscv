package markorv.backend

import chisel3._
import chisel3.util._

import markorv.frontend.DecoderOutParams
import markorv.backend._

class LoadStoreUnit(data_width: Int = 64, addr_width: Int = 64) extends Module {
    val io = IO(new Bundle {
        // lsu_opcode encoding:
        // Bit [4]
        //             0 = Load
        //             1 = Store
        //
        // Bit [3]   - Flag: Load from memory or Immediate
        //             0 = Memory(base address is params.source1 offset is immediate)
        //             1 = Immediate
        //
        // Bit [2]   - Sign: Indicates if the data is signed or unsigned.
        //             0 = Signed integer (SInt)
        //             1 = Unsigned integer (UInt)
        //
        // Bits [1:0] - Size: Specifies the size of the data being loaded or stored.
        //             00 = Byte (8 bits)
        //             01 = Halfword (16 bits)
        //             10 = Word (32 bits)
        //             11 = Doubleword (64 bits)
        val lsu_instr = Flipped(Decoupled(new Bundle {
            val lsu_opcode = UInt(5.W)
            val params = new DecoderOutParams(data_width)
        }))

        val read_req = Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(64.W)
            val sign = Bool()
        })
        val read_data = Flipped(Decoupled((UInt(data_width.W))))

        val write_req = Decoupled(new Bundle {
            val size = UInt(2.W)
            val addr = UInt(addr_width.W)
            val data = UInt(data_width.W)
        })
        val write_outfire = Input(Bool())

        val write_back = Decoupled(new Bundle {
            val reg = Input(UInt(5.W))
            val data = Input(UInt(data_width.W))
        })

        val outfire = Output(Bool())
        val debug_peek = Output(UInt(64.W))
    })

    // Alias
    val opcode = io.lsu_instr.bits.lsu_opcode
    val params = io.lsu_instr.bits.params
    val write_req = io.write_req.bits

    val op_fired = Wire(Bool())
    val load_data = Wire(UInt(data_width.W))

    io.debug_peek := io.lsu_instr.valid

    // default
    io.lsu_instr.ready := false.B
    io.write_req.valid := false.B
    write_req.size := 0.U
    write_req.addr := 0.U
    write_req.data := 0.U
    io.read_req.valid := false.B
    io.read_req.bits.size := 0.U
    io.read_req.bits.addr := 0.U
    io.read_req.bits.sign := false.B
    io.read_data.ready := false.B

    io.write_back.valid := false.B
    io.write_back.bits.data := 0.U(data_width.W)
    io.write_back.bits.reg := 0.U(5.W)

    io.outfire := false.B
    op_fired := false.B
    load_data := 0.U

    when(!io.lsu_instr.valid) {
        io.lsu_instr.ready := io.write_back.ready
    }

    when(io.lsu_instr.valid && io.write_back.ready) {
        when(opcode(4) === 0.U) {
            val is_signed = !opcode(2)
            val size = opcode(1, 0)

            io.read_req.bits.size := size
            io.read_req.bits.addr := params.source1.asUInt + params.immediate.asUInt
            io.read_req.bits.sign := is_signed
            io.read_req.valid := true.B
            io.read_data.ready := true.B
            when(io.read_data.valid) {
                op_fired := true.B
                load_data := io.read_data.bits
            }
        }.otherwise {
            val size = opcode(1, 0)
            val store_data = params.source2.asUInt

            io.write_req.valid := true.B
            write_req.size := size
            write_req.addr := params.source1.asUInt + params.immediate.asUInt
            write_req.data := MuxCase(
              store_data,
              Seq(
                (size === 0.U) -> store_data(7, 0).pad(64),
                (size === 1.U) -> store_data(15, 0).pad(64),
                (size === 2.U) -> store_data(31, 0).pad(64)
                // size === 3.U is the default case (raw_data)
              )
            )

            when(io.write_outfire) {
                op_fired := true.B
            }
        }
    }

    when(op_fired) {
        when(opcode(4) === 0.U) {
            io.outfire := true.B
            io.lsu_instr.ready := true.B
            io.write_back.valid := true.B
            io.write_back.bits.data := load_data
            io.write_back.bits.reg := params.rd
        }.otherwise {
            io.outfire := true.B
            io.lsu_instr.ready := true.B
        }
    }
}
