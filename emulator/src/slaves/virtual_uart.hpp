#pragma once

#include <queue>
#include <memory>
#include <cstdint>
#include <iostream>
#include <ranges>

#include "VMarkoRvCore.h"
#include "slave.hpp"

class VirtualUart : public Slave, public InterruptSource {
public:
    VirtualUart(uint64_t base_addr, uint16_t irq_id);
    ~VirtualUart() = default;

    uint64_t read(uint64_t addr, uint8_t size) override;
    void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) override;
    void step(const std::unique_ptr<VMarkoRvCore>& top) override;
    void enqueue_char(uint8_t ch);

private:
    void update_iir();
    void clear_rx_buffer();
    void update_loopback_msr();
    uint8_t fifo_iir_bits() const;
    uint8_t fifo_trig_bytes() const;

    uint16_t irq_id;
    std::queue<uint8_t> rx_buffer;
    uint32_t rx_timeout_counter;

    // 16550 register set
    uint8_t rbr_reg; // Receiver Buffer Register (read only)
    uint8_t thr_reg; // Transmitter Holding Register (write only)
    uint8_t ier_reg; // Interrupt Enable Register
    uint8_t iir_reg; // Interrupt Identification Register (read) / FIFO Control Register (write)
    uint8_t fcr_reg; // FIFO Control Register
    uint8_t lcr_reg; // Line Control Register
    uint8_t mcr_reg; // Modem Control Register
    uint8_t lsr_reg; // Line Status Register
    uint8_t msr_reg; // Modem Status Register
    uint8_t spr_reg; // Scratch Register

    // Baud divisor latch registers, selected by LCR[7] DLAB
    uint8_t dll_reg; // Divisor Latch LSB
    uint8_t dlm_reg; // Divisor Latch MSB

    uint8_t iir_pending_reg;

    // Character timeout threshold (roughly 4 character times)
    static constexpr uint32_t RX_TIMEOUT_THRESHOLD = 384;

    // LSR bit definitions
    static constexpr uint8_t LSR_DATA_READY         = 0x01;
    static constexpr uint8_t LSR_DATA_OVERRUN       = 0x02;
    static constexpr uint8_t LSR_DATA_PARITY        = 0x04;
    static constexpr uint8_t LSR_DATA_FRAME         = 0x08;
    static constexpr uint8_t LSR_DATA_BREAK         = 0x10;
    static constexpr uint8_t LSR_THR_EMPTY          = 0x20;
    static constexpr uint8_t LSR_TRANSMITTER_EMPTY  = 0x40;
    static constexpr uint8_t LSR_FIFO_ERROR         = 0x80;

    // IER bit definitions
    static constexpr uint8_t IER_RX_AVAILABLE = 0x01;
    static constexpr uint8_t IER_THR_EMPTY    = 0x02;
    static constexpr uint8_t IER_LINE_STAT    = 0x04;
    static constexpr uint8_t IER_SUPPORTED    = 0x0f;

    // IIR ID values (what goes into iir_reg[3:0])
    static constexpr uint8_t IIR_MODEM_STAT   = 0x00;
    static constexpr uint8_t IIR_NO_INT       = 0x01;
    static constexpr uint8_t IIR_THR_EMPTY    = 0x02;
    static constexpr uint8_t IIR_RX_AVAILABLE = 0x04;
    static constexpr uint8_t IIR_LINE_STAT    = 0x06;
    static constexpr uint8_t IIR_CHAR_TIMEOUT = 0x0C;
    static constexpr uint8_t IIR_ID_MASK      = 0x0f;
    static constexpr uint8_t IIR_FIFO_ENABLED = 0xc0;

    // Internal pending flags (independent bits, NOT IIR IDs)
    static constexpr uint8_t PEND_LINE_STAT    = 0x01;
    static constexpr uint8_t PEND_RX_AVAIL     = 0x02;
    static constexpr uint8_t PEND_CHAR_TIMEOUT = 0x04;
    static constexpr uint8_t PEND_THR_EMPTY    = 0x08;

    // FCR bit definitions
    static constexpr uint8_t FCR_ENABLE          = 0x01;
    static constexpr uint8_t FCR_CLEAR_RX        = 0x02;
    static constexpr uint8_t FCR_CLEAR_TX        = 0x04;
    static constexpr uint8_t FCR_DMA_MODE        = 0x08;
    static constexpr uint8_t FCR_RX_TRIGGER_MASK = 0xc0;
    static constexpr uint8_t FCR_STORED_MASK     = FCR_ENABLE | FCR_DMA_MODE | FCR_RX_TRIGGER_MASK;

    // LCR bit definitions
    static constexpr uint8_t LCR_DLAB = 0x80;

    // MCR bit definitions
    static constexpr uint8_t MCR_DTR      = 0x01;
    static constexpr uint8_t MCR_RTS      = 0x02;
    static constexpr uint8_t MCR_OUT1     = 0x04;
    static constexpr uint8_t MCR_OUT2     = 0x08;
    static constexpr uint8_t MCR_LOOPBACK = 0x10;

    // MSR bit definitions
    static constexpr uint8_t MSR_DCTS = 0x01;
    static constexpr uint8_t MSR_DDSR = 0x02;
    static constexpr uint8_t MSR_TERI = 0x04;
    static constexpr uint8_t MSR_DDCD = 0x08;
    static constexpr uint8_t MSR_CTS  = 0x10;
    static constexpr uint8_t MSR_DSR  = 0x20;
    static constexpr uint8_t MSR_RI   = 0x40;
    static constexpr uint8_t MSR_DCD  = 0x80;
};
