#include "plic.hpp"

VirtualPLIC::VirtualPLIC(uint64_t base_addr) : InterruptController(base_addr) {
    range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, 0x3FFF000);
}

uint64_t VirtualPLIC::read(uint64_t addr, uint8_t size) {
    if (std::ranges::contains(priority_reg_range, addr)) {
        uint8_t index = addr >> 2;
        return source_priority[index];
    }

    if (std::ranges::contains(pending_reg_range, addr)) {
        uint64_t result = 0;
        for (const auto& source_index : source_pending) {
            result |= 1ULL << (source_index - ((addr - 0x1000) << 3));
        }
        return result;
    }

    if (std::ranges::contains(enable_reg_range, addr)) {
        uint64_t result = 0;
        uint64_t offset = addr - base_addr;
        for (uint8_t rptr = 0; rptr <= size; ++rptr) {
            if (offset + rptr >= 0x80)
                continue;
            result |= source_enable[offset + rptr] << (rptr << 3);
        }
        return result;
    }

    // TODO more context support context0 current
    if (std::ranges::contains(context_reg_range, addr)) {
        if (addr == 0x200000) {
            // Threshold
            return threshold;
        } else if (addr == 0x200004) {
            // Claim
            const auto max_it = std::ranges::max_element(source_pending);
            if (max_it == source_pending.end() || *max_it < threshold) {
                return 0;
            }
            source_processing.push_back(*max_it);
            source_pending.erase(max_it);
            return *max_it;
        }
    }

    return 0;
}

void VirtualPLIC::write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) {
    if (std::ranges::contains(priority_reg_range, addr)) {
        uint8_t index = addr >> 2;
        source_priority[index] = data;
        return;
    }

    if (std::ranges::contains(enable_reg_range, addr)) {
        uint64_t offset = addr - base_addr;
        for (uint8_t rptr = 0; rptr <= size; ++rptr) {
            if (offset + rptr >= 0x80)
                continue;
            if (!(strb & (1 << rptr)))
                continue;
            source_enable[offset + rptr] = data >> (rptr << 3);
        }
        return;
    }

    // TODO more context support context0 current
    if (std::ranges::contains(context_reg_range, addr)) {
        if (addr == 0x200000) {
            // Threshold
            threshold = data;
        } else if (addr == 0x200004) {
            // Complete
            source_processing.erase(std::ranges::find(source_processing, data));
        }
    }
}

void VirtualPLIC::step(const std::unique_ptr<VMarkoRvCore> &top) {
    const auto max_it = std::ranges::max_element(source_pending);
    if (max_it == source_pending.end() || *max_it < threshold) {
        top->io_meip = 0;
    } else {
        top->io_meip = 1;
    }
    for (const auto& source : source_asserted) {
        if (!std::ranges::contains(source_pending, source) && !std::ranges::contains(source_processing, source)) {
            source_pending.push_back(source);
        }
    }
}

void VirtualPLIC::set_interrupt_level(uint16_t interrupt_id, bool level) {
    if (level) {
        source_asserted.insert(interrupt_id);
    } else {
        source_asserted.erase(interrupt_id);
    }
}