#pragma once

#include <ranges>
#include <memory>
#include <cstdint>
#include <string>
#include <vector>
#include <functional>

#define CLINT_CONTROLER_TYPE 0
#define PLIC_CONTROLER_TYPE 1

using IRQCallback = std::function<bool()>;

struct IRQRegistration {
    const char* desc;
    uint64_t type;
    uint64_t irq_number = 0;
    IRQCallback check_trigger;
};

struct IRQContext {
    uint64_t cause;
};

class Slave {
public:
    uint64_t base_addr;
    std::ranges::iota_view<uint64_t, uint64_t> range;

    virtual ~Slave() = default;
    virtual uint64_t read(uint64_t addr, uint8_t size) = 0;
    virtual void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) = 0;
};

class Device : public Slave {
public:
    virtual std::vector<IRQRegistration> get_irqs() const = 0;
};

class InterruptController : public Slave {
public:
    virtual uint64_t get_type() const = 0;
    virtual void register_irq(const IRQRegistration irq) = 0;
    virtual IRQContext check_irq() = 0;
};