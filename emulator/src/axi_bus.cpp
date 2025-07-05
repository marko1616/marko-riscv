#include "axi_bus.hpp"

bool VirtualAxiSlaves::ReservedItem::isConflict(uint64_t addr, uint8_t size) {
    if (!valid) return false;

    // Compute byte ranges for this reserved item and the target address
    uint64_t this_bytes = 1ULL << this->size;
    uint64_t target_bytes = 1ULL << size;

    uint64_t this_start = this->addr;
    uint64_t this_end = this->addr + this_bytes;

    uint64_t target_start = addr;
    uint64_t target_end = addr + target_bytes;

    // Return true if byte ranges overlap
    return (this_end > target_start) && (this_start < target_end);
}

VirtualAxiSlaves::VirtualAxiSlaves() {
    empty_read_transaction();
    empty_write_transaction();
    reserved_items.resize(CFG_MAX_RESERVED);
}

VirtualAxiSlaves::~VirtualAxiSlaves() = default;

uint64_t VirtualAxiSlaves::register_slave(std::shared_ptr<Slave> slave) {
    slaves.emplace_back(std::move(slave));
    return slaves.size() - 1;
}

std::shared_ptr<Slave> VirtualAxiSlaves::get_slave(uint64_t id) {
    return slaves[id];
}

void VirtualAxiSlaves::sim_step(const std::unique_ptr<VMarkoRvCore> &top, axiSignal &axi) {
    handle_top(top);
    handle_read(axi);
    handle_write(axi);
}

void VirtualAxiSlaves::empty_read_transaction() {
    current_read.state = STAT_RIDLE;
    current_read.beat = 0;
    current_read.held_data = std::nullopt;
    current_read.addr = 0;
    current_read.size = 0;
    current_read.burst = BURST_RESERVED;
    current_read.id = 0;
    current_read.len = 0;
    current_read.lock = false;
}

void VirtualAxiSlaves::empty_write_transaction() {
    current_write.state = STAT_WIDLE;
    current_write.beat = 0;
    current_write.addr = 0;
    current_write.size = 0;
    current_write.burst = BURST_RESERVED;
    current_write.id = 0;
    current_write.len = 0;
    current_write.lock = false;
    current_write.resp = RESP_OKAY;
}

uint64_t VirtualAxiSlaves::calculate_next_addr(uint64_t base_addr, uint8_t size, axi_burst_t burst, uint8_t beat) {
    const uint64_t bytes_per_beat = 1 << size;
    switch (burst) {
        case BURST_FIXED:
            // Fixed burst: same address every beat
            return base_addr;
        case BURST_INCR:
            // Incrementing burst: address increases by beat count
            return base_addr + beat * bytes_per_beat;
        case BURST_WRAP: {
            // Wrap burst: address wraps within a fixed-size region
            const uint64_t num_beats = current_read.len + 1;
            const uint64_t wrap_boundary = num_beats * bytes_per_beat;
            const uint64_t aligned_base = base_addr & ~(wrap_boundary - 1);
            const uint64_t offset = beat * bytes_per_beat;
            return aligned_base + (offset % wrap_boundary);
        }
        default:
            std::cerr << "Not valid burst mode";
            return base_addr;
    }
}

void VirtualAxiSlaves::handle_top(const std::unique_ptr<VMarkoRvCore> &top) {
    for(const auto& slave : slaves) {
        slave->step(top);
    }
}

void VirtualAxiSlaves::handle_read(axiSignal &axi) {
    switch (current_read.state) {
        case STAT_RIDLE:
            axi.arready = true;
            if (axi.arvalid) {
                current_read.addr = axi.araddr;
                current_read.size = axi.arsize;
                current_read.burst = static_cast<axi_burst_t>(axi.arburst);
                current_read.id = axi.arid;
                current_read.len = axi.arlen;
                current_read.lock = axi.arlock;
                current_read.state = STAT_SEND;
                current_read.beat = 0;
            }
            break;

        case STAT_SEND:
            axi.rvalid = true;
            axi.rid = current_read.id;
            uint64_t current_addr = calculate_next_addr(
                current_read.addr,
                current_read.size,
                current_read.burst,
                current_read.beat
            );

            auto slave = std::ranges::find_if(slaves,
                [current_addr](const std::shared_ptr<Slave> &s) -> bool {
                    return std::ranges::contains(s->range, current_addr - s->base_addr);
                });

            // Found and not cross 4k boundary.
            bool addr_valid = (slave != slaves.end()) &&
                ((current_addr & 0xfffff000) == (current_read.addr & 0xfffff000));

            if (addr_valid) {
                if (current_read.held_data) {
                    // Prevent multiple read side effects
                    axi.rdata = *current_read.held_data;
                    axi.rresp = current_read.lock ? RESP_EXOKAY : RESP_OKAY;
                } else {
                    uint64_t data = (*slave)->read(current_addr - (*slave)->base_addr, current_read.size);
                    current_read.held_data = data;
                    axi.rdata = data;
                    axi.rresp = current_read.lock ? RESP_EXOKAY : RESP_OKAY;
                }
                axi.rlast = (current_read.beat == current_read.len);
            } else {
                // Invalid address, signal decode error
                axi.rdata = 0;
                axi.rlast = true;
                axi.rresp = RESP_DECERR;
            }

            if (axi.rready) {
                current_read.held_data = std::nullopt;

                if (current_read.lock) {
                    // Try to reserve the read address
                    bool reserved = false;
                    for (auto &item : reserved_items) {
                        if (!item.valid) {
                            item.valid = true;
                            item.addr = current_read.addr;
                            item.size = current_read.size;
                            reserved = true;
                            break;
                        }
                    }
                    if (!reserved) {
                        static size_t replace_ptr = 0;
                        reserved_items[replace_ptr] = {true, current_read.addr, current_read.size};
                        replace_ptr = (replace_ptr + 1) % CFG_MAX_RESERVED;
                    }
                }

                if (axi.rresp == RESP_DECERR || current_read.beat == current_read.len) {
                    empty_read_transaction();
                    break;
                }

                current_read.beat++;
            }
            break;
    }
}

void VirtualAxiSlaves::handle_write(axiSignal &axi) {
    switch (current_write.state) {
        case STAT_WIDLE:
            axi.awready = true;
            if (axi.awvalid) {
                current_write.addr = axi.awaddr;
                current_write.size = axi.awsize;
                current_write.burst = static_cast<axi_burst_t>(axi.awburst);
                current_write.id = axi.awid;
                current_write.len = axi.awlen;
                current_write.lock = axi.awlock;
                current_write.beat = 0;
                current_write.state = STAT_WRITE_DATA;
            }
            break;

        case STAT_WRITE_DATA:
            axi.wready = true;
            if (axi.wvalid) {
                uint64_t current_addr = calculate_next_addr(
                    current_write.addr,
                    current_write.size,
                    current_write.burst,
                    current_write.beat
                );

                auto slave = std::ranges::find_if(slaves,
                    [current_addr](const auto& s) {
                        return std::ranges::contains(s->range, current_addr - s->base_addr);
                    });

                // Found and not cross 4k boundary.
                bool addr_valid = (slave != slaves.end()) &&
                    ((current_addr & 0xfffff000) == (current_write.addr & 0xfffff000));

                if (addr_valid) {
                    bool reserved_hit = false;
                    for (auto &item : reserved_items) {
                        if (item.valid && item.addr == current_addr) {
                            reserved_hit = true;
                        }
                        if (item.valid && item.isConflict(current_addr, current_write.size)) {
                            item.valid = false;
                        }
                    }

                    if (!current_write.lock || (current_write.lock && reserved_hit)) {
                        (*slave)->write(current_addr - (*slave)->base_addr, axi.wdata, current_write.size, axi.wstrb);
                    }

                    current_write.resp = current_write.lock && reserved_hit ? RESP_EXOKAY : RESP_OKAY;
                } else {
                    // Invalid address
                    current_write.resp = RESP_DECERR;
                    current_write.state = STAT_WRITE_RESP;
                }

                if (axi.wlast) {
                    current_write.state = STAT_WRITE_RESP;
                }

                current_write.beat++;
            }
            break;

        case STAT_WRITE_RESP:
            axi.bvalid = true;
            axi.bresp = current_write.resp;
            axi.bid = current_write.id;
            if (axi.bready) {
                empty_write_transaction();
            }
            break;
    }
}
