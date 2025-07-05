#include "virtual_uart.hpp"

VirtualUart::VirtualUart(uint64_t base_addr) : Slave(base_addr) {
    range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, 0x100);

    ier = 0x00;
    isr = 0x01;
    fcr = 0x00;
    lcr = 0x03;
    mcr = 0x00;
    lsr = 0x60;
    msr = 0x00;
    spr = 0x00;
}

uint64_t VirtualUart::read(uint64_t addr, uint8_t size) {
    switch (addr) {
        case 0x0: return 0x00; // Placeholder for RX FIFO
        case 0x1: return ier;
        case 0x2: return isr;
        case 0x3: return lcr;
        case 0x4: return mcr;
        case 0x5: return lsr;
        case 0x6: return msr;
        case 0x7: return spr;
        default: return 0;
    }
}

void VirtualUart::write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) {
    uint8_t value = static_cast<uint8_t>(data);
    switch (addr) {
        case 0x0:
            std::cout << value;
            break;
        case 0x1:
            ier = value & 0xcf;
            break;
        case 0x2:
            fcr = value & 0xdf;
            break;
        case 0x3:
            lcr = value;
            break;
        case 0x4:
            mcr = value & 0x1f;
            break;
        case 0x7:
            spr = value;
            break;
    }
}
