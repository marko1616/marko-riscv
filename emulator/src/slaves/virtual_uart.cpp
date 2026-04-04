#include "virtual_uart.hpp"

VirtualUart::VirtualUart(uint64_t base_addr, uint16_t irq_id)
    : Slave(base_addr), irq_id(irq_id) {
    range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, 0x100);
    rbr_reg = 0;
    thr_reg = 0;
    ier_reg = 0;
    isr_reg = 0x01;
    fcr_reg = 0;
    lcr_reg = 0;
    mcr_reg = 0;
    lsr_reg = LSR_THR_EMPTY | LSR_TRANSMITTER_EMPTY;
    msr_reg = 0;
    spr_reg = 0;
}

void VirtualUart::enqueue_char(uint8_t ch) {
    rx_buffer.push(ch);
    lsr_reg |= LSR_DATA_READY;
    trigger_interrupt_level(irq_id, true);
}

uint64_t VirtualUart::read(uint64_t addr, uint8_t size) {
    switch (addr) {
        case 0x0: // RBR
            if (!rx_buffer.empty()) {
                rbr_reg = rx_buffer.front();
                rx_buffer.pop();
                if (rx_buffer.empty()) {
                    lsr_reg &= ~LSR_DATA_READY;
                    trigger_interrupt_level(irq_id, false);
                }
                return rbr_reg;
            }
            return 0;
        case 0x1: return ier_reg; // IER
        case 0x2: return isr_reg; // ISR
        case 0x3: return lcr_reg; // LCR
        case 0x4: return mcr_reg; // MCR
        case 0x5: return lsr_reg; // LSR
        case 0x6: return msr_reg; // MSR
        case 0x7: return spr_reg; // SPR
        default:  return 0;
    }
}

void VirtualUart::write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) {
    uint8_t val = static_cast<uint8_t>(data & 0xFF);
    switch (addr) {
        case 0x0: // THR
            thr_reg = val;
            std::cout << static_cast<char>(val) << std::flush;
            break;
        case 0x1: ier_reg = val; break; // IER
        case 0x2: fcr_reg = val; break; // FCR
        case 0x3: lcr_reg = val; break; // LCR
        case 0x4: mcr_reg = val; break; // MCR
        case 0x7: spr_reg = val; break; // SPR
        default: break;
    }
}

void VirtualUart::step(const std::unique_ptr<VMarkoRvCore>& ) {
}
