package markorv

import chisel3._
import chisel3.util._

abstract class CSRField(val width: Int) {
    def read: UInt
    def write(newValue: UInt): Bool
}

// Storage: Constant

class CSRConstField(width: Int, val constValue: UInt) extends CSRField(width) {
    override def read: UInt = constValue(width - 1, 0)
    override def write(newValue: UInt): Bool = true.B
}

class CSRZeroField(width: Int) extends CSRConstField(width, 0.U(width.W))

// Storage: Register

class CSRRegField(width: Int, resetValue: UInt = 0.U) extends CSRField(width) {
    val reg: UInt = RegInit(resetValue(width - 1, 0))

    override def read: UInt = reg
    override def write(newValue: UInt): Bool = {
        reg := newValue
        true.B
    }
}

// Storage: Mapped (read-only)

class CSRROMappedField(width: Int, val data: UInt) extends CSRField(width) {
    override def read: UInt = data
    override def write(newValue: UInt): Bool = false.B
}

// Storage: Mapped (read-write)

class CSRRWMappedField(width: Int, val data: UInt) extends CSRField(width) {
    override def read: UInt = data
    override def write(newValue: UInt): Bool = {
        data := newValue
        true.B
    }
}

// Storage: Mapped (read-write register with logical-OR external data)

class CSRRWORMappedField(width: Int, val data: UInt, resetValue: UInt = 0.U) extends CSRField(width) {
    val reg: UInt = RegInit(resetValue(width - 1, 0))

    override def read: UInt = data | reg
    override def write(newValue: UInt): Bool = {
        reg := newValue
        true.B
    }
}

// CSR base

abstract class CSR {
    val addr: UInt
    val fields: Seq[CSRField]

    protected def validateWrite(newValue: UInt): Bool = true.B
    protected def legalizeWrite(newValue: UInt): UInt = newValue

    def read: UInt = Cat(fields.reverse.map(_.read))

    def write(newValue: UInt): Bool = {
        val shouldWrite = validateWrite(newValue)
        val legalized   = legalizeWrite(newValue)
        var offset = 0
        fields.foreach { field =>
            val bits = legalized(offset + field.width - 1, offset)
            offset += field.width
            when(shouldWrite) {
                field.write(bits)
            }
        }
        true.B
    }

    // Helper: compute the bit-offset of a field within this CSR
    protected def fieldOffset(target: CSRField): Int = {
        var offset = 0
        for (f <- fields) {
            if (f eq target) return offset
            offset += f.width
        }
        assert(false, "Field not found in CSR")
        0
    }

    // Helper: extract a field's bits from a raw value
    protected def extractField(value: UInt, field: CSRField): UInt = {
        val off = fieldOffset(field)
        value(off + field.width - 1, off)
    }

    // Helper: replace a field's bits in a value, return the new full-width value
    protected def replaceField(value: UInt, field: CSRField, newBits: UInt): UInt = {
        val off        = fieldOffset(field)
        val totalWidth = fields.map(_.width).sum
        if (off == 0 && field.width == totalWidth) {
            newBits
        } else if (off == 0) {
            Cat(value(totalWidth - 1, field.width), newBits)
        } else if (off + field.width == totalWidth) {
            Cat(newBits, value(off - 1, 0))
        } else {
            Cat(value(totalWidth - 1, off + field.width), newBits, value(off - 1, 0))
        }
    }
}

class CSRAnyCSR(val addr: UInt) extends CSR {
    val field  = new CSRRegField(64)
    val fields = Seq(field)
}

class CSRConstCSR(val addr: UInt, constValue: UInt) extends CSR {
    val field  = new CSRConstField(64, constValue)
    val fields = Seq(field)
}

class CSRZeroCSR(override val addr: UInt) extends CSRConstCSR(addr, 0.U)

// Unprivileged Counter/Timers (URO)

class CSRCycle   extends CSRAnyCSR("hc00".U(12.W))
class CSRTime(timeData: UInt) extends CSR {
    val addr = "hc01".U(12.W)

    val time = new CSRROMappedField(64, timeData)
    override val fields = Seq(time)
}
class CSRInstRet extends CSRAnyCSR("hc02".U(12.W))

// Machine Information Registers (MRO)

class CSRMvendorID  extends CSRZeroCSR("hf11".U(12.W))
class CSRMarchID    extends CSRZeroCSR("hf12".U(12.W))
class CSRMimpID     extends CSRZeroCSR("hf13".U(12.W))
class CSRMhartID    extends CSRZeroCSR("hf14".U(12.W))
class CSRMconfigPtr extends CSRZeroCSR("hf15".U(12.W))

// Machine Trap Setup (MRW)

class CSRMstatus extends CSR {
    val addr = "h300".U(12.W)

    val pad0       = new CSRRegField(1)
    val sieField   = new CSRRegField(1)
    val pad1       = new CSRRegField(1)
    val mieField   = new CSRRegField(1)
    val pad2       = new CSRRegField(1)
    val spieField  = new CSRRegField(1)
    val ubeField   = new CSRZeroField(1)
    val mpieField  = new CSRRegField(1)
    val sppField   = new CSRRegField(1)
    val vsField    = new CSRZeroField(2)
    val mppField   = new CSRRegField(2, 3.U(2.W))
    val fsField    = new CSRZeroField(2)
    val xsField    = new CSRZeroField(2)
    val mprvField  = new CSRRegField(1)
    val sumField   = new CSRRegField(1)
    val mxrField   = new CSRRegField(1)
    val tvmField   = new CSRZeroField(1)
    val twField    = new CSRZeroField(1)
    val tsrField   = new CSRZeroField(1)
    val spelpField = new CSRZeroField(1)
    val sdtField   = new CSRZeroField(1)
    val pad3       = new CSRRegField(7)
    val uxlField   = new CSRZeroField(2)
    val sxlField   = new CSRZeroField(2)
    val sbeField   = new CSRZeroField(1)
    val mbeField   = new CSRZeroField(1)
    val pad4       = new CSRRegField(25)
    val sdField    = new CSRZeroField(1)

    val fields: Seq[CSRField] = Seq(
        pad0, sieField, pad1, mieField, pad2, spieField, ubeField,
        mpieField, sppField, vsField, mppField, fsField, xsField,
        mprvField, sumField, mxrField, tvmField, twField, tsrField,
        spelpField, sdtField, pad3, uxlField, sxlField, sbeField, 
        mbeField, pad4, sdField
    )

    // MPP WARL: value 2 is reserved → keep old value
    override protected def legalizeWrite(newValue: UInt): UInt = {
        val mppRaw   = extractField(newValue, mppField)
        val mppLegal = Mux(mppRaw === 2.U(2.W), mppField.reg, mppRaw)
        replaceField(newValue, mppField, mppLegal)
    }
}

class CSRMisa extends CSR {
    val addr = "h301".U(12.W)

    val extensionField = new CSRConstField(26, "h0141105".U)
    val pad            = new CSRZeroField(36)
    val mxlField       = new CSRConstField(2, 2.U)

    val fields: Seq[CSRField] = Seq(extensionField, pad, mxlField)
}

class CSRMedeleg extends CSR {
    val addr = "h302".U(12.W)

    val instrMisalignedField  = new CSRRegField(1)
    val instrAccessFaultField = new CSRRegField(1)
    val illegalInstrField     = new CSRRegField(1)
    val breakpointField       = new CSRRegField(1)
    val loadMisalignedField   = new CSRRegField(1)
    val loadAccessFaultField  = new CSRRegField(1)
    val storeMisalignedField  = new CSRRegField(1)
    val storeAccessFaultField = new CSRRegField(1)
    val ecallFromUModeField   = new CSRRegField(1)
    val ecallFromSModeField   = new CSRRegField(1)
    val ecallFromVSModeField  = new CSRZeroField(1) // Virtual Supervisor mode is not supported
    val ecallFromMModeField   = new CSRZeroField(1) // Can't delegate M-mode exceptions
    val pad                   = new CSRRegField(52)

    val fields: Seq[CSRField] = Seq(
        instrMisalignedField, instrAccessFaultField, illegalInstrField, breakpointField,
        loadMisalignedField, loadAccessFaultField, storeMisalignedField, storeAccessFaultField,
        ecallFromUModeField, ecallFromSModeField, ecallFromVSModeField, ecallFromMModeField,
        pad
    )
}
class CSRMideleg extends CSR {
    val addr = "h303".U(12.W)

    val pad0       = new CSRZeroField(1)
    val ssieField  = new CSRRegField(1)
    val pad1       = new CSRZeroField(1)
    val msieField  = new CSRZeroField(1)
    val pad2       = new CSRZeroField(1)
    val stieField  = new CSRRegField(1)
    val pad3       = new CSRZeroField(1)
    val mtieField  = new CSRZeroField(1)
    val pad4       = new CSRZeroField(1)
    val seieField  = new CSRRegField(1)
    val pad5       = new CSRZeroField(1)
    val meieField  = new CSRZeroField(1)
    val pad6       = new CSRZeroField(52)

    val fields: Seq[CSRField] = Seq(
        pad0, ssieField, pad1, msieField, pad2, stieField,
        pad3, mtieField, pad4, seieField, pad5, meieField, pad6
    )
}

class CSRMie extends CSR {
    val addr = "h304".U(12.W)

    val pad0       = new CSRRegField(1)
    val ssieField  = new CSRRegField(1)
    val pad1       = new CSRRegField(1)
    val msieField  = new CSRRegField(1)
    val pad2       = new CSRRegField(1)
    val stieField  = new CSRRegField(1)
    val pad3       = new CSRRegField(1)
    val mtieField  = new CSRRegField(1)
    val pad4       = new CSRRegField(1)
    val seieField  = new CSRRegField(1)
    val pad5       = new CSRRegField(1)
    val meieField  = new CSRRegField(1)
    val pad6       = new CSRRegField(52)

    val fields: Seq[CSRField] = Seq(
        pad0, ssieField, pad1, msieField, pad2, stieField,
        pad3, mtieField, pad4, seieField, pad5, meieField, pad6
    )
}

class CSRMtvec extends CSR {
    val addr = "h305".U(12.W)

    val modeField = new CSRRegField(2, 0.U)
    val baseField = new CSRRegField(62, 0.U)

    val fields: Seq[CSRField] = Seq(modeField, baseField)

    // MODE WARL: only Direct(0) and Vectored(1) are legal
    override protected def legalizeWrite(newValue: UInt): UInt = {
        val modeRaw   = extractField(newValue, modeField)
        val modeLegal = Mux(modeRaw >= 2.U, modeField.reg, modeRaw)
        replaceField(newValue, modeField, modeLegal)
    }
}

class CSRMcounteren extends CSR {
    val addr = "h306".U(12.W)

    val cyField = new CSRRegField(1)
    val tmField = new CSRRegField(1)
    val irField = new CSRRegField(1)
    val pad     = new CSRZeroField(61)

    val fields: Seq[CSRField] = Seq(cyField, tmField, irField, pad)
}

// Machine Counter Setup (MRW)

class CSRMcountinhibit extends CSRZeroCSR("h320".U(12.W))

// Machine Trap Handling (MRW)

class CSRMscratch extends CSRAnyCSR("h340".U(12.W))
class CSRMepc     extends CSR {
    val addr = "h341".U(12.W)

    val pad0 = new CSRZeroField(1)
    val epcField = new CSRRegField(63)
    val fields = Seq(pad0, epcField)
}

class CSRMcause extends CSR {
    val addr = "h342".U(12.W)

    val codeField      = new CSRRegField(63, 0.U)
    val interruptField = new CSRRegField(1, 0.U)

    val fields: Seq[CSRField] = Seq(codeField, interruptField)

    override protected def validateWrite(newValue: UInt): Bool = {
        val interrupt = newValue(63).asBool
        val code      = newValue(62, 0)

        val legalInterrupt = VecInit(
            Seq(1, 3, 5, 7, 9, 11).map(_.U(63.W))
        ).exists(_ === code)

        val legalException = VecInit(
            Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 15).map(_.U(63.W))
        ).exists(_ === code)

        Mux(interrupt, legalInterrupt, legalException)
    }
}

class CSRMtval extends CSR {
    val addr = "h343".U(12.W)
    val mtvalField = new CSRRegField(64)
    val fields = Seq(mtvalField)
}

class CSRMip(msipData: UInt, mtipData: UInt, meipData: UInt, stipData: UInt, seipData: UInt) extends CSR {
    val addr = "h344".U(12.W)

    val pad0       = new CSRZeroField(1)
    val ssipField  = new CSRRegField(1)
    val pad1       = new CSRZeroField(1)
    val msipField  = new CSRROMappedField(1, msipData)
    val pad2       = new CSRZeroField(1)
    val stipField  = new CSRROMappedField(1, stipData)
    val pad3       = new CSRZeroField(1)
    val mtipField  = new CSRROMappedField(1, mtipData)
    val pad4       = new CSRZeroField(1)
    val seipField  = new CSRRWORMappedField(1, seipData)
    val pad5       = new CSRZeroField(1)
    val meipField  = new CSRROMappedField(1, meipData)
    val pad6       = new CSRZeroField(52)

    val fields: Seq[CSRField] = Seq(
        pad0, ssipField, pad1, msipField, pad2, stipField,
        pad3, mtipField, pad4, seipField, pad5, meipField, pad6
    )
}

// Machine Configuration

class CSRMenvcfg extends CSR {
    val addr = "h30a".U(12.W)

    val fiomField  = new CSRZeroField(1)
    val pad0       = new CSRRegField(1)
    val lpeField   = new CSRZeroField(1)
    val sseField   = new CSRZeroField(1)
    val cbieField  = new CSRZeroField(2)
    val cbcfeFeild = new CSRZeroField(1)
    val cbzeField  = new CSRZeroField(1)
    val pad1       = new CSRRegField(24)
    val pmmField   = new CSRZeroField(2)
    val pad2       = new CSRZeroField(25)
    val dteField   = new CSRZeroField(1)
    val cdeField   = new CSRZeroField(1)
    val adueField  = new CSRZeroField(1)
    val pbmteField  = new CSRZeroField(1)
    val stceField   = new CSRRegField(1)

    val fields: Seq[CSRField] = Seq(
        fiomField, pad0, lpeField, sseField, cbieField, cbcfeFeild,
        cbzeField, pad1, pmmField, pad2, dteField, cdeField,
        adueField, pbmteField, stceField
    )
}

// Supervisor Trap Setup

class CSRSstatus(csrMstatus: CSRMstatus) extends CSR {
    val addr = "h100".U(12.W)

    val pad0       = new CSRRegField(1)
    val sieField   = new CSRRWMappedField(1, csrMstatus.sieField.reg)
    val pad1       = new CSRRegField(3)
    val spieField  = new CSRRWMappedField(1, csrMstatus.spieField.reg)
    val ubeField   = new CSRZeroField(1)
    val pad2       = new CSRRegField(1)
    val sppField   = new CSRRWMappedField(1, csrMstatus.sppField.reg)
    val vsField    = new CSRZeroField(2)
    val pad3       = new CSRRegField(2)
    val fsField    = new CSRZeroField(2)
    val xsField    = new CSRZeroField(2)
    val pad4       = new CSRRegField(1)
    val sumField   = new CSRRWMappedField(1, csrMstatus.sumField.reg)
    val mxrField   = new CSRRWMappedField(1, csrMstatus.mxrField.reg)
    val pad5       = new CSRRegField(3)
    val spelpField = new CSRZeroField(1)
    val sdtField   = new CSRZeroField(1)
    val pad6       = new CSRRegField(7)
    val uxlField   = new CSRZeroField(2)
    val pad7       = new CSRRegField(29)
    val sdField    = new CSRZeroField(1)

    val fields: Seq[CSRField] = Seq(
        pad0, sieField, pad1, spieField, ubeField, pad2, sppField,
        vsField, pad3, fsField, xsField, pad4, sumField, mxrField,
        pad5, spelpField, sdtField, pad6, uxlField, pad7, sdField
    )
}

class CSRSie(csrMie: CSRMie) extends CSR {
    val addr = "h104".U(12.W)

    val pad0       = new CSRZeroField(1)
    val ssieField  = new CSRRWMappedField(1, csrMie.ssieField.reg)
    val pad1       = new CSRZeroField(3)
    val stieField  = new CSRRWMappedField(1, csrMie.stieField.reg)
    val pad2       = new CSRZeroField(3)
    val seieField  = new CSRRWMappedField(1, csrMie.seieField.reg)
    val pad3       = new CSRZeroField(54)

    val fields: Seq[CSRField] = Seq(
        pad0, ssieField, pad1, stieField, pad2, seieField, pad3
    )
}

class CSRStvec extends CSR {
    val addr = "h105".U(12.W)

    val modeField = new CSRRegField(2, 0.U)
    val baseField = new CSRRegField(62, 0.U)

    val fields: Seq[CSRField] = Seq(modeField, baseField)

    // MODE WARL: only Direct(0) and Vectored(1) are legal
    override protected def legalizeWrite(newValue: UInt): UInt = {
        val modeRaw   = extractField(newValue, modeField)
        val modeLegal = Mux(modeRaw >= 2.U, modeField.reg, modeRaw)
        replaceField(newValue, modeField, modeLegal)
    }
}

class CSRScounteren extends CSR {
    val addr = "h106".U(12.W)

    val cyField = new CSRRegField(1)
    val tmField = new CSRRegField(1)
    val irField = new CSRRegField(1)
    val pad     = new CSRZeroField(61)

    val fields: Seq[CSRField] = Seq(cyField, tmField, irField, pad)
}

// Supervisor Configuration

class CSRSenvcfg extends CSR {
    val addr = "h10a".U(12.W)

    val fiomField  = new CSRZeroField(1)
    val pad0       = new CSRRegField(1)
    val lpeField   = new CSRZeroField(1)
    val sseField   = new CSRZeroField(1)
    val cbieField  = new CSRZeroField(1)
    val cbcfeFeild = new CSRZeroField(1)
    val cbzeField  = new CSRZeroField(1)
    val pad1       = new CSRRegField(24)
    val pmmField   = new CSRZeroField(2)
    val pad2       = new CSRZeroField(30)

    val fields: Seq[CSRField] = Seq(
        fiomField, pad0, lpeField, sseField, cbieField, cbcfeFeild,
        cbzeField, pad1, pmmField, pad2
    )
}

// Supervisor Trap Handling

class CSRSscratch extends CSRAnyCSR("h140".U(12.W))
class CSRSepc extends CSR {
    val addr = "h141".U(12.W)

    val pad0 = new CSRZeroField(1)
    val epcField = new CSRRegField(63)
    val fields = Seq(pad0, epcField)
}

class CSRScause extends CSR {
    val addr = "h142".U(12.W)

    val codeField      = new CSRRegField(63, 0.U)
    val interruptField = new CSRRegField(1, 0.U)

    val fields: Seq[CSRField] = Seq(codeField, interruptField)

    override protected def validateWrite(newValue: UInt): Bool = {
        val interrupt = newValue(63).asBool
        val code      = newValue(62, 0)

        val legalInterrupt = VecInit(
            Seq(1, 5, 9).map(_.U(63.W))
        ).exists(_ === code)

        val legalException = VecInit(
            Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 15).map(_.U(63.W))
        ).exists(_ === code)

        Mux(interrupt, legalInterrupt, legalException)
    }
}

class CSRStval extends CSR {
    val addr = "h143".U(12.W)
    val stvalField = new CSRRegField(64)
    val fields: Seq[CSRField] = Seq(stvalField)
}

class CSRSip(csrMip: CSRMip) extends CSR {
    val addr = "h144".U(12.W)

    val pad0       = new CSRZeroField(1)
    val ssipField  = new CSRRWMappedField(1, csrMip.ssipField.reg)
    val pad1       = new CSRZeroField(3)
    val stipField  = new CSRROMappedField(1, csrMip.stipField.read)
    val pad2       = new CSRZeroField(3)
    val seipField  = new CSRROMappedField(1, csrMip.seipField.read)
    val pad3       = new CSRZeroField(54)

    val fields: Seq[CSRField] = Seq(
        pad0, ssipField, pad1, stipField, pad2, seipField, pad3
    )
}

// Supervisor Protection and Translation

class CSRSatp(asidWidth: Int) extends CSR {
    val addr = "h180".U(12.W)

    val ppnField = new CSRRegField(44, 0.U)
    val asidField = new CSRRegField(asidWidth, 0.U)
    val pad0 = if (asidWidth != 16) Some(new CSRZeroField(16 - asidWidth)) else None
    val modeField = new CSRRegField(4, 0.U)
    val fields: Seq[CSRField] = if (asidWidth != 16) Seq(
            ppnField, asidField, pad0.get, modeField
        ) else Seq(
            ppnField, asidField, modeField
        )

    // MODE WARL: 0: Bare, 8: Sv39
    override protected def legalizeWrite(newValue: UInt): UInt = {
        val modeRaw   = extractField(newValue, modeField)
        val modeLegal = Mux((modeRaw === 8.U || modeRaw === 0.U), modeRaw, 0.U)
        replaceField(newValue, modeField, modeLegal)
    }
}

// Supervisor Timer Compare

class CSRStimecmp extends CSR {
    val addr = "h14d".U(12.W)

    val cmpField = new CSRRegField(64)
    val fields: Seq[CSRField] = Seq(cmpField)

    override def write(newValue: UInt): Bool = {
        val shouldWrite = validateWrite(newValue)
        val legalized   = legalizeWrite(newValue)
        var offset = 0
        fields.foreach { field =>
            val bits = legalized(offset + field.width - 1, offset)
            offset += field.width
            when(shouldWrite) {
                field.write(bits)
            }
        }
        true.B
    }
}