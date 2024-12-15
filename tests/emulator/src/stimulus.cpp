#include <random>
#include <ranges>
#include <iostream>
#include <format>
#include <fstream>
#include <memory>

#include <getopt.h>
#include <capstone/capstone.h>

#include "markorv_core.h"

#define BUS_WIDTH 8
#define DEFAULT_MAX_CLOCK 0x400
#define ROM_SIZE (1024LL * 16)
#define RAM_SIZE (1024LL * 1024 * 8)

#define USAGE "Usage: {} --rom-path <bin rom payload> --ram-path <bin ram payload> [--max-clock <max clock>] [--random-async-interruption] [--assert-last-peek <hex>] [--random-range <min:max>] [--axi-debug]\n"

struct parsedArgs {
    std::string ram_path;
    std::string rom_path;
    uint64_t max_clock = DEFAULT_MAX_CLOCK;
    bool random_async_interruption = false;
    bool assert_last_peek_valid = false;
    uint64_t assert_last_peek = 0;
    bool random_range_valid = false;
    int random_range_min = 0;
    int random_range_max = 0;
    bool verbose = false;
    bool axi_debug = false;
};

struct IRQ {
    bool valid = 0;
    uint32_t code = 0;
};

struct axiSignal {
    // Write request signals
    bool awvalid;                  // Master
    bool awready;                  // Slave
    uint64_t awaddr;               // Master
    uint8_t awprot;                // Master (3 bits)

    // Write data signals
    bool wvalid;                   // Master
    bool wready;                   // Slave
    uint64_t wdata;                // Master
    uint8_t wstrb;                 // Master (data_width / 8, assuming data_width = 32)

    // Write response signals
    bool bvalid;                   // Slave
    bool bready;                   // Master
    uint8_t bresp;                 // Slave (2 bits)

    // Read request signals
    bool arvalid;                  // Master
    bool arready;                  // Slave
    uint64_t araddr;               // Master
    uint8_t arprot;                // Master (3 bits)

    // Read data signals
    bool rvalid;                   // Slave
    bool rready;                   // Master
    uint64_t rdata;                // Slave
    uint8_t rresp;                 // Slave (2 bits)
};

class Slave {
public:
    std::ranges::iota_view<uint64_t, uint64_t> range;
    virtual ~Slave() = default;

    virtual uint64_t read(uint64_t addr) = 0;
    virtual void write(uint64_t addr, uint64_t data, uint8_t strb) = 0;

    virtual void posedge_step() {
        return;
    }

    virtual IRQ get_irq() {
        IRQ irq;
        irq.valid = false;
        irq.code = 0;
        return irq;
    }
};

class VirtualUart : public Slave {
public:
    explicit VirtualUart() {
        range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, 0x8);

        ier = 0x00;
        // Interrupt pending when isr[0] == 0
        isr = 0x01;
        fcr = 0x00;
        // Word lengths from 5(lcr[1,0]==0) to 8(lcr[1,0]==3) bits
        lcr = 0x03;
        mcr = 0x00;
        // Clear to send when lsr[5]==1 and lsr[6]==1
        lsr = 0x60;
        msr = 0x00;
        spr = 0x00;
    }

    uint64_t read(uint64_t addr) override {
        switch (addr) {
            case 0x0:
                // TODO
                return 0x00;
            case 0x1:
                return ier;
            case 0x2:
                return isr;
            case 0x3:
                return lcr;
            case 0x4:
                return mcr;
            case 0x5:
                return lsr;
            case 0x6:
                return msr;
            case 0x7:
                return spr;
            default:
                return 0;
        }
    }

    void write(uint64_t addr, uint64_t data, uint8_t strb) override {
        uint8_t value = static_cast<uint8_t>(data);
        switch (addr) {
            case 0x0:
                std::cout << value;
                break;
            case 0x1:
                ier = value & 0xcf;
                break;
            case 0x2:
                fcr = value & 0xdf;
                break;
            case 0x3:
                lcr = value;
                break;
            case 0x4:
                mcr = value & 0x1f;
                break;
            case 0x7:  // Scratch Pad Register
                spr = value;
                break;
        }
    }
private:
    // 0x0(r)   Receiver Holding Register 
    // 0x0(w)   Transmitter Holding Register(WIP)
    uint8_t ier; // 0x1(r/w) Interrupt Enable Register(WIP)
    uint8_t isr; // 0x2(r)   Interrupt Status Register(WIP)
    uint8_t fcr; // 0x2(w)   FIFO Control Register(WIP)
    uint8_t lcr; // 0x3(r/w) Line Control Register(WIP)
    uint8_t mcr; // 0x4(r/w) Modem Control Register(WIP)
    uint8_t lsr; // 0x5(r)   Line Status Register(WIP)
    uint8_t msr; // 0x6(r)   Modem Status Register(WIP)
    uint8_t spr; // 0x7(r/w) Scratch Pad Register(WIP)
};

class VirtualRAM : public Slave {
public:
    explicit VirtualRAM(const std::string& file_path, uint64_t size) {
        range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, size);

        ram = static_cast<uint8_t*>(std::malloc(size));
        if (ram == nullptr) {
            throw std::runtime_error("Failed to allocate RAM");
        }
        std::memset(ram, 0, size);

        if (init_ram(file_path, size)) {
            throw std::runtime_error("Failed to initialize RAM");
        }
    }

    ~VirtualRAM() {
        if(ram != nullptr)
            std::free(ram);
    }

    uint64_t read(uint64_t addr) override {
        uint64_t data = 0;
        for (int i = 0; i < 8; i++) {
            data |= static_cast<uint64_t>(ram[addr + i]) << (8 * i);
        }
        return data;
    }

    void write(uint64_t addr, uint64_t data, uint8_t strb) override {
        for (int i = 0; i < 8; i++) {
            if (strb & (1 << i)) {
                ram[addr + i] = static_cast<uint8_t>(data >> (8 * i));
            }
        }
    }
private:
    uint8_t* ram;

    int init_ram(const std::string& file_path, uint64_t size) {
        std::ifstream file(file_path, std::ios::binary);
        if (!file) {
            std::cerr << "Can't open file:" << file_path << std::endl;
            return 1;
        }

        file.seekg(0, std::ios::end);
        std::streamsize file_size = file.tellg();
        file.seekg(0, std::ios::beg);

        if (file_size > size) {
            std::cerr << "File size is too big:" << file_size << std::endl;
            return 1;
        }
        file.read(reinterpret_cast<char*>(ram), file_size);

        return 0;
    }
};

class VirtualAxiSlaves {
public:
    struct slaveWithAddr {
        std::unique_ptr<Slave> slave;
        uint64_t base_addr;
    };

    enum axi_read_state {
        stat_ridle,
        stat_send
    };
    enum axi_write_state {
        stat_widle,
        stat_accept_req,
        stat_outfire,
    };
    explicit VirtualAxiSlaves() {
        read_state = stat_ridle;
        write_state = stat_widle;
        read_addr = 0;
        write_addr = 0;
        write_data = 0;
        write_mask = 0;
    }

    ~VirtualAxiSlaves() = default;

    void register_slave(std::unique_ptr<Slave> slave, uint64_t base_addr) {
        slaves.emplace_back(std::move(slave), base_addr);
    }

    void sim_step(axiSignal &axi) {
        handle_read(axi);
        handle_write(axi);
    }

private:
    std::vector<slaveWithAddr> slaves;
    axi_read_state read_state;
    axi_write_state write_state;
    uint64_t read_addr;
    uint64_t write_addr;
    uint64_t write_data;
    uint8_t write_mask;

    void handle_read(axiSignal &axi) {
        switch (read_state) {
            case stat_ridle:
                axi.arready = true;
                if (axi.arvalid) {
                    read_addr = axi.araddr;
                    read_state = stat_send;
                }
                break;

            case stat_send:
                axi.rvalid = true;
                auto slave = std::ranges::find_if(slaves,
                                    [this](const slaveWithAddr &slave) -> bool {
                                        return std::ranges::contains(slave.slave->range, this->read_addr-slave.base_addr);
                                    });
                if (slave != slaves.end()) {
                    axi.rdata = (*slave).slave->read(read_addr-(*slave).base_addr);
                    axi.rresp = 0b00; // OKAY
                } else {
                    axi.rdata = 0;
                    axi.rresp = 0b11; // DECERR
                }

                if (axi.rready) {
                    read_state = stat_ridle;
                }
                break;
        }
    }

    void handle_write(axiSignal &axi) {
        switch (write_state) {
            case stat_widle:
                axi.awready = true;
                if (axi.awvalid) {
                    write_addr = axi.awaddr;
                    write_state = stat_accept_req;
                }
                break;

            case stat_accept_req:
                axi.wready = true;
                if (axi.wvalid) {
                    write_data = axi.wdata;
                    write_mask = axi.wstrb;
                    write_state = stat_outfire;
                }
                break;

            case stat_outfire:
                axi.bvalid = true;
                auto slave = std::ranges::find_if(slaves,
                                    [this](const slaveWithAddr &slave) -> bool {
                                        return std::ranges::contains(slave.slave->range, this->write_addr-slave.base_addr);
                                    });
                if (slave != slaves.end()) {
                    (*slave).slave->write(write_addr-(*slave).base_addr, write_data, write_mask);
                    axi.bresp = 0b00; // OKAY
                } else {
                    axi.bresp = 0b11; // DECERR
                }

                if (axi.bready) {
                    write_state = stat_widle;
                }
                break;
        }
    }
};

csh capstone_handle;

void read_axi(const std::unique_ptr<VMarkoRvCore> &top, axiSignal &axi) {
    // Write request signals
    axi.awvalid = top->io_axi_awvalid;
    axi.awaddr = top->io_axi_awaddr;
    axi.awprot = top->io_axi_awprot;

    // Write data signals
    axi.wvalid = top->io_axi_wvalid;
    axi.wdata = top->io_axi_wdata;
    axi.wstrb = top->io_axi_wstrb;

    // Write response signals
    axi.bready = top->io_axi_bready;

    // Read request signals
    axi.arvalid = top->io_axi_arvalid;
    axi.araddr = top->io_axi_araddr;
    axi.arprot = top->io_axi_arprot;

    // Read data signals
    axi.rready = top->io_axi_rready;
}

void set_axi(const std::unique_ptr<VMarkoRvCore> &top, const axiSignal &axi) {
    // Write response signals
    top->io_axi_bvalid = axi.bvalid;
    top->io_axi_bresp = axi.bresp;

    // Read request signals
    top->io_axi_arready = axi.arready;

    // Read data signals
    top->io_axi_rvalid = axi.rvalid;
    top->io_axi_rdata = axi.rdata;
    top->io_axi_rresp = axi.rresp;

    // Write request signals
    top->io_axi_awready = axi.awready;

    // Write data signals
    top->io_axi_wready = axi.wready;
}

void clear_axi(const std::unique_ptr<VMarkoRvCore> &top) {
    // Write response signals
    top->io_axi_bvalid = false;
    top->io_axi_bresp = 0;

    // Read request signals
    top->io_axi_arready = false;

    // Read data signals
    top->io_axi_rvalid = false;
    top->io_axi_rdata = 0;
    top->io_axi_rresp = 0;

    // Write request signals
    top->io_axi_awready = false;

    // Write data signals
    top->io_axi_wready = false;
}

void axi_debug(const axiSignal& axi) {
    std::cout << std::format("AXI Signal State:\n"
                             "Write Request:\n"
                             "  awvalid: {}\n"
                             "  awready: {}\n"
                             "  awaddr:  0x{:016x}\n"
                             "  awprot:  0x{:02x}\n"
                             "\n"
                             "Write Data:\n"
                             "  wvalid:  {}\n"
                             "  wready:  {}\n"
                             "  wdata:   0x{:016x}\n"
                             "  wstrb:   0x{:02x}\n"
                             "\n"
                             "Write Response:\n"
                             "  bvalid:  {}\n"
                             "  bready:  {}\n"
                             "  bresp:   0x{:02x}\n"
                             "\n"
                             "Read Request:\n"
                             "  arvalid: {}\n"
                             "  arready: {}\n"
                             "  araddr:  0x{:016x}\n"
                             "  arprot:  0x{:02x}\n"
                             "\n"
                             "Read Data:\n"
                             "  rvalid:  {}\n"
                             "  rready:  {}\n"
                             "  rdata:   0x{:016x}\n"
                             "  rresp:   0x{:02x}\n",
                             axi.awvalid, axi.awready, axi.awaddr, axi.awprot,
                             axi.wvalid, axi.wready, axi.wdata, axi.wstrb,
                             axi.bvalid, axi.bready, axi.bresp,
                             axi.arvalid, axi.arready, axi.araddr, axi.arprot,
                             axi.rvalid, axi.rready, axi.rdata, axi.rresp);
}

void cycle_verbose(uint64_t cycle, uint64_t pc, uint64_t raw_instr, uint64_t peek, bool interrupted) {
    uint8_t raw_code[4] = {0};
    std::cout << std::format("Cycle: 0x{:04x} PC: 0x{:016x} Instr: 0x{:08x} Peek: 0x{:04x} Interrupted: {} Asm: ",cycle, pc, raw_instr, peek, interrupted);
    for(int i=0;i<4;i++) {
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

int parse_args(int argc, char **argv, parsedArgs &args) {
    // Define long options
    struct option long_options[] = {
        {"rom-path", required_argument, nullptr, 0},
        {"ram-path", required_argument, nullptr, 0},
        {"max-clock", required_argument, nullptr, 0},
        {"random-async-interruption", no_argument, nullptr, 0},
        {"assert-last-peek", required_argument, nullptr, 0},
        {"random-range", required_argument, nullptr, 0},
        {"verbose", no_argument, nullptr, 0},
        {"axi-debug", no_argument, nullptr, 0},
        {"help", no_argument, nullptr, 0},
        {nullptr, 0, nullptr, 0}
    };

    int option;
    int option_index;
    while ((option = getopt_long(argc, argv, "", long_options, &option_index)) != -1) {
        switch (option_index) {
            case 0:
                if (optarg == nullptr) {
                    break;
                }
                args.rom_path = optarg;
                break;
            case 1:
                if (optarg == nullptr) {
                    break;
                }
                args.ram_path = optarg;
                break;
            case 2:
                if (optarg == nullptr) {
                    break;
                }
                args.max_clock = std::stoull(optarg, nullptr, 16);
                break;
            case 3:
                args.random_async_interruption = true;
                break;
            case 4:
                if (optarg == nullptr) {
                    std::cerr << "Error: --assert-last-peek requires a value.\n";
                    return 1;
                }
                try {
                    args.assert_last_peek = std::stoull(optarg, nullptr, 16);
                    args.assert_last_peek_valid = true;
                } catch (const std::invalid_argument &) {
                    std::cerr << std::format("Invalid hex value for --assert-last-peek: {}\n", optarg);
                    return 1;
                } catch (const std::out_of_range &) {
                    std::cerr << std::format("Hex value out of range for --assert-last-peek: {}\n", optarg);
                    return 1;
                }
                break;
            case 5:
                if (optarg == nullptr) {
                    std::cerr << "Error: --random-range requires a value.\n";
                    return 1;
                }
                try {
                    std::string range(optarg);
                    size_t colon_pos = range.find(':');
                    if (colon_pos == std::string::npos) {
                        throw std::invalid_argument("Range format must be min:max");
                    }

                    args.random_range_min = std::stoi(range.substr(0, colon_pos));
                    args.random_range_max = std::stoi(range.substr(colon_pos + 1));

                    if (args.random_range_min > args.random_range_max) {
                        throw std::invalid_argument("Min value must be <= max value");
                    }

                    args.random_range_valid = true;
                } catch (const std::invalid_argument &e) {
                    std::cerr << std::format("Invalid range format for --random-range: {} ({})\n", optarg, e.what());
                    return 1;
                } catch (const std::out_of_range &) {
                    std::cerr << std::format("Range values out of range for --random-range: {}\n", optarg);
                    return 1;
                }
                break;
            case 6:
                args.verbose = true;
                break;
            case 7:
                args.axi_debug = true;
                break;
            case 8: // --help
                std::cout << std::format(USAGE, argv[0]);
                return 0;
            default:
                std::cerr << std::format("Unknown option or missing argument. Use --help for usage information.\n");
                return 1;
        }
    }

    // Check if required parameter is provided
    if (args.ram_path.empty()) {
        std::cerr << "Error: --ram-path is required.\n";
        std::cerr << std::format(USAGE, argv[0]);
        return 1;
    }
    if (args.rom_path.empty()) {
        std::cerr << "Error: --rom-path is required.\n";
        std::cerr << std::format(USAGE, argv[0]);
        return 1;
    }

    // Output parsed results
    std::cout << std::format("ROM payload path: {}\n", args.rom_path);
    std::cout << std::format("RAM payload path: {}\n", args.ram_path);
    std::cout << std::format("Random async interruption {}\n", args.random_async_interruption ? "enabled" : "disabled");

    if (args.assert_last_peek_valid) {
        std::cout << std::format("Assert last peek hex: 0x{:x}\n", args.assert_last_peek);
    }

    if (args.random_range_valid) {
        std::cout << std::format("Random range: {} to {}\n", args.random_range_min, args.random_range_max);
    }

    return 0;
}

void init_stimulus(const std::unique_ptr<VMarkoRvCore> &top) {
    clear_axi(top);
    top->io_debug_async_flush = 0;
}

int main(int argc, char **argv, char **env)
{
    Verilated::commandArgs(argc, argv);
    auto top = std::make_unique<VMarkoRvCore>();
    auto context = std::make_unique<VerilatedContext>();

    parsedArgs args;
    if(parse_args(argc, argv, args) != 0)
        return 1;

    // Init
    top->clock = 0;
    top->reset = 0;
    VirtualAxiSlaves slaves;
    slaves.register_slave(std::make_unique<VirtualRAM>(args.rom_path, ROM_SIZE), 0x10000000);
    slaves.register_slave(std::make_unique<VirtualRAM>(args.ram_path, RAM_SIZE), 0x80000000);
    slaves.register_slave(std::make_unique<VirtualUart>(), 0x20000000);

    // RV64G only not for C extension.
    if (cs_open(CS_ARCH_RISCV, CS_MODE_RISCV64, &capstone_handle) != CS_ERR_OK) {
        std::cerr << "Capstone engine failed to init.\n";
        return 1;
    }

    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<uint64_t> dist;
    if(args.random_range_valid) {
        dist = std::uniform_int_distribution<uint64_t>(args.random_range_min, args.random_range_max);
    } else {
        dist = std::uniform_int_distribution<uint64_t>(1, args.max_clock-1);
    }
    uint64_t trigger_time = dist(gen);
    bool triggered = false;

    // Main loop
    uint64_t clock_cnt = 0;
    axiSignal axi;    
    while (!Verilated::gotFinish() && clock_cnt < args.max_clock) {
        if (clock_cnt < 4)
            top->reset = 1;
        else
            top->reset = 0;
        
        // Posedge clk
        context->timeInc(1);
        top->clock = 1;
        top->eval();
        // Debug out
        if(args.verbose)
            cycle_verbose(clock_cnt, top->io_pc, top->io_instr_now, top->io_peek, triggered);
        init_stimulus(top);
        // Handle axi
        std::memset(&axi, 0, sizeof(axiSignal));
        read_axi(top, axi);
        slaves.sim_step(axi);
        if (args.axi_debug)
            axi_debug(axi);
        set_axi(top, axi);
        
        top->io_debug_async_flush = triggered;

        if (clock_cnt == trigger_time && args.random_async_interruption)
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