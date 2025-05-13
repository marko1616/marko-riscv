#pragma once
#include <iostream>
#include <ranges>
#include <cstdint>

#include "slave.hpp"

class VirtualUart : public Slave {
public:
    explicit VirtualUart(uint64_t base_addr);

    uint64_t read(uint64_t addr, uint8_t size) override;
    void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) override;

private:
    uint8_t ier, isr, fcr, lcr, mcr, lsr, msr, spr;
};
