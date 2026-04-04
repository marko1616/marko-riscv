#pragma once

#include <string>
#include <cstdint>
#include <vector>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <format>
#include <stdexcept>
#include <cassert>

#include "slave.hpp"
#include "../elf.hpp"

class VirtualRAM : public Slave {
public:
    explicit VirtualRAM(uint64_t base_addr, uint64_t size);
    ~VirtualRAM();

    uint64_t read(uint64_t addr, uint8_t size) override;
    void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) override;

    void load_elf(const std::string& file_path);
    void load_bin(const std::string& file_path, uint64_t addr);

    uint8_t* ram = nullptr;
    uint64_t size = 0;

private:
    static std::vector<uint8_t> read_file(const std::string& file_path);
    void check_range(uint64_t addr, uint64_t len, const std::string& what) const;
};
