#pragma once
#include <iostream>
#include <ranges>
#include <cstdint>

#include "slave.hpp"

#define MTIME_OFFSET	0xbff8
#define MTIMECMP_OFFSET	0x4000
#define MSIP_OFFSET	0x0

class VirtualCLINT : public Slave {
public:
    explicit VirtualCLINT(uint64_t base_addr);

    uint64_t read(uint64_t addr, uint8_t size) override;
    void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) override;
    void step(const std::unique_ptr<VMarkoRvCore> &top) override;
private:
    uint64_t mtime = 0;
    uint64_t mtimecmp = 0;
    uint32_t msip = 0;
};
