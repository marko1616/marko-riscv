package markorv.bus

import chisel3._
import chisel3.util._

import markorv.utils.ChiselUtils._
import markorv.config._
import markorv.cache._

class MemoryManagementUnit(implicit val c: CoreConfig) extends Module {
    val io = IO(new Bundle {
        val paReadReq = Decoupled(new DCachePaReadReq)
        val paReadResp = Flipped(Valid(new DCachePaReadResp()(c.dcacheConfig)))

        val mmuReqs = Vec(2, Flipped(Decoupled(new MMUReq)))
        val mmuResps = Vec(2, Valid(new MmuResp))

        val ppn  = Input(UInt(44.W))
        val asid = Input(UInt(c.asidWidth.W))
    })

    object State extends ChiselEnum {
        val sIdle, sPgdLookUp, sPmdLookUp, sPteLookUp, sResp = Value
    }

    // Regs
    val state = RegInit(State.sIdle)
    val dCacheTxnInProg = RegInit(false.B)

    val reqIdReg = RegInit(0.U(1.W))

    val transactionVaReg        = Reg(UInt(64.W))
    val transactionRootPpnReg   = Reg(UInt(44.W))
    val transactionPgdIdx       = Reg(UInt(9.W))
    val transactionPmdIdx       = Reg(UInt(9.W))
    val transactionPteIdx       = Reg(UInt(9.W))
    val transactionOffsetIdx    = Reg(UInt(12.W))
    val transactionPmdBaseReg   = Reg(UInt(44.W))
    val transactionPteBaseReg   = Reg(UInt(44.W))
    val transactionPageType     = RegInit(0.U(2.W)) // 00=4K, 01=2M, 10=1G

    val respReg = RegInit(new MmuResp().zero)

    // Arbiter
    val reqArb = Module(new RRArbiter(new MMUReq, 2))
    reqArb.io.in.zipWithIndex.foreach { case (in, i) =>
        in <> io.mmuReqs(i)
    }

    val reqVa        = reqArb.io.out.bits.va
    val reqMode      = reqArb.io.out.bits.mode
    val reqId        = reqArb.io.chosen
    val reqPgdIdx    = reqVa(38, 30)
    val reqPmdIdx    = reqVa(29, 21)
    val reqPteIdx    = reqVa(20, 12)
    val reqOffsetIdx = reqVa(11, 0)

    // Address helpers
    val transactionPgdBase = Cat(0.U(8.W), transactionRootPpnReg, 0.U(12.W))
    val transactionPmdBase = Cat(0.U(8.W), transactionPmdBaseReg, 0.U(12.W))
    val transactionPteBase = Cat(0.U(8.W), transactionPteBaseReg, 0.U(12.W))

    val transactionPgdAddr = transactionPgdBase + (transactionPgdIdx << 3)
    val transactionPmdAddr = transactionPmdBase + (transactionPmdIdx << 3)
    val transactionPteAddr = transactionPteBase + (transactionPteIdx << 3)

    // PMA
    val pmaCommChecker = Module(new PMAChecker(c.pma))
    val actionPmaCommCheckAddr = WireDefault(0.U(64.W))
    pmaCommChecker.io.addr := actionPmaCommCheckAddr
    pmaCommChecker.io.size := 3.U

    val pmaWalkChecker = Module(new PMAChecker(c.pma))
    // Must be norm mem
    val walkPmaSucc = pmaWalkChecker.io.attr.r && pmaWalkChecker.io.attr.w && pmaWalkChecker.io.attr.x && pmaWalkChecker.io.attr.a && pmaWalkChecker.io.attr.c
    val actionPmaWalkCheckAddr = WireDefault(0.U(64.W))
    pmaWalkChecker.io.addr := actionPmaWalkCheckAddr
    pmaWalkChecker.io.size := 3.U

    // Action wires
    val actionSetTransactionValid     = WireDefault(false.B)
    val actionSetTransactionReqId     = WireDefault(0.U(2.W))
    val actionSetTransactionVa        = WireDefault(0.U(64.W))
    val actionSetTransactionRootPpn   = WireDefault(0.U(44.W))
    val actionSetTransactionPgdIdx    = WireDefault(0.U(9.W))
    val actionSetTransactionPmdIdx    = WireDefault(0.U(9.W))
    val actionSetTransactionPteIdx    = WireDefault(0.U(9.W))
    val actionSetTransactionOffsetIdx = WireDefault(0.U(12.W))
    val actionSetTransactionPageTypeValid = WireDefault(false.B)
    val actionSetTransactionPageType      = WireDefault(0.U(2.W))

    val actionSetPmdBaseValid = WireDefault(false.B)
    val actionSetPmdBase      = WireDefault(0.U(44.W))

    val actionSetPteBaseValid = WireDefault(false.B)
    val actionSetPteBase      = WireDefault(0.U(44.W))

    val actionSetRespValid = WireDefault(false.B)
    val actionSetRespBits  = WireDefault(new MmuResp().zero)

    val actionWalkReadValid = WireDefault(false.B)
    val actionWalkReadAddr  = WireDefault(0.U(64.W))

    // Defaults
    io.paReadReq.valid := actionWalkReadValid
    io.paReadReq.bits.paddr := actionWalkReadAddr

    io.mmuResps.foreach { out =>
        out.valid := false.B
        out.bits  := new MmuResp().zero
    }
    io.mmuResps(reqIdReg).valid := (state === State.sResp)
    io.mmuResps(reqIdReg).bits  := respReg

    reqArb.io.out.ready := (state === State.sIdle) || (state === State.sResp)

    // Helpers
    def ptePpn(pte: Pte): UInt = Cat(pte.ppn2, pte.ppn1, pte.ppn0)

    def isCanonicalSv39(va: UInt): Bool = {
        va(63, 39) === Fill(25, va(38))
    }

    def mkCommonFaultResp(): MmuResp = {
        new MmuResp().zero
    }

    def mkWalkPmaFaultResp(): MmuResp = {
        val r = WireDefault(new MmuResp().zero)
        r.walkPmaFault := true.B
        r
    }

    def mkBareResp(pa: UInt): MmuResp = {
        val r = WireDefault(new MmuResp().zero)
        r.pa       := pa
        r.valid    := true.B
        r.walkPmaFault := false.B
        r.pmaRead  := pmaCommChecker.io.attr.r
        r.pmaWrite := pmaCommChecker.io.attr.w
        r.pmaExec  := pmaCommChecker.io.attr.x
        r.pteRead  := true.B
        r.pteWrite := true.B
        r.pteExec  := true.B
        r.user     := true.B
        r.global   := true.B
        r.dirty    := true.B
        r.accessed := true.B
        r.cache    := pmaCommChecker.io.attr.c
        r.atomic   := pmaCommChecker.io.attr.a
        r
    }

    def mkPageResp(pa: UInt, pte: Pte, mmuChecks: Seq[Bool]): MmuResp = {
        val r = WireDefault(new MmuResp().zero)
        val mmuValid = mmuChecks.reduce(_ && _)

        r.pa       := Mux(mmuValid, pa, 0.U)
        r.valid    := mmuValid
        r.walkPmaFault := false.B
        r.pmaRead  := pmaCommChecker.io.attr.r
        r.pmaWrite := pmaCommChecker.io.attr.w
        r.pmaExec  := pmaCommChecker.io.attr.x
        r.pteRead  := pte.r
        r.pteWrite := pte.w
        r.pteExec  := pte.x
        r.user     := pte.u
        r.global   := pte.g
        r.dirty    := pte.d
        r.accessed := pte.a
        r.cache    := pmaCommChecker.io.attr.c
        r.atomic   := pmaCommChecker.io.attr.a
        r
    }

    def pteReservedInvalid(pte: Pte): Bool = {
        pte.n || pte.pbmt.orR || pte.pad.orR
    }

    def pteAttrInvalid(pte: Pte): Bool = {
        !pte.v || (!pte.r && pte.w)
    }

    def pteIsLeaf(pte: Pte): Bool = {
        pte.r || pte.x
    }

    def pteNonLeafInvalid(pte: Pte): Bool = {
        pte.u || pte.a || pte.d
    }

    def mkPa1G(pte: Pte): UInt = {
        Cat(0.U(8.W), pte.ppn2, transactionPmdIdx, transactionPteIdx, transactionOffsetIdx)
    }

    def mkPa2M(pte: Pte): UInt = {
        Cat(0.U(8.W), pte.ppn2, pte.ppn1, transactionPteIdx, transactionOffsetIdx)
    }

    def mkPa4K(pte: Pte): UInt = {
        Cat(0.U(8.W), pte.ppn2, pte.ppn1, pte.ppn0, transactionOffsetIdx)
    }

    def acceptReqBase(va: UInt, id: UInt): Unit = {
        actionSetTransactionValid     := true.B
        actionSetTransactionReqId     := id
        actionSetTransactionVa        := va
        actionSetTransactionRootPpn   := io.ppn
        actionSetTransactionPgdIdx    := va(38, 30)
        actionSetTransactionPmdIdx    := va(29, 21)
        actionSetTransactionPteIdx    := va(20, 12)
        actionSetTransactionOffsetIdx := va(11, 0)
        actionSetTransactionPageTypeValid := true.B
        actionSetTransactionPageType      := 0.U
    }

    def acceptReq(nextState: State.Type, va: UInt, mode: MmuMode.Type, id: UInt): Unit = {
        acceptReqBase(va, id)

        when(mode === MmuMode.bare) {
            actionPmaCommCheckAddr := va
            actionSetRespValid := true.B
            actionSetRespBits  := mkBareResp(va)

            nextState := State.sResp
        }.elsewhen(mode === MmuMode.sv39) {
            when(!isCanonicalSv39(va)) {
                actionSetRespValid := true.B
                actionSetRespBits  := mkCommonFaultResp()

                nextState := State.sResp
            }.otherwise {
                nextState := State.sPgdLookUp
            }
        }.otherwise {
            // Caution! shouldn't reach here
            actionSetRespValid := true.B
            actionSetRespBits  := mkCommonFaultResp()

            nextState := State.sResp
        }
    }

    // Walk response decode
    val walkPte = Wire(new Pte)
    walkPte.fromRaw(io.paReadResp.bits.data)

    // FSM
    switch(state) {
        is(State.sIdle) {
            val nextState = WireDefault(state)

            when(reqArb.io.out.fire) {
                acceptReq(nextState, reqVa, reqMode, reqId)
            }

            state := nextState
        }

        is(State.sPgdLookUp) {
            val nextState = WireDefault(state)
            actionPmaWalkCheckAddr := transactionPgdAddr

            actionWalkReadValid := true.B && walkPmaSucc && !dCacheTxnInProg
            actionWalkReadAddr  := transactionPgdAddr

            when(io.paReadReq.fire) {
                dCacheTxnInProg := true.B
            }

            when(!walkPmaSucc) {
                dCacheTxnInProg := false.B
                actionSetRespValid := true.B
                actionSetRespBits  := mkWalkPmaFaultResp()
                nextState := State.sResp
            }

            when(io.paReadResp.valid) {
                val reservedInvalid = pteReservedInvalid(walkPte)
                val attrInvalid     = pteAttrInvalid(walkPte)
                val leaf            = pteIsLeaf(walkPte)
                val nonLeafInvalid  = !leaf && pteNonLeafInvalid(walkPte)
                dCacheTxnInProg := false.B

                when(reservedInvalid || attrInvalid || nonLeafInvalid) {
                    actionSetRespValid := true.B
                    actionSetRespBits  := mkCommonFaultResp()
                    nextState := State.sResp
                }.elsewhen(leaf) {
                    val superPageInvalid = walkPte.ppn1.orR || walkPte.ppn0.orR
                    val pa = mkPa1G(walkPte)

                    actionPmaCommCheckAddr := pa
                    actionSetTransactionPageTypeValid := true.B
                    actionSetTransactionPageType      := "b10".U
                    actionSetRespValid := true.B
                    actionSetRespBits  := mkPageResp(pa, walkPte, Seq(!reservedInvalid, !attrInvalid, !superPageInvalid))
                    nextState := State.sResp
                }.otherwise {
                    actionSetPmdBaseValid := true.B
                    actionSetPmdBase      := ptePpn(walkPte)
                    nextState := State.sPmdLookUp
                }
            }

            state := nextState
        }

        is(State.sPmdLookUp) {
            val nextState = WireDefault(state)
            actionPmaWalkCheckAddr := transactionPmdAddr

            actionWalkReadValid := true.B && walkPmaSucc && !dCacheTxnInProg
            actionWalkReadAddr  := transactionPmdAddr

            when(io.paReadReq.fire) {
                dCacheTxnInProg := true.B
            }

            when(!walkPmaSucc) {
                dCacheTxnInProg := false.B
                actionSetRespValid := true.B
                actionSetRespBits  := mkWalkPmaFaultResp()
                nextState := State.sResp
            }

            when(io.paReadResp.valid) {
                val reservedInvalid = pteReservedInvalid(walkPte)
                val attrInvalid     = pteAttrInvalid(walkPte)
                val leaf            = pteIsLeaf(walkPte)
                val nonLeafInvalid  = !leaf && pteNonLeafInvalid(walkPte)
                dCacheTxnInProg := false.B
                when(reservedInvalid || attrInvalid || nonLeafInvalid) {
                    actionSetRespValid := true.B
                    actionSetRespBits  := mkCommonFaultResp()
                    nextState := State.sResp
                }.elsewhen(leaf) {
                    val superPageInvalid = walkPte.ppn0.orR
                    val pa = mkPa2M(walkPte)

                    actionPmaCommCheckAddr := pa
                    actionSetTransactionPageTypeValid := true.B
                    actionSetTransactionPageType      := "b01".U
                    actionSetRespValid := true.B
                    actionSetRespBits  := mkPageResp(pa, walkPte, Seq(!reservedInvalid, !attrInvalid, !superPageInvalid))
                    nextState := State.sResp
                }.otherwise {
                    actionSetPteBaseValid := true.B
                    actionSetPteBase      := ptePpn(walkPte)
                    nextState := State.sPteLookUp
                }
            }

            state := nextState
        }

        is(State.sPteLookUp) {
            val nextState = WireDefault(state)
            actionPmaWalkCheckAddr := transactionPteAddr

            actionWalkReadValid := true.B && walkPmaSucc && !dCacheTxnInProg
            actionWalkReadAddr  := transactionPteAddr

            when(io.paReadReq.fire) {
                dCacheTxnInProg := true.B
            }

            when(!walkPmaSucc) {
                dCacheTxnInProg := false.B
                actionSetRespValid := true.B
                actionSetRespBits  := mkWalkPmaFaultResp()
                nextState := State.sResp
            }

            when(io.paReadResp.valid) {
                val reservedInvalid = pteReservedInvalid(walkPte)
                val attrInvalid     = pteAttrInvalid(walkPte)
                val leaf            = pteIsLeaf(walkPte)
                dCacheTxnInProg := false.B
                when(reservedInvalid || attrInvalid || !leaf) {
                    actionSetRespValid := true.B
                    actionSetRespBits  := mkCommonFaultResp()
                    nextState := State.sResp
                }.otherwise {
                    val pa = mkPa4K(walkPte)
                    actionPmaCommCheckAddr := pa
                    actionSetTransactionPageTypeValid := true.B
                    actionSetTransactionPageType      := "b00".U
                    actionSetRespValid := true.B
                    actionSetRespBits  := mkPageResp(pa, walkPte, Seq(!reservedInvalid, !attrInvalid, leaf))
                    nextState := State.sResp
                }
            }

            state := nextState
        }

        is(State.sResp) {
            val nextState = WireDefault(state)

            when(io.mmuResps(reqIdReg).fire) {
                nextState := State.sIdle

                when(reqArb.io.out.fire) {
                    acceptReq(nextState, reqVa, reqMode, reqId)
                }
            }

            state := nextState
        }
    }

    // Commit
    when(actionSetTransactionValid) {
        reqIdReg                := actionSetTransactionReqId
        transactionVaReg        := actionSetTransactionVa
        transactionRootPpnReg   := actionSetTransactionRootPpn
        transactionPgdIdx       := actionSetTransactionPgdIdx
        transactionPmdIdx       := actionSetTransactionPmdIdx
        transactionPteIdx       := actionSetTransactionPteIdx
        transactionOffsetIdx    := actionSetTransactionOffsetIdx
    }

    when(actionSetTransactionPageTypeValid) {
        transactionPageType := actionSetTransactionPageType
    }

    when(actionSetPmdBaseValid) {
        transactionPmdBaseReg := actionSetPmdBase
    }

    when(actionSetPteBaseValid) {
        transactionPteBaseReg := actionSetPteBase
    }

    when(actionSetRespValid) {
        respReg := actionSetRespBits
    }
}