#include "arg_parser.hpp"

static bool parse_bp_filters(const std::string& token,
                             std::map<std::string, uint64_t>& filters)
{
    std::istringstream ss(token);
    std::string pair;
    while (std::getline(ss, pair, ',')) {
        auto eq = pair.find('=');
        if (eq == std::string::npos) {
            std::cerr << "Invalid breakpoint filter (missing '='): " << pair << "\n";
            return false;
        }

        std::string key = pair.substr(0, eq);
        std::string val = pair.substr(eq + 1);
        try {
            filters[key] = std::stoull(val, nullptr, 0);
        } catch (...) {
            std::cerr << "Invalid value for breakpoint filter '" << key << "': " << val << "\n";
            return false;
        }

        std::cout << std::format("    Filter: {}={:#x}\n", key, filters[key]);
    }
    return true;
}

static bool parse_one_breakpoint(const std::string& spec, EventBreakpoint& bp)
{
    std::cout << std::format("Parsing breakpoint spec: '{}'\n", spec);

    static const std::vector<std::string> valid_types = {
        "issue", "commit", "discon", "retire"
    };

    auto colon = spec.find(':');
    bp.event_type = (colon == std::string::npos) ? spec : spec.substr(0, colon);

    bool type_ok = false;
    for (const auto& t : valid_types) {
        if (bp.event_type == t) {
            type_ok = true;
            break;
        }
    }

    if (!type_ok) {
        std::cerr << "Unknown event type in --break-on: " << bp.event_type << "\n";
        return false;
    }

    if (colon != std::string::npos && colon + 1 < spec.size()) {
        if (!parse_bp_filters(spec.substr(colon + 1), bp.filters)) {
            return false;
        }
    }

    return true;
}

static bool parse_one_payload(const std::string& spec, PayloadSpec& payload)
{
    if (spec.rfind("elf:", 0) == 0) {
        payload.type = PayloadType::Elf;
        payload.file_path = spec.substr(4);
        payload.addr.reset();

        if (payload.file_path.empty()) {
            std::cerr << "Invalid ELF payload spec: " << spec << "\n";
            return false;
        }
        return true;
    }

    if (spec.rfind("bin:", 0) == 0) {
        const std::string body = spec.substr(4);
        const auto at = body.rfind('@');
        if (at == std::string::npos || at == 0 || at + 1 >= body.size()) {
            std::cerr << "Invalid BIN payload spec: " << spec
                      << ", expected format: bin:<path>@<addr>\n";
            return false;
        }

        payload.type = PayloadType::Bin;
        payload.file_path = body.substr(0, at);

        try {
            payload.addr = std::stoull(body.substr(at + 1), nullptr, 0);
        } catch (...) {
            std::cerr << "Invalid BIN load address in payload spec: " << spec << "\n";
            return false;
        }

        return true;
    }

    std::cerr << "Invalid payload spec: " << spec
              << ", expected format: elf:<path> or bin:<path>@<addr>\n";
    return false;
}

static bool append_payloads(const std::vector<std::string>& specs,
                            std::vector<PayloadSpec>& payloads)
{
    for (const auto& spec : specs) {
        PayloadSpec payload;
        if (!parse_one_payload(spec, payload)) {
            return false;
        }
        payloads.push_back(std::move(payload));
    }
    return true;
}

static void print_payloads(const std::string& name,
                           const std::vector<PayloadSpec>& payloads)
{
    if (payloads.empty()) {
        std::cout << std::format("{} payloads: <none>\n", name);
        return;
    }

    std::cout << std::format("{} payloads:\n", name);
    for (const auto& payload : payloads) {
        if (payload.type == PayloadType::Elf) {
            std::cout << std::format("  ELF  {}\n", payload.file_path);
        } else {
            std::cout << std::format("  BIN  {} @ {:#x}\n",
                                     payload.file_path,
                                     payload.addr.value());
        }
    }
}

int parse_args(int argc, char **argv, parsedArgs &args) {
    try {
        cxxopts::Options options(argv[0], "MarkoRvCore simulator");

        options.add_options()
            ("rom-load", "ROM payload, repeatable: elf:<path> | bin:<path>@<addr>",
             cxxopts::value<std::vector<std::string>>())
            ("ram-load", "RAM payload, repeatable: elf:<path> | bin:<path>@<addr>",
             cxxopts::value<std::vector<std::string>>())

            ("ram-dump", "Dump the memory after the run is complete",
             cxxopts::value<std::string>())
            ("vcd-dump", "Dump the waveform after the run is complete",
             cxxopts::value<std::string>())
            ("max-clock", "Maximum clock cycles to simulate (hex value)",
             cxxopts::value<std::string>()->default_value(std::to_string(CFG_DEFAULT_MAX_CLOCK)))
            ("timer-scale", "Scale factor for timer (default: 1.0)",
             cxxopts::value<double>()->default_value("1.0"))
            ("stable-clock", "Use a stable clock to ensure reproducible results",
             cxxopts::value<bool>()->default_value("false")->implicit_value("true"))
            ("verbose", "Enable verbose output")
            ("d,debug", "Enable debug options (comma separated: axi,rob,rs,rt,rf)",
             cxxopts::value<std::vector<std::string>>())
            ("break-on",
             "Auto-pause when event fires with matching fields. "
             "Format: event_type[:field=val,...] "
             "(event_type: issue|commit|discon|retire). "
             "May be specified multiple times.",
             cxxopts::value<std::vector<std::string>>())
            ("cleanup-dcache", "Clean certain addrs(comma separated) of dcache data at end of simulation",
             cxxopts::value<std::vector<uint64_t>>())
            ("help", "Print usage information");

        auto result = options.parse(argc, argv);

        if (result.count("help")) {
            std::cout << options.help() << std::endl;
            return 1;
        }

        if (result.count("rom-load")) {
            if (!append_payloads(result["rom-load"].as<std::vector<std::string>>(),
                                 args.rom_payloads)) {
                return 1;
            }
        }

        if (result.count("ram-load")) {
            if (!append_payloads(result["ram-load"].as<std::vector<std::string>>(),
                                 args.ram_payloads)) {
                return 1;
            }
        }

        if (result.count("ram-dump")) {
            args.ram_dump = result["ram-dump"].as<std::string>();
        }

        if (result.count("vcd-dump")) {
            args.vcd_dump = result["vcd-dump"].as<std::string>();
        }

        if (result.count("max-clock")) {
            try {
                args.max_clock = std::stoull(result["max-clock"].as<std::string>(), nullptr, 16);
            } catch (...) {
                std::cerr << "Invalid hex value for --max-clock\n";
                return 1;
            }
        } else {
            args.max_clock = CFG_DEFAULT_MAX_CLOCK;
        }

        if (result.count("timer-scale")) {
            args.timer_scale = result["timer-scale"].as<double>();
        } else {
            args.timer_scale = 1.0;
        }

        if (result.count("stable-clock")) {
            args.stable_clock = result["stable-clock"].as<bool>();
        } else {
            args.stable_clock = false;
        }

        args.verbose = result.count("verbose") > 0;

        if (result.count("debug")) {
            auto debug_flags = result["debug"].as<std::vector<std::string>>();
            for (const auto& flag : debug_flags) {
                if (flag == "axi") args.axi_debug = true;
                else if (flag == "rob") args.rob_debug = true;
                else if (flag == "rs") args.rs_debug = true;
                else if (flag == "rt") args.rt_debug = true;
                else if (flag == "rf") args.rf_debug = true;
                else {
                    std::cerr << "Warning: Unknown debug flag: " << flag << "\n";
                }
            }
        }

        if (result.count("cleanup-dcache")) {
            args.cleanup_dcache_addrs = result["cleanup-dcache"].as<std::vector<uint64_t>>();
        }

        if (result.count("break-on")) {
            for (const auto& spec : result["break-on"].as<std::vector<std::string>>()) {
                EventBreakpoint bp;
                if (!parse_one_breakpoint(spec, bp)) {
                    return 1;
                }
                args.event_breakpoints.push_back(std::move(bp));
                std::cout << "\n";
            }
        }

        print_payloads("ROM", args.rom_payloads);
        print_payloads("RAM", args.ram_payloads);

        return 0;
    } catch (...) {
        std::cerr << "Error parsing options\n";
        return 1;
    }
}
