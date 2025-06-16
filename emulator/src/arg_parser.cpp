#include "arg_parser.hpp"

int parse_args(int argc, char **argv, parsedArgs &args) {
    try {
        cxxopts::Options options(argv[0], "MarkoRvCore simulator");

        options.add_options()
            ("rom-path", "Path to ROM payload", cxxopts::value<std::string>())
            ("ram-path", "Path to RAM payload", cxxopts::value<std::string>())
            ("ram-dump", "Dump the memory after the run is complete", cxxopts::value<std::string>())
            ("max-clock", "Maximum clock cycles to simulate (hex value)", cxxopts::value<std::string>()->default_value(std::to_string(CFG_DEFAULT_MAX_CLOCK)))
            ("verbose", "Enable verbose output")
            ("d,debug", "Enable debug options (comma separated: axi,rob,rs,rt,rf)", cxxopts::value<std::vector<std::string>>())
            ("help", "Print usage information");

        auto result = options.parse(argc, argv);

        if (result.count("help")) {
            std::cout << options.help() << std::endl;
            return 1;
        }

        if (!result.count("ram-path") || !result.count("rom-path")) {
            std::cerr << "Error: --ram-path and --rom-path are required.\n";
            std::cout << options.help() << std::endl;
            return 1;
        }

        args.ram_path = result["ram-path"].as<std::string>();
        args.rom_path = result["rom-path"].as<std::string>();

        if (result.count("ram-dump")) {
            args.ram_dump = result["ram-dump"].as<std::string>();
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
                    std::cerr << "Warning: Unknown debug flag: " << flag << std::endl;
                }
            }
        }

        std::cout << std::format("ROM payload path: {}\n", args.rom_path);
        std::cout << std::format("RAM payload path: {}\n", args.ram_path);

        return 0;
    } catch (...) {
        std::cerr << "Error parsing options\n";
        return 1;
    }
}
