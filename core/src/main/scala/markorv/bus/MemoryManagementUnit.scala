package markorv.bus

import chisel3._
import chisel3.util._

import markorv.cache.{
    DCachePaReadReq,
    DCachePaReadResp,
    TlbAllocateReq,
    TlbInvalidateReq,
    TlbInvalidateResp,
    TlbPageLevel,
    TlbPte,
    TranslationLookasideBuffer
}
import markorv.config.{CoreConfig, TlbConfig}
import markorv.utils.ChiselUtils.DataOperationExtension

class MemoryManagementUnit(implicit val c: CoreConfig) extends Module {
    implicit val tlb4KCfg: TlbConfig = c.tlb4KConfig
    implicit val tlb2MCfg: TlbConfig = c.tlb2MConfig
    implicit val tlb1GCfg: TlbConfig = c.tlb1GConfig

    val io = IO(new Bundle {
        val paReadReq  = Decoupled(new DCachePaReadReq)
        val paReadResp = Flipped(Valid(new DCachePaReadResp()(c.dcacheConfig)))

        val mmuReqs  = Vec(2, Flipped(Decoupled(new MmuReq)))
        val mmuResps = Vec(2, Valid(new MmuResp))

        val ppn  = Input(UInt(44.W))
        val asid = Input(UInt(c.asidWidth.W))

        // TLB invalidation interface (from SFENCE.VMA)
        val tlbInvalidateReq =
            Flipped(Decoupled(new TlbInvalidateReq(c.asidWidth)))
        val tlbInvalidateResp = Valid(new TlbInvalidateResp)
    })

    object State extends ChiselEnum {
        val sIdle, sTlbLookup, sPgdLookUp, sPmdLookUp, sPteLookUp, sAllocate,
            sMissResp, sInvalidate, sInvalidateResp = Value
    }

    // Regs
    val state           = RegInit(State.sIdle)
    val dCacheTxnInProg = RegInit(false.B)

    val tlbLookupsFired = RegInit(false.B)
    val reqIdReg        = RegInit(0.U(1.W))

    val transactionVaReg      = Reg(UInt(64.W))
    val transactionModeReg    = Reg(new MmuMode.Type)
    val transactionRootPpnReg = Reg(UInt(44.W))
    val transactionPgdIdx     = Reg(UInt(9.W))
    val transactionPmdIdx     = Reg(UInt(9.W))
    val transactionPteIdx     = Reg(UInt(9.W))
    val transactionOffsetIdx  = Reg(UInt(12.W))
    val transactionPmdBaseReg = Reg(UInt(44.W))
    val transactionPteBaseReg = Reg(UInt(44.W))
    val transactionPageType   = RegInit(0.U(2.W)) // 00=4K, 01=2M, 10=1G

    val respReg    = RegInit(new MmuResp().zero)
    val walkPteReg = Reg(new Pte) // saved PTE from walk for TLB allocation

    // 3 TLBs -- one per page size
    val tlb4K = Module(new TranslationLookasideBuffer()(tlb4KCfg))
    val tlb2M = Module(new TranslationLookasideBuffer()(tlb2MCfg))
    val tlb1G = Module(new TranslationLookasideBuffer()(tlb1GCfg))

    val allTlbs = Seq(tlb4K, tlb2M, tlb1G)

    // Invalidation tracking
    val invalidateAcceptedVec = RegInit(VecInit(Seq.fill(3)(false.B)))
    val invalidateRespSeenVec = RegInit(VecInit(Seq.fill(3)(false.B)))
    val invalidateReqReg      = Reg(new TlbInvalidateReq(c.asidWidth))

    // Arbiter
    val reqArb = Module(new RRArbiter(new MmuReq, 2))
    reqArb.io.in.zipWithIndex.foreach { case (in, i) => in <> io.mmuReqs(i) }

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

    // PMA -- main checker for current transaction (TLB hit / page walk leaf / etc.)
    val pmaCommChecker         = Module(new PMAChecker(c.pma))
    val actionPmaCommCheckAddr = WireDefault(0.U(64.W))
    pmaCommChecker.io.addr := actionPmaCommCheckAddr
    pmaCommChecker.io.size := 3.U

    // PMA -- dedicated checker for acceptReq bare-mode path (breaks combinational cycle)
    val pmaAcceptChecker         = Module(new PMAChecker(c.pma))
    val actionPmaAcceptCheckAddr = WireDefault(0.U(64.W))
    pmaAcceptChecker.io.addr := actionPmaAcceptCheckAddr
    pmaAcceptChecker.io.size := 3.U

    val pmaWalkChecker = Module(new PMAChecker(c.pma))
    // Must be norm mem
    val walkPmaSucc = pmaWalkChecker.io.attr.r && pmaWalkChecker.io.attr.w &&
        pmaWalkChecker.io.attr.x && pmaWalkChecker.io.attr.a &&
        pmaWalkChecker.io.attr.c
    val actionPmaWalkCheckAddr = WireDefault(0.U(64.W))
    pmaWalkChecker.io.addr := actionPmaWalkCheckAddr
    pmaWalkChecker.io.size := 3.U

    // Action wires
    val actionSetTransactionValid         = WireDefault(false.B)
    val actionSetTransactionReqId         = WireDefault(0.U(2.W))
    val actionSetTransactionVa            = WireDefault(0.U(64.W))
    val actionSetTransactionMode          = WireDefault(MmuMode.bare)
    val actionSetTransactionRootPpn       = WireDefault(0.U(44.W))
    val actionSetTransactionPgdIdx        = WireDefault(0.U(9.W))
    val actionSetTransactionPmdIdx        = WireDefault(0.U(9.W))
    val actionSetTransactionPteIdx        = WireDefault(0.U(9.W))
    val actionSetTransactionOffsetIdx     = WireDefault(0.U(12.W))
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

    val actionSaveWalkPte = WireDefault(false.B)
    val actionWalkPteBits = Wire(new Pte)
    actionWalkPteBits := new Pte().zero

    val tlbInvPending    = io.tlbInvalidateReq.valid
    val tlbHitRespFiring = WireDefault(false.B)

    // Defaults
    io.paReadReq.valid      := actionWalkReadValid
    io.paReadReq.bits.paddr := actionWalkReadAddr

    io.mmuResps.foreach { out =>
        out.valid := false.B
        out.bits  := new MmuResp().zero
    }
    io.mmuResps(reqIdReg).valid := (state === State.sMissResp)
    io.mmuResps(reqIdReg).bits  := respReg

    reqArb.io.out.ready := ((state === State.sIdle) ||
        (state === State.sMissResp) ||
        tlbHitRespFiring) && !tlbInvPending

    // Default TLB connections
    allTlbs.foreach { tlb =>
        tlb.io.lookupReq.valid     := false.B
        tlb.io.lookupReq.bits      := 0.U.asTypeOf(tlb.io.lookupReq.bits)
        tlb.io.allocateReq.valid   := false.B
        tlb.io.allocateReq.bits    := 0.U.asTypeOf(tlb.io.allocateReq.bits)
        tlb.io.invalidateReq.valid := false.B
        tlb.io.invalidateReq.bits  := 0.U.asTypeOf(tlb.io.invalidateReq.bits)
    }

    // Invalidation output defaults
    io.tlbInvalidateReq.ready      := false.B
    io.tlbInvalidateResp.valid     := false.B
    io.tlbInvalidateResp.bits.done := false.B

    // Walk response decode
    val walkPte = Wire(new Pte)
    walkPte.fromRaw(io.paReadResp.bits.data)

    // Helpers
    def ptePpn(pte: Pte): UInt = Cat(pte.ppn2, pte.ppn1, pte.ppn0)

    def isCanonicalSv39(va: UInt): Bool = va(63, 39) === Fill(25, va(38))

    def mkCommonFaultResp(): MmuResp = new MmuResp().zero

    def mkWalkPmaFaultResp(): MmuResp = {
        val r = WireDefault(new MmuResp().zero)
        r.walkPmaFault := true.B
        r
    }

    // Bare-mode response using pmaCommChecker (used from sIdle / sMissResp paths)
    def mkBareResp(pa: UInt): MmuResp = {
        val r = WireDefault(new MmuResp().zero)
        r.pa           := pa
        r.valid        := true.B
        r.walkPmaFault := false.B
        r.pmaRead      := pmaCommChecker.io.attr.r
        r.pmaWrite     := pmaCommChecker.io.attr.w
        r.pmaExec      := pmaCommChecker.io.attr.x
        r.pteRead      := true.B
        r.pteWrite     := true.B
        r.pteExec      := true.B
        r.user         := true.B
        r.global       := true.B
        r.dirty        := true.B
        r.accessed     := true.B
        r.cache        := pmaCommChecker.io.attr.c
        r.atomic       := pmaCommChecker.io.attr.a
        r
    }

    // Bare-mode response using pmaAcceptChecker (used from acceptReq to break comb cycle)
    def mkBareRespAccept(pa: UInt): MmuResp = {
        val r = WireDefault(new MmuResp().zero)
        r.pa           := pa
        r.valid        := true.B
        r.walkPmaFault := false.B
        r.pmaRead      := pmaAcceptChecker.io.attr.r
        r.pmaWrite     := pmaAcceptChecker.io.attr.w
        r.pmaExec      := pmaAcceptChecker.io.attr.x
        r.pteRead      := true.B
        r.pteWrite     := true.B
        r.pteExec      := true.B
        r.user         := true.B
        r.global       := true.B
        r.dirty        := true.B
        r.accessed     := true.B
        r.cache        := pmaAcceptChecker.io.attr.c
        r.atomic       := pmaAcceptChecker.io.attr.a
        r
    }

    def mkPageResp(pa: UInt, pte: Pte, mmuChecks: Seq[Bool]): MmuResp = {
        val r        = WireDefault(new MmuResp().zero)
        val mmuValid = mmuChecks.reduce(_ && _)
        r.pa           := Mux(mmuValid, pa, 0.U)
        r.valid        := mmuValid
        r.walkPmaFault := false.B
        r.pmaRead      := pmaCommChecker.io.attr.r
        r.pmaWrite     := pmaCommChecker.io.attr.w
        r.pmaExec      := pmaCommChecker.io.attr.x
        r.pteRead      := pte.r
        r.pteWrite     := pte.w
        r.pteExec      := pte.x
        r.user         := pte.u
        r.global       := pte.g
        r.dirty        := pte.d
        r.accessed     := pte.a
        r.cache        := pmaCommChecker.io.attr.c
        r.atomic       := pmaCommChecker.io.attr.a
        r
    }

    // Build MmuResp from a TLB hit -- PMA is re-checked combinationally
    def mkTlbHitResp(paddr: UInt, tlbPte: TlbPte): MmuResp = {
        val r = WireDefault(new MmuResp().zero)
        r.pa           := paddr
        r.valid        := true.B
        r.walkPmaFault := false.B
        r.pmaRead      := pmaCommChecker.io.attr.r
        r.pmaWrite     := pmaCommChecker.io.attr.w
        r.pmaExec      := pmaCommChecker.io.attr.x
        r.pteRead      := tlbPte.r
        r.pteWrite     := tlbPte.w
        r.pteExec      := tlbPte.x
        r.user         := tlbPte.u
        r.global       := tlbPte.g
        r.dirty        := tlbPte.d
        r.accessed     := tlbPte.a
        r.cache        := pmaCommChecker.io.attr.c
        r.atomic       := pmaCommChecker.io.attr.a
        r
    }

    def pteReservedInvalid(pte: Pte): Bool =
        pte.n || pte.pbmt.orR || pte.pad.orR
    def pteAttrInvalid(pte: Pte): Bool    = !pte.v || (!pte.r && pte.w)
    def pteIsLeaf(pte: Pte): Bool         = pte.r || pte.x
    def pteNonLeafInvalid(pte: Pte): Bool = pte.u || pte.a || pte.d

    def mkPa1G(pte: Pte): UInt =
        Cat(
          0.U(8.W),
          pte.ppn2,
          transactionPmdIdx,
          transactionPteIdx,
          transactionOffsetIdx
        )
    def mkPa2M(pte: Pte): UInt =
        Cat(
          0.U(8.W),
          pte.ppn2,
          pte.ppn1,
          transactionPteIdx,
          transactionOffsetIdx
        )
    def mkPa4K(pte: Pte): UInt =
        Cat(0.U(8.W), pte.ppn2, pte.ppn1, pte.ppn0, transactionOffsetIdx)

    def acceptReqBase(va: UInt, id: UInt, mode: MmuMode.Type): Unit = {
        actionSetTransactionValid         := true.B
        actionSetTransactionReqId         := id
        actionSetTransactionVa            := va
        actionSetTransactionMode          := mode
        actionSetTransactionRootPpn       := io.ppn
        actionSetTransactionPgdIdx        := va(38, 30)
        actionSetTransactionPmdIdx        := va(29, 21)
        actionSetTransactionPteIdx        := va(20, 12)
        actionSetTransactionOffsetIdx     := va(11, 0)
        actionSetTransactionPageTypeValid := true.B
        actionSetTransactionPageType      := 0.U
    }

    // Accept a new request: bare mode resolves immediately.
    def acceptReq(
        nextState: State.Type,
        va: UInt,
        mode: MmuMode.Type,
        id: UInt
    ): Unit = {
        acceptReqBase(va, id, mode)

        when(mode === MmuMode.bare) {
            actionPmaAcceptCheckAddr := va
            actionSetRespValid       := true.B
            actionSetRespBits        := mkBareRespAccept(va)
            nextState                := State.sMissResp
        }.elsewhen(mode === MmuMode.sv39) {
            when(!isCanonicalSv39(va)) {
                actionSetRespValid := true.B
                actionSetRespBits  := mkCommonFaultResp()
                tlbLookupsFired    := false.B
                nextState          := State.sMissResp
            }.otherwise {
                // Initiate parallel TLB lookup on all 3 TLBs
                val earlyFired = fireTlbLookups(va, io.asid)
                tlbLookupsFired := earlyFired
                nextState       := State.sTlbLookup
            }
        }.otherwise {
            // Caution! shouldn't reach here
            actionSetRespValid := true.B
            actionSetRespBits  := mkCommonFaultResp()
            tlbLookupsFired    := false.B
            nextState          := State.sMissResp
        }
    }

    // Fire TLB lookups to all 3 TLBs simultaneously
    def fireTlbLookups(va: UInt, asid: UInt): Bool = {
        val allReady = allTlbs.map(_.io.lookupReq.ready).reduce(_ && _)

        when(allReady) {
            Seq(
              (tlb4K, tlb4KCfg),
              (tlb2M, tlb2MCfg),
              (tlb1G, tlb1GCfg)
            ).foreach { case (tlb, cfg) =>
                tlb.io.lookupReq.valid      := true.B
                tlb.io.lookupReq.bits.vaddr := va
                tlb.io.lookupReq.bits.asid  := asid
            }
        }
        allReady
    }

    // FSM
    switch(state) {
        is(State.sIdle) {
            val nextState = WireDefault(state)

            // Accept invalidation with highest priority
            io.tlbInvalidateReq.ready := true.B

            when(io.tlbInvalidateReq.fire) {
                invalidateReqReg      := io.tlbInvalidateReq.bits
                invalidateAcceptedVec := VecInit(Seq.fill(3)(false.B))
                invalidateRespSeenVec := VecInit(Seq.fill(3)(false.B))
                nextState             := State.sInvalidate
            }.elsewhen(reqArb.io.out.fire) {
                acceptReq(nextState, reqVa, reqMode, reqId)
            }

            state := nextState
        }

        // TLB parallel lookup
        is(State.sTlbLookup) {
            val nextState = WireDefault(state)

            when(!tlbLookupsFired) {
                val fired = fireTlbLookups(transactionVaReg, io.asid)
                when(fired)(tlbLookupsFired := true.B)
            }

            val resp4K = tlb4K.io.lookupResp
            val resp2M = tlb2M.io.lookupResp
            val resp1G = tlb1G.io.lookupResp

            val anyResp = resp4K.valid || resp2M.valid || resp1G.valid

            when(anyResp) {
                val hit4K = resp4K.valid && resp4K.bits.hit
                val hit2M = resp2M.valid && resp2M.bits.hit
                val hit1G = resp1G.valid && resp1G.bits.hit

                val anyHit = hit4K || hit2M || hit1G

                when(anyHit) {
                    // Priority: 4K > 2M > 1G (most specific first)
                    val hitPaddr = MuxCase(
                      0.U,
                      Seq(
                        hit4K -> resp4K.bits.paddr,
                        hit2M -> resp2M.bits.paddr,
                        hit1G -> resp1G.bits.paddr
                      )
                    )

                    val hitPte = MuxCase(
                      new TlbPte().zero,
                      Seq(
                        hit4K -> resp4K.bits.pte,
                        hit2M -> resp2M.bits.pte,
                        hit1G -> resp1G.bits.pte
                      )
                    )

                    // Re-check PMA for the physical address (uses pmaCommChecker)
                    actionPmaCommCheckAddr := hitPaddr

                    // Drive response directly on io (bypass respReg)
                    val tlbResp = mkTlbHitResp(hitPaddr, hitPte)
                    io.mmuResps(reqIdReg).valid := true.B
                    io.mmuResps(reqIdReg).bits  := tlbResp

                    // Signal that we are producing a TLB-hit response this cycle,
                    // so the arbiter can hand us the next request immediately
                    tlbHitRespFiring := true.B

                    // Accept next request in the same cycle (like sMissResp does)
                    // acceptReq uses pmaAcceptChecker, so no combinational cycle
                    when(reqArb.io.out.fire) {
                        acceptReq(nextState, reqVa, reqMode, reqId)
                    }.otherwise {
                        nextState := State.sIdle
                    }
                }.otherwise {
                    // All TLBs missed -- proceed with page table walk
                    nextState := State.sPgdLookUp
                }
            }

            state := nextState
        }

        // Page table walk: PGD level
        is(State.sPgdLookUp) {
            val nextState = WireDefault(state)
            actionPmaWalkCheckAddr := transactionPgdAddr

            actionWalkReadValid := walkPmaSucc && !dCacheTxnInProg
            actionWalkReadAddr  := transactionPgdAddr

            when(io.paReadReq.fire)(dCacheTxnInProg := true.B)

            when(!walkPmaSucc) {
                dCacheTxnInProg    := false.B
                actionSetRespValid := true.B
                actionSetRespBits  := mkWalkPmaFaultResp()
                nextState          := State.sMissResp
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
                    nextState          := State.sMissResp
                }.elsewhen(leaf) {
                    val superPageInvalid = walkPte.ppn1.orR || walkPte.ppn0.orR
                    val pa               = mkPa1G(walkPte)
                    actionPmaCommCheckAddr            := pa
                    actionSetTransactionPageTypeValid := true.B
                    actionSetTransactionPageType      := "b10".U
                    actionSetRespValid                := true.B
                    actionSetRespBits := mkPageResp(
                      pa,
                      walkPte,
                      Seq(!reservedInvalid, !attrInvalid, !superPageInvalid)
                    )

                    // Save PTE for TLB allocation
                    actionSaveWalkPte := true.B
                    actionWalkPteBits := walkPte

                    nextState := Mux(
                      !reservedInvalid && !attrInvalid && !superPageInvalid,
                      State.sAllocate,
                      State.sMissResp
                    )
                }.otherwise {
                    actionSetPmdBaseValid := true.B
                    actionSetPmdBase      := ptePpn(walkPte)
                    nextState             := State.sPmdLookUp
                }
            }

            state := nextState
        }

        // Page table walk: PMD level
        is(State.sPmdLookUp) {
            val nextState = WireDefault(state)
            actionPmaWalkCheckAddr := transactionPmdAddr

            actionWalkReadValid := walkPmaSucc && !dCacheTxnInProg
            actionWalkReadAddr  := transactionPmdAddr

            when(io.paReadReq.fire)(dCacheTxnInProg := true.B)

            when(!walkPmaSucc) {
                dCacheTxnInProg    := false.B
                actionSetRespValid := true.B
                actionSetRespBits  := mkWalkPmaFaultResp()
                nextState          := State.sMissResp
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
                    nextState          := State.sMissResp
                }.elsewhen(leaf) {
                    val superPageInvalid = walkPte.ppn0.orR
                    val pa               = mkPa2M(walkPte)
                    actionPmaCommCheckAddr            := pa
                    actionSetTransactionPageTypeValid := true.B
                    actionSetTransactionPageType      := "b01".U
                    actionSetRespValid                := true.B
                    actionSetRespBits := mkPageResp(
                      pa,
                      walkPte,
                      Seq(!reservedInvalid, !attrInvalid, !superPageInvalid)
                    )

                    actionSaveWalkPte := true.B
                    actionWalkPteBits := walkPte

                    nextState := Mux(
                      !reservedInvalid && !attrInvalid && !superPageInvalid,
                      State.sAllocate,
                      State.sMissResp
                    )
                }.otherwise {
                    actionSetPteBaseValid := true.B
                    actionSetPteBase      := ptePpn(walkPte)
                    nextState             := State.sPteLookUp
                }
            }

            state := nextState
        }

        // Page table walk: PTE level
        is(State.sPteLookUp) {
            val nextState = WireDefault(state)
            actionPmaWalkCheckAddr := transactionPteAddr

            actionWalkReadValid := walkPmaSucc && !dCacheTxnInProg
            actionWalkReadAddr  := transactionPteAddr

            when(io.paReadReq.fire)(dCacheTxnInProg := true.B)

            when(!walkPmaSucc) {
                dCacheTxnInProg    := false.B
                actionSetRespValid := true.B
                actionSetRespBits  := mkWalkPmaFaultResp()
                nextState          := State.sMissResp
            }

            when(io.paReadResp.valid) {
                val reservedInvalid = pteReservedInvalid(walkPte)
                val attrInvalid     = pteAttrInvalid(walkPte)
                val leaf            = pteIsLeaf(walkPte)
                dCacheTxnInProg := false.B

                when(reservedInvalid || attrInvalid || !leaf) {
                    actionSetRespValid := true.B
                    actionSetRespBits  := mkCommonFaultResp()
                    nextState          := State.sMissResp
                }.otherwise {
                    val pa = mkPa4K(walkPte)
                    actionPmaCommCheckAddr            := pa
                    actionSetTransactionPageTypeValid := true.B
                    actionSetTransactionPageType      := "b00".U
                    actionSetRespValid                := true.B
                    actionSetRespBits := mkPageResp(
                      pa,
                      walkPte,
                      Seq(!reservedInvalid, !attrInvalid, leaf)
                    )

                    actionSaveWalkPte := true.B
                    actionWalkPteBits := walkPte

                    nextState := State.sAllocate
                }
            }

            state := nextState
        }

        // TLB allocation after successful walk
        is(State.sAllocate) {
            val nextState = WireDefault(state)

            // Determine target TLB and page level based on transactionPageType
            val level = WireDefault(TlbPageLevel.page4KiB)
            when(transactionPageType === "b01".U) {
                level := TlbPageLevel.page2MiB
            }
            when(transactionPageType === "b10".U) {
                level := TlbPageLevel.page1GiB
            }

            val is4K = transactionPageType === "b00".U
            val is2M = transactionPageType === "b01".U
            val is1G = transactionPageType === "b10".U

            // 4K TLB allocate
            when(is4K) {
                val req = Wire(new TlbAllocateReq(c.asidWidth))
                req.vaddr := transactionVaReg
                req.asid  := io.asid
                req.level := level
                req.pte.fromPte(walkPteReg)
                tlb4K.io.allocateReq.valid := true.B
                tlb4K.io.allocateReq.bits  := req
            }

            // 2M TLB allocate
            when(is2M) {
                val req = Wire(new TlbAllocateReq(c.asidWidth))
                req.vaddr := transactionVaReg
                req.asid  := io.asid
                req.level := level
                req.pte.fromPte(walkPteReg)
                tlb2M.io.allocateReq.valid := true.B
                tlb2M.io.allocateReq.bits  := req
            }

            // 1G TLB allocate
            when(is1G) {
                val req = Wire(new TlbAllocateReq(c.asidWidth))
                req.vaddr := transactionVaReg
                req.asid  := io.asid
                req.level := level
                req.pte.fromPte(walkPteReg)
                tlb1G.io.allocateReq.valid := true.B
                tlb1G.io.allocateReq.bits  := req
            }

            // Proceed to sMissResp once target TLB accepts
            val targetFired = MuxCase(
              false.B,
              Seq(
                is4K -> tlb4K.io.allocateReq.fire,
                is2M -> tlb2M.io.allocateReq.fire,
                is1G -> tlb1G.io.allocateReq.fire
              )
            )

            when(targetFired) {
                nextState := State.sMissResp
            }
            // else stay in sAllocate until the target TLB is ready

            state := nextState
        }

        // Response (only for page-walk / bare / fault paths)
        is(State.sMissResp) {
            val nextState = WireDefault(state)

            when(io.mmuResps(reqIdReg).fire) {
                nextState := State.sIdle

                when(reqArb.io.out.fire) {
                    acceptReq(nextState, reqVa, reqMode, reqId)
                }
            }

            state := nextState
        }

        is(State.sInvalidate) {
            val nextState = WireDefault(state)

            val invalidateReqFireVec   = Wire(Vec(3, Bool()))
            val invalidateRespValidVec = Wire(Vec(3, Bool()))
            val acceptedNextVec        = Wire(Vec(3, Bool()))
            val respSeenNextVec        = Wire(Vec(3, Bool()))

            invalidateReqFireVec   := VecInit(Seq.fill(3)(false.B))
            invalidateRespValidVec := VecInit(Seq.fill(3)(false.B))

            Seq(
              (tlb4K, 0),
              (tlb2M, 1),
              (tlb1G, 2)
            ).foreach { case (tlb, idx) =>
                invalidateRespValidVec(idx) := tlb.io.invalidateResp.valid

                when(!invalidateAcceptedVec(idx)) {
                    tlb.io.invalidateReq.valid      := true.B
                    tlb.io.invalidateReq.bits.mode  := invalidateReqReg.mode
                    tlb.io.invalidateReq.bits.asid  := invalidateReqReg.asid
                    tlb.io.invalidateReq.bits.vaddr := invalidateReqReg.vaddr

                    invalidateReqFireVec(idx) := tlb.io.invalidateReq.fire
                }
            }

            for (i <- 0 until 3) {
                acceptedNextVec(i) := invalidateAcceptedVec(
                  i
                ) || invalidateReqFireVec(i)
                respSeenNextVec(i) := invalidateRespSeenVec(
                  i
                ) || invalidateRespValidVec(i)
            }

            invalidateAcceptedVec := acceptedNextVec
            invalidateRespSeenVec := respSeenNextVec

            val allAccepted = acceptedNextVec.asUInt.andR
            val allRespSeen = respSeenNextVec.asUInt.andR
            when(allAccepted && allRespSeen) {
                nextState := State.sInvalidateResp
            }

            state := nextState
        }

        is(State.sInvalidateResp) {
            io.tlbInvalidateResp.valid     := true.B
            io.tlbInvalidateResp.bits.done := true.B
            state                          := State.sIdle
        }
    }

    // Commit
    when(actionSetTransactionValid) {
        reqIdReg              := actionSetTransactionReqId
        transactionVaReg      := actionSetTransactionVa
        transactionModeReg    := actionSetTransactionMode
        transactionRootPpnReg := actionSetTransactionRootPpn
        transactionPgdIdx     := actionSetTransactionPgdIdx
        transactionPmdIdx     := actionSetTransactionPmdIdx
        transactionPteIdx     := actionSetTransactionPteIdx
        transactionOffsetIdx  := actionSetTransactionOffsetIdx
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

    when(actionSaveWalkPte) {
        walkPteReg := actionWalkPteBits
    }
}
