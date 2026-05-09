#include "virtual_uart.hpp"

VirtualUart::VirtualUart(uint64_t base_addr, uint16_t irq_id)
    : Slave(base_addr), irq_id(irq_id) {
    range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, 0x100);

    rbr_reg = 0;
    thr_reg = 0;
    ier_reg = 0;
    iir_reg = IIR_NO_INT;
    fcr_reg = 0;
    lcr_reg = 0;
    mcr_reg = 0;
    lsr_reg = LSR_THR_EMPTY | LSR_TRANSMITTER_EMPTY;
    msr_reg = 0;
    spr_reg = 0;

    dll_reg = 0;
    dlm_reg = 0;

    iir_pending_reg = 0;
    rx_timeout_counter = 0;
}

void VirtualUart::enqueue_char(uint8_t ch) {
    uint8_t max_depth = (fcr_reg & FCR_ENABLE) ? 16 : 1;
    auto rx_overrun = rx_buffer.size() >= max_depth;
    if (!rx_overrun) {
        rx_buffer.push(ch);
    }
    auto trig_rx_level = fifo_trig_bytes();

    lsr_reg |= LSR_DATA_READY;
    if (rx_overrun) {
        lsr_reg |= LSR_DATA_OVERRUN;
        iir_pending_reg |= PEND_LINE_STAT;
    }
    if (rx_buffer.size() >= trig_rx_level) {
        iir_pending_reg |= PEND_RX_AVAIL;
    }

    rx_timeout_counter = 0;
    iir_pending_reg &= ~PEND_CHAR_TIMEOUT;

    update_iir();
}

uint64_t VirtualUart::read(uint64_t addr, uint8_t size) {
    switch (addr) {
        case 0x0: // RBR / DLL
            if (lcr_reg & LCR_DLAB) {
                return dll_reg;
            }

            if (!rx_buffer.empty()) {
                rbr_reg = rx_buffer.front();
                rx_buffer.pop();

                if (rx_buffer.empty()) {
                    lsr_reg &= ~LSR_DATA_READY;
                }

                if (rx_buffer.size() < fifo_trig_bytes()) {
                    iir_pending_reg &= ~PEND_RX_AVAIL;
                }

                rx_timeout_counter = 0;
                iir_pending_reg &= ~PEND_CHAR_TIMEOUT;

                update_iir();
            }
            return rbr_reg;

        case 0x1: // IER / DLM
            if (lcr_reg & LCR_DLAB) {
                return dlm_reg;
            }
            return ier_reg;

        case 0x2: { // IIR
            uint8_t ret = fifo_iir_bits() | iir_reg;

            // THRE interrupt is cleared by reading IIR only if THRE is the reported source.
            if ((iir_reg & IIR_ID_MASK) == IIR_THR_EMPTY) {
                iir_pending_reg &= ~PEND_THR_EMPTY;
                update_iir();
            }

            return ret;
        }

        case 0x3: return lcr_reg; // LCR
        case 0x4: return mcr_reg; // MCR
        case 0x5: { // LSR
            uint8_t ret = lsr_reg;
            // Clear all line state.
            lsr_reg &= ~(LSR_DATA_OVERRUN | LSR_DATA_PARITY | LSR_DATA_FRAME |
                          LSR_DATA_BREAK | LSR_FIFO_ERROR);
            iir_pending_reg &= ~PEND_LINE_STAT;
            update_iir();
            return ret;
        }
        case 0x6: { // MSR
            uint8_t ret = msr_reg;
            msr_reg &= ~(MSR_DCTS | MSR_DDSR | MSR_TERI | MSR_DDCD);
            return ret;
        }
        case 0x7: return spr_reg; // SPR
        default:  return 0;
    }
}

void VirtualUart::write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) {
    uint8_t val = static_cast<uint8_t>(data & 0xFF);

    switch (addr) {
        case 0x0: // THR / DLL
            if (lcr_reg & LCR_DLAB) {
                dll_reg = val;
                return;
            }

            iir_pending_reg &= ~PEND_THR_EMPTY;
            thr_reg = val;

            if (mcr_reg & MCR_LOOPBACK) {
                enqueue_char(val);
            } else {
                // Write THR is complete immediately and atomically so we don't need to change lsr;
                std::cout << static_cast<char>(val) << std::flush;
            }

            iir_pending_reg |= PEND_THR_EMPTY;
            update_iir();
            break;

        case 0x1: { // IER / DLM
            if (lcr_reg & LCR_DLAB) {
                dlm_reg = val;
                return;
            }

            uint8_t old_ier = ier_reg;
            ier_reg = val & IER_SUPPORTED;

            // If RX data is already waiting, enabling RX interrupt should expose it in IIR.
            if ((ier_reg & IER_RX_AVAILABLE) && (lsr_reg & LSR_DATA_READY)) {
                iir_pending_reg |= PEND_RX_AVAIL;
            }

            // If THRE interrupt is newly enabled while THR is empty, raise one THRE event.
            if (!(old_ier & IER_THR_EMPTY) &&
                (ier_reg & IER_THR_EMPTY) &&
                (lsr_reg & LSR_THR_EMPTY)) {
                iir_pending_reg |= PEND_THR_EMPTY;
            }

            update_iir();
            break;
        }

        case 0x2: { // FCR
            uint8_t old_fcr = fcr_reg;
            fcr_reg = val & FCR_STORED_MASK;

            bool fifo_mode_changed = ((old_fcr ^ fcr_reg) & FCR_ENABLE) != 0;

            if ((val & FCR_CLEAR_RX) || fifo_mode_changed || !(fcr_reg & FCR_ENABLE)) {
                clear_rx_buffer();
            }

            if ((val & FCR_CLEAR_TX) || fifo_mode_changed) {
                iir_pending_reg |= PEND_THR_EMPTY;
            }

            update_iir();
            break;
        }

        case 0x3: // LCR
            lcr_reg = val;
            break;

        case 0x4: { // MCR
            uint8_t old_mcr = mcr_reg;
            mcr_reg = val & 0x1f;

            if (mcr_reg & MCR_LOOPBACK) {
                update_loopback_msr();
            } else if (old_mcr & MCR_LOOPBACK) {
                msr_reg = 0;
            }

            update_iir();
            break;
        }

        case 0x7: // SPR
            spr_reg = val;
            break;

        default:
            break;
    }
}

void VirtualUart::step(const std::unique_ptr<VMarkoRvCore>&) {
    if ((fcr_reg & FCR_ENABLE) && !rx_buffer.empty()) {
        if (rx_timeout_counter < RX_TIMEOUT_THRESHOLD) {
            rx_timeout_counter++;
            if (rx_timeout_counter >= RX_TIMEOUT_THRESHOLD) {
                iir_pending_reg |= PEND_CHAR_TIMEOUT;
                update_iir();
            }
        }
    }
}

void VirtualUart::clear_rx_buffer() {
    while (!rx_buffer.empty()) {
        rx_buffer.pop();
    }

    lsr_reg &= ~LSR_DATA_READY;
    iir_pending_reg &= ~(PEND_RX_AVAIL | PEND_CHAR_TIMEOUT);
    rx_timeout_counter = 0;
}

uint8_t VirtualUart::fifo_iir_bits() const {
    return (fcr_reg & FCR_ENABLE) ? IIR_FIFO_ENABLED : 0;
}

void VirtualUart::update_iir() {
    // MCR Out2
    if (!(mcr_reg & MCR_OUT2)) {
        iir_reg = IIR_NO_INT;
        trigger_interrupt_level(irq_id, false);
        return;
    }

    if ((iir_pending_reg & PEND_LINE_STAT) && (ier_reg & IER_LINE_STAT)) {
        iir_reg = IIR_LINE_STAT;
        trigger_interrupt_level(irq_id, true);
        return;
    }

    if ((iir_pending_reg & PEND_RX_AVAIL) && (ier_reg & IER_RX_AVAILABLE)) {
        iir_reg = IIR_RX_AVAILABLE;
        trigger_interrupt_level(irq_id, true);
        return;
    }

    if ((iir_pending_reg & PEND_CHAR_TIMEOUT) && (ier_reg & IER_RX_AVAILABLE)) {
        iir_reg = IIR_CHAR_TIMEOUT;
        trigger_interrupt_level(irq_id, true);
        return;
    }

    if ((iir_pending_reg & PEND_THR_EMPTY) && (ier_reg & IER_THR_EMPTY)) {
        iir_reg = IIR_THR_EMPTY;
        trigger_interrupt_level(irq_id, true);
        return;
    }

    // No PE FE BI line state.
    iir_reg = IIR_NO_INT;
    trigger_interrupt_level(irq_id, false);
}

void VirtualUart::update_loopback_msr() {
    uint8_t new_msr = 0;
    if (mcr_reg & MCR_DTR)  new_msr |= MSR_DSR;
    if (mcr_reg & MCR_RTS)  new_msr |= MSR_CTS;
    if (mcr_reg & MCR_OUT1) new_msr |= MSR_RI;
    if (mcr_reg & MCR_OUT2) new_msr |= MSR_DCD;

    uint8_t changed = (msr_reg ^ new_msr) & 0xF0;
    uint8_t delta = 0;
    if (changed & MSR_CTS) delta |= MSR_DCTS;
    if (changed & MSR_DSR) delta |= MSR_DDSR;
    if (changed & MSR_DCD) delta |= MSR_DDCD;
    if ((msr_reg & MSR_RI) && !(new_msr & MSR_RI)) delta |= MSR_TERI;

    msr_reg = new_msr | delta;
}

uint8_t VirtualUart::fifo_trig_bytes() const {
    if (!(fcr_reg & FCR_ENABLE)) {
        return 1;
    }

    switch (fcr_reg >> 6) {
        case 0: return 1;
        case 1: return 4;
        case 2: return 8;
        case 3: return 14;
        default: return 1;
    }
}