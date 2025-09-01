#include "virtual_uart.hpp"

VirtualUart::VirtualUart(uint64_t base_addr, uint16_t irq_id) : Slave(base_addr), irq_id(irq_id) {
    range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, 0x100);
    enable_raw_mode();
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

VirtualUart::~VirtualUart() {
    disable_raw_mode();
}

void VirtualUart::enable_raw_mode() {
    tcgetattr(STDIN_FILENO, &orig_termios);

    struct termios raw = orig_termios;
    raw.c_iflag &= ~(IGNBRK | BRKINT | PARMRK | ISTRIP |
                     INLCR  | IGNCR  | ICRNL  | IXON  | IXOFF | IXANY);

    // raw.c_oflag &= ~OPOST;
    raw.c_lflag &= ~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);

    raw.c_cflag &= ~(CSIZE | PARENB);
    raw.c_cflag |= CS8;

    tcsetattr(STDIN_FILENO, TCSAFLUSH, &raw);
}

void VirtualUart::disable_raw_mode() {
    tcsetattr(STDIN_FILENO, TCSAFLUSH, &orig_termios);
}

bool VirtualUart::read_byte_from_stdin(uint8_t &ch) {
    fd_set set;
    struct timeval timeout = {0, 0};
    FD_ZERO(&set);
    FD_SET(STDIN_FILENO, &set);
    if (select(STDIN_FILENO + 1, &set, NULL, NULL, &timeout) > 0) {
        if (::read(STDIN_FILENO, &ch, 1) == 1) {
            return true;
        }
    }
    return false;
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
        case 0x1: // IER
            return ier_reg;
        case 0x2: // ISR
            return isr_reg;
        case 0x3: // LCR
            return lcr_reg;
        case 0x4: // MCR
            return mcr_reg;
        case 0x5: // LSR
            return lsr_reg;
        case 0x6: // MSR
            return msr_reg;
        case 0x7: // SPR
            return spr_reg;
        default:
            return 0;
    }
}

void VirtualUart::write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) {
    uint8_t val = static_cast<uint8_t>(data & 0xFF);
    switch (addr) {
        case 0x0: // THR
            thr_reg = val;
            std::cout << static_cast<char>(val) << std::flush;
            break;
        case 0x1: // IER
            ier_reg = val;
            break;
        case 0x2: // FCR
            fcr_reg = val;
            break;
        case 0x3: // LCR
            lcr_reg = val;
            break;
        case 0x4: // MCR
            mcr_reg = val;
            break;
        case 0x7: // SPR
            spr_reg = val;
            break;
        default:
            break;
    }
}

void VirtualUart::step(const std::unique_ptr<VMarkoRvCore> &top) {
    uint8_t ch;
    if (read_byte_from_stdin(ch)) {
        rx_buffer.push(ch);
        lsr_reg |= LSR_DATA_READY;
        trigger_interrupt_level(irq_id, true);
    }
}
