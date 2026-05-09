#include "plic.hpp"

VirtualPLIC::VirtualPLIC(uint64_t base_addr) : InterruptController(base_addr) {
    range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, 0x3FFF000);
}

bool VirtualPLIC::is_source_enabled(uint32_t context_id, uint16_t id) const {
    if (context_id >= PLIC_CONTEXT_NUM) return false;
    uint16_t byte_index = id / 8;
    uint8_t  bit_index  = id % 8;
    if (byte_index >= contexts[context_id].source_enable.size()) return false;
    return (contexts[context_id].source_enable[byte_index] >> bit_index) & 1;
}

bool VirtualPLIC::is_claimable(uint32_t context_id, uint16_t id) const {
    if (context_id >= PLIC_CONTEXT_NUM) return false;
    return source_priority[id] > 0
        && is_source_enabled(context_id, id)
        && source_priority[id] > contexts[context_id].threshold;
}

uint64_t VirtualPLIC::read(uint64_t addr, uint8_t size) {
    auto plic_comparator = [this](uint16_t a, uint16_t b) -> bool {
        if (source_priority[a] != source_priority[b])
            return source_priority[a] < source_priority[b];
        return a > b;
    };

    if (std::ranges::contains(priority_reg_range, addr)) {
        uint16_t index = addr >> 2;
        return source_priority[index];
    }

    if (std::ranges::contains(pending_reg_range, addr)) {
        uint64_t result = 0;
        uint32_t word = (addr - 0x1000) / 4;
        for (const auto& source_index : source_pending) {
            if (source_index / 32 == word) {
                uint32_t bit = source_index % 32;
                result |= (1ULL << bit);
            }
        }
        return result;
    }

    if (std::ranges::contains(enable_reg_range, addr)) {
        uint64_t rel = addr - 0x2000;
        uint32_t context_id = rel / 0x80;
        uint64_t byte_offset = rel % 0x80;
        if (context_id >= PLIC_CONTEXT_NUM) return 0;

        uint64_t result = 0;
        const auto& enable = contexts[context_id].source_enable;
        for (uint8_t rptr = 0; rptr <= size; ++rptr) {
            if (byte_offset + rptr >= enable.size())
                continue;
            result |= static_cast<uint64_t>(enable[byte_offset + rptr]) << (rptr << 3);
        }
        return result;
    }

    if (std::ranges::contains(context_reg_range, addr)) {
        uint64_t rel = addr - 0x200000;
        uint32_t context_id = rel / 0x1000;
        uint32_t reg_offset = rel % 0x1000;
        if (context_id >= PLIC_CONTEXT_NUM) return 0;

        if (reg_offset == 0x0) {
            // Threshold
            return contexts[context_id].threshold;
        } else if (reg_offset == 0x4) {
            // Claim
            auto candidates = source_pending
                | std::views::filter([this, context_id](uint16_t id) {
                    return is_claimable(context_id, id);
                });
            auto max_it = std::ranges::max_element(candidates, plic_comparator);

            if (max_it == candidates.end()) {
                return 0;
            }

            uint16_t claimed = *max_it;
            source_processing.push_back(claimed);

            source_pending.erase(std::ranges::find(source_pending, claimed));
            return claimed;
        }
    }

    return 0;
}

void VirtualPLIC::write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) {
    if (std::ranges::contains(priority_reg_range, addr)) {
        uint16_t index = addr >> 2;
        if (index < source_priority.size()) {
            source_priority[index] = data;
        }
        return;
    }

    if (std::ranges::contains(enable_reg_range, addr)) {
        uint64_t rel = addr - 0x2000;
        uint32_t context_id = rel / 0x80;
        uint64_t byte_offset = rel % 0x80;
        if (context_id >= PLIC_CONTEXT_NUM) return;

        auto& enable = contexts[context_id].source_enable;
        for (uint8_t rptr = 0; rptr <= size; ++rptr) {
            if (byte_offset + rptr >= enable.size())
                continue;
            if (!(strb & (1 << rptr)))
                continue;
            enable[byte_offset + rptr] = (data >> (rptr << 3)) & 0xFF;
        }
        return;
    }

    if (std::ranges::contains(context_reg_range, addr)) {
        uint64_t rel = addr - 0x200000;
        uint32_t context_id = rel / 0x1000;
        uint32_t reg_offset = rel % 0x1000;
        if (context_id >= PLIC_CONTEXT_NUM) return;

        if (reg_offset == 0x0) {
            // Threshold
            contexts[context_id].threshold = data;
        } else if (reg_offset == 0x4) {
            // Complete
            auto it = std::ranges::find(source_processing, static_cast<uint16_t>(data));
            if (it != source_processing.end()) {
                source_processing.erase(it);
            }
        }
    }
}

void VirtualPLIC::step(const std::unique_ptr<VMarkoRvCore> &top) {
    for (const auto& source : source_asserted) {
        if (!std::ranges::contains(source_pending, source) && !std::ranges::contains(source_processing, source)) {
            source_pending.push_back(source);
        }
    }

    bool m_has_actionable = std::ranges::any_of(source_pending, [this](uint16_t id) {
        return is_claimable(PLIC_CONTEXT_M, id);
    });

    bool s_has_actionable = std::ranges::any_of(source_pending, [this](uint16_t id) {
        return is_claimable(PLIC_CONTEXT_S, id);
    });

    top->io_meip = m_has_actionable ? 1 : 0;
    top->io_seip = s_has_actionable ? 1 : 0;
}

void VirtualPLIC::set_interrupt_level(uint16_t interrupt_id, bool level) {
    if (level) {
        source_asserted.insert(interrupt_id);
    } else {
        source_asserted.erase(interrupt_id);
    }
}
