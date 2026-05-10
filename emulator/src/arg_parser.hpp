#pragma once

#include <cstdint>
#include <format>
#include <iostream>
#include <map>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

#include "config.hpp"
#include <cxxopts.hpp>

enum class PayloadType { Elf, Bin };

struct PayloadSpec {
    PayloadType type;
    std::string file_path;
    std::optional<uint64_t> addr;
};

struct EventBreakpoint {
    std::string event_type;
    std::map<std::string, uint64_t> filters;
};

struct parsedArgs {
    std::vector<PayloadSpec> rom_payloads;
    std::vector<PayloadSpec> ram_payloads;

    std::optional<std::string> ram_dump;
    std::optional<std::string> vcd_dump;
    uint64_t max_clock = CFG_DEFAULT_MAX_CLOCK;
    double timer_scale = 1.0;
    bool stable_clock = false;
    bool verbose = false;
    bool axi_debug = false;
    bool rob_debug = false;
    bool rs_debug = false;
    bool rt_debug = false;
    bool rf_debug = false;

    std::vector<uint64_t> cleanup_dcache_addrs;
    std::vector<EventBreakpoint> event_breakpoints;
};

int parse_args(int argc, char **argv, parsedArgs &args);