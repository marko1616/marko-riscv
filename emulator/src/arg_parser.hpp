#pragma once
#include <string>
#include <optional>
#include <iostream>
#include <format>

#include <cxxopts.hpp>

struct parsedArgs {
    std::string ram_path;
    std::string rom_path;
    std::optional<std::string> ram_dump;
    uint64_t max_clock;
    bool verbose;
    bool axi_debug;
};

// Returns 0 on success, 1 on error
int parse_args(int argc, char **argv, parsedArgs &args);
