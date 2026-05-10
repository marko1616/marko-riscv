package markorv.cache

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import markorv.config.TlbConfig
import markorv.utils.ChiselUtils.DataOperationExtension

class TranslationLookasideBuffer(implicit val c: TlbConfig) extends Module {
    val io = IO(new Bundle {
        val lookupReq  = Flipped(Decoupled(new TlbLookupReq(c.asidWidth)))
        val lookupResp = Valid(new TlbLookupResp(c.asidWidth))

        val allocateReq = Flipped(Decoupled(new TlbAllocateReq(c.asidWidth)))

        val invalidateReq =
            Flipped(Decoupled(new TlbInvalidateReq(c.asidWidth)))
        val invalidateResp = Valid(new TlbInvalidateResp)
    })

    object State extends ChiselEnum {
        val sIdle, sLookupResp, sAllocateWrite, sInvalidateWrite = Value
    }

    val state = RegInit(State.sInvalidateWrite)

    val entryMem = SyncReadMem(1, Vec(c.entryNum, new TlbEntry))

    val lookupReqReg   = Reg(new TlbLookupReq(c.asidWidth))
    val allocateReqReg = Reg(new TlbAllocateReq(c.asidWidth))
    val invalidateReqReg = RegInit(
      new TlbInvalidateReq(c.asidWidth).Lit(
        _.mode -> TlbInvalidateMode.byAll
      )
    )

    val victimPtr = RegInit(0.U(c.entryIdxBits.W))

    val emptyRow = Wire(Vec(c.entryNum, new TlbEntry))
    emptyRow := VecInit(Seq.fill(c.entryNum)(new TlbEntry().zero))

    val memReadValid = WireDefault(false.B)
    val memReadRow   = entryMem.read(0.U, memReadValid)

    val memWriteValid = WireDefault(false.B)
    val memWriteRow   = Wire(Vec(c.entryNum, new TlbEntry))
    memWriteRow := emptyRow

    def vaToVpn(va: UInt): UInt = va(c.sv39VpnHigh, c.pageOffsetBits)

    def pageMatch(reqVpn: UInt, entry: TlbEntry): Bool =
        MuxLookup(entry.level, false.B)(
          Seq(
            TlbPageLevel.page4KiB -> (reqVpn === entry.vpn),
            TlbPageLevel.page2MiB -> (reqVpn(26, 9) === entry.vpn(26, 9)),
            TlbPageLevel.page1GiB -> (reqVpn(26, 18) === entry.vpn(26, 18))
          )
        )

    def asidMatch(reqAsid: UInt, entry: TlbEntry): Bool =
        entry.pte.g || entry.asid === reqAsid

    def makePaddr(vaddr: UInt, entry: TlbEntry): UInt = {
        val pte       = entry.pte
        val paddr4KiB = Cat(pte.ppn, vaddr(11, 0))
        val paddr2MiB = Cat(pte.ppn2, pte.ppn1, vaddr(20, 0))
        val paddr1GiB = Cat(pte.ppn2, vaddr(29, 0))

        MuxLookup(entry.level, paddr4KiB)(
          Seq(
            TlbPageLevel.page4KiB -> paddr4KiB,
            TlbPageLevel.page2MiB -> paddr2MiB,
            TlbPageLevel.page1GiB -> paddr1GiB
          )
        )
    }

    def lookupEntryMatch(req: TlbLookupReq, entry: TlbEntry): Bool = {
        val reqVpn = vaToVpn(req.vaddr)
        entry.valid &&
        pageMatch(reqVpn, entry) &&
        asidMatch(req.asid, entry)
    }

    def invalidateVaddrMatch(vaddr: UInt, entry: TlbEntry): Bool = {
        val reqVpn = vaToVpn(vaddr)
        pageMatch(reqVpn, entry)
    }

    def allocateConflict(req: TlbAllocateReq, entry: TlbEntry): Bool = {
        val reqVpn = vaToVpn(req.vaddr)
        val samePage =
            entry.level === req.level &&
                pageMatch(reqVpn, entry)
        val sameAddressSpace =
            entry.pte.g || req.pte.g || entry.asid === req.asid
        entry.valid && samePage && sameAddressSpace
    }

    def makeAllocatedEntry(req: TlbAllocateReq): TlbEntry = {
        val e = Wire(new TlbEntry)
        e.valid := true.B
        e.asid  := req.asid
        e.vpn   := vaToVpn(req.vaddr)
        e.level := req.level
        e.pte   := req.pte
        e
    }

    // Default
    io.lookupReq.ready  := false.B
    io.lookupResp.valid := false.B
    io.lookupResp.bits  := new TlbLookupResp(c.asidWidth).zero

    io.allocateReq.ready := false.B

    io.invalidateReq.ready      := false.B
    io.invalidateResp.valid     := false.B
    io.invalidateResp.bits.done := false.B

    switch(state) {
        is(State.sIdle) {
            io.invalidateReq.ready := true.B
            io.allocateReq.ready   := !io.invalidateReq.valid
            io.lookupReq.ready := !io.invalidateReq.valid && !io.allocateReq.valid

            when(io.invalidateReq.fire) {
                invalidateReqReg := io.invalidateReq.bits
                memReadValid     := true.B
                state            := State.sInvalidateWrite
            }.elsewhen(io.allocateReq.fire) {
                allocateReqReg := io.allocateReq.bits
                memReadValid   := true.B
                state          := State.sAllocateWrite
            }.elsewhen(io.lookupReq.fire) {
                lookupReqReg := io.lookupReq.bits
                memReadValid := true.B
                state        := State.sLookupResp
            }
        }

        is(State.sLookupResp) {
            val hitVec =
                VecInit(memReadRow.map(e => lookupEntryMatch(lookupReqReg, e)))
            val hit = hitVec.asUInt.orR

            val hitEntry = Mux(
              hit,
              Mux1H((0 until c.entryNum).map(i => hitVec(i) -> memReadRow(i))),
              new TlbEntry().zero
            )

            io.lookupResp.valid      := true.B
            io.lookupResp.bits.hit   := hit
            io.lookupResp.bits.vaddr := lookupReqReg.vaddr
            io.lookupResp.bits.asid  := lookupReqReg.asid
            io.lookupResp.bits.paddr := Mux(
              hit,
              makePaddr(lookupReqReg.vaddr, hitEntry),
              0.U
            )
            io.lookupResp.bits.level := hitEntry.level
            io.lookupResp.bits.pte   := hitEntry.pte

            state := State.sIdle
        }

        is(State.sAllocateWrite) {
            val conflictVec = VecInit(
              memReadRow.map(e => allocateConflict(allocateReqReg, e))
            )
            val invalidVec = VecInit(memReadRow.map(e => !e.valid))

            val hasConflict = conflictVec.asUInt.orR
            val hasInvalid  = invalidVec.asUInt.orR

            val conflictOH = PriorityEncoderOH(conflictVec.asUInt)
            val invalidOH  = PriorityEncoderOH(invalidVec.asUInt)
            val victimOH   = UIntToOH(victimPtr, c.entryNum)

            val targetOH = Mux(
              hasConflict,
              conflictOH,
              Mux(hasInvalid, invalidOH, victimOH)
            )

            val newEntry = makeAllocatedEntry(allocateReqReg)
            val nextRow  = Wire(Vec(c.entryNum, new TlbEntry))

            for (i <- 0 until c.entryNum) {
                nextRow(i) := memReadRow(i)
                when(conflictVec(i))(nextRow(i).valid := false.B)
                when(targetOH(i))(nextRow(i)          := newEntry)
            }

            memWriteValid := true.B
            memWriteRow   := nextRow

            when(!hasConflict && !hasInvalid) {
                victimPtr := victimPtr + 1.U
            }

            state := State.sIdle
        }

        is(State.sInvalidateWrite) {
            val nextRow = Wire(Vec(c.entryNum, new TlbEntry))

            for (i <- 0 until c.entryNum) {
                val e = memReadRow(i)

                val byAll = invalidateReqReg.mode === TlbInvalidateMode.byAll

                val byAsid =
                    invalidateReqReg.mode === TlbInvalidateMode.byAsid &&
                        e.valid && !e.pte.g &&
                        e.asid === invalidateReqReg.asid

                val byVaddr =
                    invalidateReqReg.mode === TlbInvalidateMode.byVaddr &&
                        e.valid && invalidateVaddrMatch(
                          invalidateReqReg.vaddr,
                          e
                        )

                val byAsidAndVaddr =
                    invalidateReqReg.mode === TlbInvalidateMode.byAsidAndVaddr &&
                        e.valid && !e.pte.g &&
                        e.asid === invalidateReqReg.asid &&
                        invalidateVaddrMatch(invalidateReqReg.vaddr, e)

                val shouldInvalidate =
                    byAll || byAsid || byVaddr || byAsidAndVaddr

                nextRow(i) := e
                when(shouldInvalidate)(nextRow(i).valid := false.B)
            }

            memWriteValid := true.B
            memWriteRow   := nextRow

            io.invalidateResp.valid     := true.B
            io.invalidateResp.bits.done := true.B

            state := State.sIdle
        }
    }

    when(memWriteValid) {
        entryMem.write(0.U, memWriteRow)
    }
}
