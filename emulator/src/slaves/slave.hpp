#pragma once

#include <ranges>
#include <memory>
#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include "VMarkoRvCore.h"

#define CLINT_CONTROLER_TYPE 0
#define PLIC_CONTROLER_TYPE 1

struct IRQRegistration;

class Slave {
public:
    uint64_t base_addr;
    std::ranges::iota_view<uint64_t, uint64_t> range;

    explicit Slave(uint64_t base_addr) {
        this->base_addr = base_addr;
    }
    virtual ~Slave() = default;
    virtual uint64_t read(uint64_t addr, uint8_t size) = 0;
    virtual void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) = 0;
    virtual void step(const std::unique_ptr<VMarkoRvCore> &top) {}
};

class InterruptController {
public:
    virtual void set_interrupt(uint32_t interrupt_id, int level) = 0;
};