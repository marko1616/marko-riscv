#pragma once
#include <string>
#include <cstdint>
#include <memory>
#include <cassert>

#include "slave.hpp"
#include "../elf.hpp"

class VirtualRAM : public Slave {
public:
    explicit VirtualRAM(uint64_t base_addr, const std::string& file_path, uint64_t size);
    ~VirtualRAM();

    uint64_t read(uint64_t addr, uint8_t size) override;
    void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) override;

    uint8_t* ram;
    uint64_t size;

private:
    int init_ram(const std::string& file_path, uint64_t size);
};
