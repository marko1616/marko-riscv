#pragma once
#include <memory>
#include <vector>
#include <optional>
#include <cstdint>
#include <ctime>
#include <iostream>

#include "VMarkoRvCore.h"
#include "config.hpp"
#include "axi_signal.hpp"
#include "slaves/slave.hpp"

class VirtualAxiSlaves {
public:
    enum axi_resp_t { RESP_OKAY, RESP_EXOKAY, RESP_SLVERR, RESP_DECERR };
    enum axi_burst_t { BURST_FIXED, BURST_INCR, BURST_WRAP, BURST_RESERVED };
    enum axi_read_state_t { STAT_RIDLE, STAT_SEND };
    enum axi_write_state_t { STAT_WIDLE, STAT_WRITE_DATA, STAT_WRITE_RESP };

    struct ReadTransaction {
        axi_read_state_t state;
        uint8_t beat;
        std::optional<uint64_t> held_data;
        uint64_t addr;
        uint8_t size;
        axi_burst_t burst;
        uint16_t id;
        uint8_t len;
        bool lock;
    };

    struct WriteTransaction {
        axi_write_state_t state;
        uint8_t beat;
        uint64_t addr;
        uint8_t size;
        axi_burst_t burst;
        uint16_t id;
        uint8_t len;
        bool lock;
        axi_resp_t resp;
    };

    struct ReservedItem {
        bool valid = false;
        uint64_t addr = 0;
        uint8_t size = 0;
        bool isConflict(uint64_t addr, uint8_t size);
    };

    VirtualAxiSlaves();
    ~VirtualAxiSlaves();

    uint64_t register_slave(std::shared_ptr<Slave> slave);
    std::shared_ptr<Slave> get_slave(uint64_t id);
    void sim_step(axiSignal &axi);

private:
    std::vector<std::shared_ptr<Slave>> slaves;
    ReadTransaction current_read;
    WriteTransaction current_write;
    std::vector<ReservedItem> reserved_items;

    void empty_read_transaction();
    void empty_write_transaction();
    uint64_t calculate_next_addr(uint64_t base_addr, uint8_t size, axi_burst_t burst, uint8_t beat);
    void handle_read(axiSignal &axi);
    void handle_write(axiSignal &axi);
};