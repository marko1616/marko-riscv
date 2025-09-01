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

class InterruptController : public Slave {
public:
    explicit InterruptController(uint64_t base_addr) : Slave(base_addr) {}
    virtual ~InterruptController() = default;

    virtual void set_interrupt_level(uint16_t interrupt_id, bool level) = 0;
};

class InterruptSource {
private:
    std::shared_ptr<InterruptController> interrupt_controller = nullptr;
protected:
    void trigger_interrupt_level(uint16_t interrupt_id, bool level) {
        if (interrupt_controller) {
            interrupt_controller->set_interrupt_level(interrupt_id, level);
        }
    }
public:
    virtual void set_interrupt_controller(std::shared_ptr<InterruptController> interrupt_controller) {
        this->interrupt_controller = interrupt_controller;
    }
};
