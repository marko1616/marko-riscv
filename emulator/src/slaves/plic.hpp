#pragma once
#include <iostream>
#include <ranges>
#include <cstdint>
#include <unordered_set>

#include "slave.hpp"

#define PLIC_SOURCE_NUM 1024

class VirtualPLIC : public InterruptController {
private:
    static constexpr auto priority_reg_range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, 0x1000);
    static constexpr auto pending_reg_range = std::ranges::iota_view<uint64_t, uint64_t>(0x1000, 0x1080);
    static constexpr auto enable_reg_range = std::ranges::iota_view<uint64_t, uint64_t>(0x2000, 0x1f2000);
    static constexpr auto context_reg_range = std::ranges::iota_view<uint64_t, uint64_t>(0x200000, 0x3FFF000);

    std::array<uint8_t, PLIC_SOURCE_NUM / 8> source_enable{};
    std::array<uint32_t, PLIC_SOURCE_NUM> source_priority{};
    std::unordered_set<uint16_t> source_asserted{};
    std::vector<uint16_t> source_pending{};
    std::vector<uint16_t> source_processing{};
    uint16_t threshold = 0;
public:
    explicit VirtualPLIC(uint64_t base_addr);

    uint64_t read(uint64_t addr, uint8_t size) override;
    void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) override;
    void step(const std::unique_ptr<VMarkoRvCore> &top) override;

    void set_interrupt_level(uint16_t interrupt_id, bool level) override;
};
