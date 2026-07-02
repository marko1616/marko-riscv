package markorv.backend

import chisel3._
import chisel3.util._

import markorv.config.CoreConfig
import markorv.math.CountLeadingZeros
import markorv.utils.ChiselUtils.{
    DataOperationExtension,
    SIntOperationExtension,
    UIntOperationExtension
}
import markorv.math.multiplier.BoothRadix4Multiplier
import markorv.math.divider.SRTDivider
import markorv.frontend.DecodedParams
import markorv.manage.RegisterCommit
import markorv.manage.EXUParams

class MultiplyDivisionUnit(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val mduInstr = Flipped(Decoupled(new Bundle {
            val mduOpcode = new MDUOpcode
            val params    = new EXUParams
        }))
        val commit = Decoupled(new MDUCommit)

        val flush   = Input(Bool())
        val outfire = Output(Bool())
    })

    private val mulLatency =
        BoothRadix4Multiplier.latency(64, c.mulCompTreeMaxStage)

    private val divLatency =
        SRTDivider.latency(
          base = c.dividerBase,
          width = 65,
          remLeadBits = c.dividerRemLeadBits,
          divisorLeadBits = c.dividerDivisorLeadBits,
          maxStage = c.dividerMaxStage
        )

    private val mduLatency    = math.max(mulLatency, divLatency)
    private val mulPadLatency = mduLatency - mulLatency
    private val divPadLatency = mduLatency - divLatency

    require(mulLatency > 0, "multiplier latency must be positive")
    require(divLatency > 0, "divider latency must be positive")

    val opcode = io.mduInstr.bits.mduOpcode
    val params = io.mduInstr.bits.params

    val multiplier =
        Module(
          new BoothRadix4Multiplier(width = 64, maxStage = c.mulCompTreeMaxStage)
        )

    val divider =
        Module(
          new SRTDivider(
            base = c.dividerBase,
            width = 65,
            remLeadBits = c.dividerRemLeadBits,
            divisorLeadBits = c.dividerDivisorLeadBits,
            maxStage = c.dividerMaxStage
          )
        )

    val isDiv = opcode.funct3(2)
    val op32  = opcode.op32

    val op64Funct3 = opcode.getFunct3Op64()
    val op32Funct3 = opcode.getFunct3Op32()

    val robIndexWidth = params.robIndex.getWidth

    class MulPipeMeta extends Bundle {
        val valid      = Bool()
        val robIndex   = UInt(robIndexWidth.W)
        val op32       = Bool()
        val op64Funct3 = MultiplyDivisionUnitFunct3Op64()

        val src1Sign = Bool()
        val src2     = UInt(64.W)
    }

    class DivPipeMeta extends Bundle {
        val valid      = Bool()
        val robIndex   = UInt(robIndexWidth.W)
        val op32       = Bool()
        val op32Funct3 = MultiplyDivisionUnitFunct3Op32()
        val op64Funct3 = MultiplyDivisionUnitFunct3Op64()
    }

    class RespPipeEntry extends Bundle {
        val valid = Bool()
        val bits  = new MDUCommit
    }

    def zeroMulMeta: MulPipeMeta =
        0.U.asTypeOf(new MulPipeMeta)

    def zeroDivMeta: DivPipeMeta =
        0.U.asTypeOf(new DivPipeMeta)

    def zeroRespEntry: RespPipeEntry =
        0.U.asTypeOf(new RespPipeEntry)

    def padRespPipe(in: RespPipeEntry, stages: Int): RespPipeEntry = {
        if (stages == 0) {
            in
        } else {
            val regs = RegInit(VecInit(Seq.fill(stages)(zeroRespEntry)))

            when(io.flush) {
                for (i <- 0 until stages) {
                    regs(i) := zeroRespEntry
                }
            }.otherwise {
                regs(0) := in
                for (i <- 1 until stages) {
                    regs(i) := regs(i - 1)
                }
            }

            regs(stages - 1)
        }
    }

    val mulMetaPipe =
        RegInit(VecInit(Seq.fill(mulLatency)(zeroMulMeta)))

    val divMetaPipe =
        RegInit(VecInit(Seq.fill(divLatency)(zeroDivMeta)))

    val respValid = RegInit(false.B)
    val respBits  = RegInit((new MDUCommit).zero)

    io.commit.valid := respValid
    io.commit.bits  := respBits

    val respSlotFree = !respValid || io.commit.fire

    // 固定延迟流水线：不再需要 divider idle / div cache / div result buffer。
    io.mduInstr.ready := !io.flush && respSlotFree

    val mulReqFire = io.mduInstr.fire && !isDiv
    val divReqFire = io.mduInstr.fire && isDiv

    // Divide request sign.
    val divSign = Mux(
      op32,
      MuxLookup(op32Funct3, false.B)(
        Seq(
          MultiplyDivisionUnitFunct3Op32.divw -> true.B,
          MultiplyDivisionUnitFunct3Op32.remw -> true.B
        )
      ),
      MuxLookup(op64Funct3, false.B)(
        Seq(
          MultiplyDivisionUnitFunct3Op64.div -> true.B,
          MultiplyDivisionUnitFunct3Op64.rem -> true.B
        )
      )
    )

    // ------------------------
    // Multiply request
    // ------------------------
    val mulSrc1 = Wire(UInt(64.W))
    val mulSrc2 = Wire(UInt(64.W))

    when(op32) {
        mulSrc1 := params.source1(31, 0).sextu(64)
        mulSrc2 := params.source2(31, 0).sextu(64)
    }.otherwise {
        mulSrc1 := params.source1
        mulSrc2 := params.source2
    }

    val mulMetaIn = Wire(new MulPipeMeta)
    mulMetaIn.valid      := mulReqFire
    mulMetaIn.robIndex   := params.robIndex
    mulMetaIn.op32       := op32
    mulMetaIn.op64Funct3 := op64Funct3
    mulMetaIn.src1Sign   := mulSrc1(63)
    mulMetaIn.src2       := mulSrc2

    multiplier.io.in.x1 := Mux(mulReqFire, mulSrc1, 0.U(64.W))
    multiplier.io.in.x2 := Mux(mulReqFire, mulSrc2, 0.U(64.W))

    when(io.flush) {
        for (i <- 0 until mulLatency) {
            mulMetaPipe(i) := zeroMulMeta
        }
    }.otherwise {
        mulMetaPipe(0) := mulMetaIn
        for (i <- 1 until mulLatency) {
            mulMetaPipe(i) := mulMetaPipe(i - 1)
        }
    }

    // ------------------------
    // Divide request
    // ------------------------
    val divSrc1 = Wire(SInt(65.W))
    val divSrc2 = Wire(SInt(65.W))

    when(op32) {
        divSrc1 := Mux(
          divSign,
          params.source1(31, 0).sextu(65),
          params.source1(31, 0).zextu(65)
        ).asSInt

        divSrc2 := Mux(
          divSign,
          params.source2(31, 0).sextu(65),
          params.source2(31, 0).zextu(65)
        ).asSInt
    }.otherwise {
        divSrc1 := Mux(
          divSign,
          params.source1.sextu(65),
          params.source1.zextu(65)
        ).asSInt

        divSrc2 := Mux(
          divSign,
          params.source2.sextu(65),
          params.source2.zextu(65)
        ).asSInt
    }

    val divMetaIn = Wire(new DivPipeMeta)
    divMetaIn.valid      := divReqFire
    divMetaIn.robIndex   := params.robIndex
    divMetaIn.op32       := op32
    divMetaIn.op32Funct3 := op32Funct3
    divMetaIn.op64Funct3 := op64Funct3

    // SRTDivider 没有 valid，靠外部 metadata pipe 追踪有效性。
    // invalid bubble 用 0 / 1，避免无意义的 div-by-zero 路径。
    divider.io.in.dividend := Mux(divReqFire, divSrc1, 0.S(65.W))
    divider.io.in.divisor  := Mux(divReqFire, divSrc2, 1.S(65.W))

    when(io.flush) {
        for (i <- 0 until divLatency) {
            divMetaPipe(i) := zeroDivMeta
        }
    }.otherwise {
        divMetaPipe(0) := divMetaIn
        for (i <- 1 until divLatency) {
            divMetaPipe(i) := divMetaPipe(i - 1)
        }
    }

    // ------------------------
    // Result selection
    // ------------------------
    def selectMulResult(meta: MulPipeMeta): UInt = {
        val out = multiplier.io.out

        val hiHsu =
            out.hi_u - Mux(meta.src1Sign, meta.src2, 0.U(64.W))

        Mux(
          meta.op32,
          out.lo(31, 0).sextu(64),
          MuxLookup(meta.op64Funct3, out.lo)(
            Seq(
              MultiplyDivisionUnitFunct3Op64.mul    -> out.lo,
              MultiplyDivisionUnitFunct3Op64.mulh   -> out.hi_s,
              MultiplyDivisionUnitFunct3Op64.mulhsu -> hiHsu,
              MultiplyDivisionUnitFunct3Op64.mulhu  -> out.hi_u
            )
          )
        )
    }

    def selectDivResult(
        quotient65: SInt,
        remainder65: SInt,
        rOp32: Bool,
        rOp32Funct3: MultiplyDivisionUnitFunct3Op32.Type,
        rOp64Funct3: MultiplyDivisionUnitFunct3Op64.Type
    ): UInt = {
        val quotient  = quotient65.asUInt
        val remainder = remainder65.asUInt

        Mux(
          rOp32,
          MuxLookup(rOp32Funct3, remainder(31, 0).sextu(64))(
            Seq(
              MultiplyDivisionUnitFunct3Op32.divw -> quotient(31, 0).sextu(64),
              MultiplyDivisionUnitFunct3Op32.divuw -> quotient(31, 0).sextu(64)
            )
          ),
          MuxLookup(rOp64Funct3, remainder(63, 0))(
            Seq(
              MultiplyDivisionUnitFunct3Op64.div  -> quotient(63, 0),
              MultiplyDivisionUnitFunct3Op64.divu -> quotient(63, 0)
            )
          )
        )
    }

    val mulRawMeta  = mulMetaPipe(mulLatency - 1)
    val divRawMeta  = divMetaPipe(divLatency - 1)
    val mulRawValid = mulRawMeta.valid && !io.flush
    val divRawValid = divRawMeta.valid && !io.flush

    val mulRawResp = Wire(new RespPipeEntry)
    mulRawResp          := zeroRespEntry
    mulRawResp.valid    := mulRawValid
    mulRawResp.bits     := new MDUCommit().zero
    mulRawResp.bits.robIndex := mulRawMeta.robIndex
    mulRawResp.bits.data     := selectMulResult(mulRawMeta)

    val divRawResp = Wire(new RespPipeEntry)
    divRawResp          := zeroRespEntry
    divRawResp.valid    := divRawValid
    divRawResp.bits     := new MDUCommit().zero
    divRawResp.bits.robIndex := divRawMeta.robIndex
    divRawResp.bits.data := selectDivResult(
      divider.io.out.quotient,
      divider.io.out.remainder,
      divRawMeta.op32,
      divRawMeta.op32Funct3,
      divRawMeta.op64Funct3
    )

    // 延迟低的一侧补拍到 mduLatency。
    // 因为 MDU 入口每周期最多 fire 一条指令，所以统一总延迟后不会同周期双结果。
    val mulFinalResp = padRespPipe(mulRawResp, mulPadLatency)
    val divFinalResp = padRespPipe(divRawResp, divPadLatency)

    assert(
      !(mulFinalResp.valid && divFinalResp.valid),
      "MDU mul/div results should not collide after latency padding"
    )

    val pipeRespValid = (mulFinalResp.valid || divFinalResp.valid) && !io.flush

    val pipeRespBits = Wire(new MDUCommit)
    pipeRespBits := new MDUCommit().zero

    when(mulFinalResp.valid) {
        pipeRespBits := mulFinalResp.bits
    }.elsewhen(divFinalResp.valid) {
        pipeRespBits := divFinalResp.bits
    }

    when(io.flush) {
        respValid := false.B
    }.elsewhen(respSlotFree && pipeRespValid) {
        respValid := true.B
        respBits  := pipeRespBits
    }.elsewhen(io.commit.fire) {
        respValid := false.B
    }

    io.outfire := io.mduInstr.fire
}