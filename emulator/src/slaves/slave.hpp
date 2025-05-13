#pragma once

#include <ranges>
#include <memory>
#include <cstdint>
#include <string>
#include <vector>

struct IRQ {
    bool valid = 0;
    uint32_t code = 0;
};

class Slave {
public:
    uint64_t base_addr;
    std::ranges::iota_view<uint64_t, uint64_t> range;

    virtual ~Slave() = default;
    virtual uint64_t read(uint64_t addr, uint8_t size) = 0;
    virtual void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) = 0;

    virtual void posedge_step() {}
    virtual IRQ get_irq() { return IRQ{false, 0}; }
};
