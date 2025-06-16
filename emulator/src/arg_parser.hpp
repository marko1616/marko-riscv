#pragma once
#include <string>
#include <optional>
#include <iostream>
#include <format>

#include <cxxopts.hpp>

#include "config.hpp"

struct parsedArgs {
    std::string ram_path;
    std::string rom_path;
    std::optional<std::string> ram_dump;
    uint64_t max_clock = CFG_DEFAULT_MAX_CLOCK;
    bool verbose = false;
    bool axi_debug = false;
    bool rob_debug = false;
    bool rs_debug = false;
    bool rt_debug = false;
    bool rf_debug = false;
};

// Returns 0 on success, 1 on error
int parse_args(int argc, char **argv, parsedArgs &args);
