#include <random>
#include <iostream>
#include <iomanip>
#include <fstream>
#include <memory>

#include <getopt.h>
#include <capstone/capstone.h>

#include "markorv_core.h"

#define MAX_CLOCK 1024
#define RAM_SIZE 4096

struct ParsedArgs {
    std::string hex_payload_path;
    bool random_async_interruption = false;
    uint64_t assert_last_peek = 0;
    bool assert_last_peek_valid = false;
};

static uint8_t ram[RAM_SIZE] = {0};
csh capstone_handle;
int init_ram(std::string file_path) {
    std::ifstream file(file_path);

    if (!file) {
        std::cerr << "Can't open file:" << file_path << std::endl;
        return 1;
    }

    uint64_t addr = 0;
    uint64_t data = 0;
    std::string line;
    while (std::getline(file, line)) {
        try {
            data = std::stoull(line, nullptr, 16);
        } catch (const std::invalid_argument& e) {
            std::cerr << "Invalid hex string." << line << std::endl;
            return 1;
        } catch (const std::out_of_range& e) {
            std::cerr << "Hex payload out of qword range." << std::endl;
            return 1;
        }

        // Read line as little endian data.
        for(int i=0;i<4;i++) {
            if(addr >= RAM_SIZE) {
                std::cerr << "Hex payload is bigger than *RAM_SIZE*." << std::endl;
                return 1;
            }
            ram[addr] = static_cast<uint8_t>(data >> 8*i);
            addr++;
        }
    }

    return 0;
}

void print_cycle(uint64_t cycle, uint64_t pc, uint64_t raw_instr, uint64_t peek) {
    uint8_t raw_code[4] = {0};
    std::cout << std::hex    << std::setfill('0')
              << "Cycle: 0x" << std::setw(4)  << cycle     << " "
              << "PC: 0x"    << std::setw(16) << pc        << " "
              << "Instr: 0x" << std::setw(8)  << raw_instr << " "
              << "Peek: 0x"  << std::setw(4)  << peek      << " "
              << "Asm: ";
    for(int i=0;i<4;i++){
        raw_code[i] = static_cast<uint8_t>(raw_instr >> 8*i);
    }

    cs_insn *instr;
    uint64_t count;
    count = cs_disasm(capstone_handle, raw_code, 4, pc, 0, &instr);
	if (count > 0) {
		for (int i = 0;i<count;i++) {
			std::cout << instr[i].mnemonic << " " << instr[i].op_str << std::endl;
		}
		cs_free(instr, count);
	} else {
		std::cout << "invalid" << std::endl;
    }
}

int parse_args(int argc, char **argv, ParsedArgs &args) {
    struct option long_options[] = {
        {"random-async-interruption", no_argument, 0, 'r'},
        {"assert-last-peek", required_argument, 0, 'a'},
        {0, 0, 0, 0}
    };

    int option;
    while ((option = getopt_long(argc, argv, "f:a:", long_options, nullptr)) != -1) {
        switch (option) {
            case 'f':
                args.hex_payload_path = optarg;
                break;
            case 'r':
                args.random_async_interruption = true;
                break;
            case 'a':
                try {
                    args.assert_last_peek = std::stoull(optarg, nullptr, 16);
                    args.assert_last_peek_valid = true;
                } catch (const std::invalid_argument &) {
                    std::cerr << "Invalid hex value for --assert-last-peek: " << optarg << std::endl;
                    return 1;
                } catch (const std::out_of_range &) {
                    std::cerr << "Hex value out of range for --assert-last-peek: " << optarg << std::endl;
                    return 1;
                }
                break;
            case '?':
                std::cerr << "Usage: " << argv[0] << " -f <Hex-payload-path> [--random-async-interruption] [--assert-last-peek <hex>]" << std::endl;
                return 1;
        }
    }

    // Check if required -f parameter is provided
    if (args.hex_payload_path.empty()) {
        std::cerr << "Error: -f <Hex-payload-path> is required." << std::endl;
        std::cerr << "Usage: " << argv[0] << " -f <Hex-payload-path> [--random-async-interruption] [--assert-last-peek <hex>]" << std::endl;
        return 1;
    }

    // Output parsed results
    std::cout << "Hex Payload Path: " << args.hex_payload_path << std::endl;
    if (args.random_async_interruption) {
        std::cout << "Random async interruption enabled." << std::endl;
    } else {
        std::cout << "Random async interruption disabled." << std::endl;
    }

    if (args.assert_last_peek_valid) {
        std::cout << "Assert last peek hex: 0x" << std::hex << args.assert_last_peek << std::dec << std::endl;
    }

    return 0;
}

void init_stimulus(const std::unique_ptr<VMarkoRvCore> &top) {
    top->io_memio_read_addr_ready = 0;
    top->io_memio_read_data_valid = 0;
    top->io_memio_write_req_ready = 0;
    top->io_memio_write_outfire   = 0;

    top->io_debug_async_flush     = 0;
    top->io_memio_read_data_bits  = 0;
}

int main(int argc, char **argv, char **env)
{
    Verilated::commandArgs(argc, argv);
    auto top = std::make_unique<VMarkoRvCore>();
    auto context = std::make_unique<VerilatedContext>();

    ParsedArgs args;
    if(parse_args(argc, argv, args) != 0)
        return 1;
    std::cout << "Hex Payload Path: " << args.hex_payload_path << std::endl;

    if(init_ram(args.hex_payload_path)) {
        std::cout << "Can't load hex payload." << std::endl;
        return 1;
    }

    // Init.
    std::cout << std::hex << std::setfill('0');
    top->clock = 0;
    top->reset = 0;

    // RV64G only not for C extension.
    if (cs_open(CS_ARCH_RISCV, CS_MODE_RISCV64, &capstone_handle) != CS_ERR_OK) {
        std::cout << "Capstone engine failed to init." << std::endl;
    }

    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<uint64_t> dist(1, MAX_CLOCK / 2 - 1);
    uint64_t trigger_time = dist(gen);
    bool triggered = false;

    // Main loop
    uint64_t clock_cnt = 0;
    while (!Verilated::gotFinish() && clock_cnt < MAX_CLOCK) {
        if (clock_cnt > 1 && clock_cnt < 4)
            top->reset = 1;
        else
            top->reset = 0;

        // Posedge clk
        context->timeInc(1);
        top->clock = 1;
        top->eval();
        // Debug out
        print_cycle(clock_cnt, top->io_pc, top->io_instr_now, top->io_peek);
        init_stimulus(top);
        // Memory read
        top->io_memio_read_addr_ready = 1;
        auto read_enable = top->io_memio_read_addr_valid;
        auto read_addr = top->io_memio_read_addr_bits;
        uint64_t read_data = 0;
        if(read_enable) {
            top->io_memio_read_data_valid = 1;
            for(int i=0;i<8;i++) {
                if(read_addr + i < RAM_SIZE)
                    read_data |= static_cast<uint64_t>(ram[read_addr + i]) << 8*i;
            }
        }
        top->io_memio_read_data_bits = read_data;
        // Memory Write
        top->io_memio_write_req_ready = 1;
        auto write_enable = top->io_memio_write_req_valid;
        auto write_size = top->io_memio_write_req_bits_size;
        auto write_addr = top->io_memio_write_req_bits_addr;
        auto write_data = top->io_memio_write_req_bits_data;
        if(write_enable) {
            top->io_memio_write_outfire = 1;
            for(int i=0;i<(1<<write_size);i++) {
                if(write_addr < RAM_SIZE) 
                    ram[write_addr] = static_cast<uint8_t>(write_data >> 8*i);
            }
        }
        
        top->io_debug_async_flush = triggered;

        if (clock_cnt == trigger_time)
            triggered = true;
        
        if (top->io_debug_async_outfired)
            triggered = false;

        // Negedge clk
        context->timeInc(1);
        top->clock = 0;
        top->eval();
        clock_cnt++;
    }

    if(args.assert_last_peek_valid) {
        if(args.assert_last_peek == top->io_peek)
            std::clog << "Assertion passed." << std::endl;
        else
            std::clog << "Assertion failed." << std::endl;
    }
    
    // Clean up
    top->final();
    return 0;
}