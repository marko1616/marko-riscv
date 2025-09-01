#pragma once
#include <queue>
#include <memory>
#include <cstdint>
#include <iostream>
#include <termios.h>
#include <unistd.h>

#include "VMarkoRvCore.h"
#include "slave.hpp"

class VirtualUart : public Slave, public InterruptSource {
public:
    VirtualUart(uint64_t base_addr, uint16_t irq_id);
    ~VirtualUart();

    uint64_t read(uint64_t addr, uint8_t size) override;
    void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) override;
    void step(const std::unique_ptr<VMarkoRvCore> &top) override;

private:
    void enable_raw_mode();
    void disable_raw_mode();
    bool read_byte_from_stdin(uint8_t &ch);

    std::queue<uint8_t> rx_buffer;
    struct termios orig_termios;
    uint16_t irq_id;

    // 16550 register set
    uint8_t rbr_reg; // Receiver Buffer Register (read only)
    uint8_t thr_reg; // Transmitter Holding Register (write only)
    uint8_t ier_reg; // Interrupt Enable Register
    uint8_t isr_reg; // Interrupt Status Register (read) / FIFO Control Register (write)
    uint8_t fcr_reg; // FIFO Control Register
    uint8_t lcr_reg; // Line Control Register
    uint8_t mcr_reg; // Modem Control Register
    uint8_t lsr_reg; // Line Status Register
    uint8_t msr_reg; // Modem Status Register
    uint8_t spr_reg; // Scratch Register

    // LSR bit definitions
    static constexpr uint8_t LSR_DATA_READY = 0x01;
    static constexpr uint8_t LSR_THR_EMPTY = 0x20;
    static constexpr uint8_t LSR_TRANSMITTER_EMPTY = 0x40;
};
