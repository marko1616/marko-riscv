#include <iostream>
#include <fstream>
#include <memory>

#include "markorv_core.h"

#define MAX_CLOCK 1024
#define RAM_SIZE 4096

static uint8_t ram[RAM_SIZE] = {0};

int init_ram(std::string file_path) {
    std::ifstream file(file_path);

    if (!file) {
        std::cerr << "Can't open file:" << file_path << std::endl;
        return 1;
    }

    uint64_t addr = 0;
    uint64_t data = 0;
    std::string line;
    std::size_t length;
    while (std::getline(file, line)) {
        length = line.length();
        try {
            data = std::stoull(line, nullptr, 16);
        } catch (const std::invalid_argument& e) {
            std::cerr << "Invalid hex string." << line << std::endl;
            return 1;
        } catch (const std::out_of_range& e) {
            std::cerr << "Hex payload out of qword range." << std::endl;
            return 1;
        }

        if(length % 2 != 0) {
            // Aligned to byte.
            length++;
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

int main(int argc, char **argv, char **env)
{
    Verilated::commandArgs(argc, argv);
    auto top = std::make_unique<VMarkoRvCore>();
    auto context = std::make_unique<VerilatedContext>();

    if(argc != 2) {
        std::cout << "Usage: VMarkoRvCore <Hex-payload-path>." << std::endl;
        return 1;
    }

    if(init_ram(argv[1])) {
        std::cout << "Can't load hex payload." << std::endl;
        return 1;
    }

    // Init values.
    std::cout << std::hex;
    top->clock = 0;
    top->reset = 0;

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
        std::cout << "Cycle: " << clock_cnt 
                << " PC: " << top->io_pc 
                << " Instr: " << top->io_instr_now 
                << " Peek: " << top->io_peek 
                << std::endl;

        // Negedge clk
        context->timeInc(1);
        top->clock = 0;
        top->eval();
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
                uint64_t byte_addr = write_addr + i;
                if(write_addr < RAM_SIZE) 
                    ram[write_addr] = static_cast<uint8_t>(write_data >> 8*i);
            }
        }

        clock_cnt++;
    }
    
    // Clean up
    top->final();
    return 0;
}