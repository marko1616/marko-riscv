#include <ranges>
#include <iostream>
#include <format>
#include <fstream>
#include <memory>
#include <cassert>

#include <cxxopts.hpp>
#include <capstone/capstone.h>

#include "markorv_core.h"
#include "elf.hpp"

#define BUS_WIDTH 8
#define DEFAULT_MAX_CLOCK 0x400
#define ROM_SIZE (1024LL * 16)
#define RAM_SIZE (1024LL * 1024 * 8)

struct parsedArgs {
    std::string ram_path;
    std::string rom_path;
    std::optional<std::string> ram_dump;
    uint64_t max_clock = DEFAULT_MAX_CLOCK;
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
    uint64_t base_addr;
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
    explicit VirtualUart(uint64_t base_addr) {
        this->base_addr = base_addr;
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
    uint8_t* ram;
    uint64_t size;

    explicit VirtualRAM(uint64_t base_addr, const std::string& file_path, uint64_t size) {
        this->base_addr = base_addr;
        this->size = size;
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
    int init_ram(const std::string& file_path, uint64_t size) {
        std::ifstream file(file_path, std::ios::binary);
        if (!file) {
            std::cerr << "Can't open file:" << file_path << std::endl;
            return 1;
        }
        std::vector<uint8_t> raw((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
        auto elf = ELF::from_raw(raw);
        if (elf.get_class_type() != ELF::ClassType::ELFCLASS64) {
            throw std::runtime_error("ELF class type must be ELFCLASS64");
        }
        if (elf.get_machine_type() != ELF::MachineType::EM_RISCV) {
            throw std::runtime_error("ELF machine type must be EM_RISCV");
        }

        auto program_headers = elf.get_program_headers_64();
        for (auto& program_header : program_headers) {
            if (program_header.p_type != ELF::SegmentType::PT_LOAD)
                continue;
            uint64_t target_addr = program_header.p_paddr - this->base_addr;
            if (target_addr < 0 || target_addr >= this->size) {
                throw std::runtime_error(std::format("Target address({:x}) is out of bounds", target_addr));
            }
            if (program_header.p_filesz > this->size - target_addr) {
                throw std::runtime_error(std::format("Write size({:x}) exceeds available memory space", program_header.p_filesz));
            }
            std::memcpy(
                ram + target_addr,
                raw.data() + program_header.p_offset,
                program_header.p_filesz
            );
        }

        return 0;
    }
};

class VirtualAxiSlaves {
public:
    enum axi_read_state {
        STAT_RIDLE,
        STAT_SEND
    };
    enum axi_write_state {
        STAT_WIDLE,
        STAT_ACCEPT_REQ,
        STAT_OUTFIRE,
    };
    explicit VirtualAxiSlaves() {
        read_state = STAT_RIDLE;
        write_state = STAT_WIDLE;
        read_addr = 0;
        write_addr = 0;
        write_data = 0;
        write_mask = 0;
    }

    ~VirtualAxiSlaves() = default;

    uint64_t register_slave(std::shared_ptr<Slave> slave) {
        slaves.emplace_back(std::move(slave));
        return slaves.size() - 1;
    }

    std::shared_ptr<Slave> get_slave(uint64_t id) {
        return slaves[id];
    }

    void sim_step(axiSignal &axi) {
        handle_read(axi);
        handle_write(axi);
    }

private:
    std::vector<std::shared_ptr<Slave>> slaves;
    axi_read_state read_state;
    axi_write_state write_state;
    uint64_t read_addr;
    uint64_t write_addr;
    uint64_t write_data;
    uint8_t write_mask;

    void handle_read(axiSignal &axi) {
        switch (read_state) {
            case STAT_RIDLE:
                axi.arready = true;
                if (axi.arvalid) {
                    read_addr = axi.araddr;
                    read_state = STAT_SEND;
                }
                break;

            case STAT_SEND:
                axi.rvalid = true;
                auto slave = std::ranges::find_if(slaves,
                                    [this](const std::shared_ptr<Slave> &slave) -> bool {
                                        return std::ranges::contains(slave->range, this->read_addr-slave->base_addr);
                                    });
                if (slave != slaves.end()) {
                    axi.rdata = (*slave)->read(read_addr-(*slave)->base_addr);
                    axi.rresp = 0b00; // OKAY
                } else {
                    axi.rdata = 0;
                    axi.rresp = 0b11; // DECERR
                }

                if (axi.rready) {
                    read_state = STAT_RIDLE;
                }
                break;
        }
    }

    void handle_write(axiSignal &axi) {
        switch (write_state) {
            case STAT_WIDLE:
                axi.awready = true;
                if (axi.awvalid) {
                    write_addr = axi.awaddr;
                    write_state = STAT_ACCEPT_REQ;
                }
                break;

            case STAT_ACCEPT_REQ:
                axi.wready = true;
                if (axi.wvalid) {
                    write_data = axi.wdata;
                    write_mask = axi.wstrb;
                    write_state = STAT_OUTFIRE;
                }
                break;

            case STAT_OUTFIRE:
                axi.bvalid = true;
                auto slave = std::ranges::find_if(slaves,
                                    [this](const std::shared_ptr<Slave> &slave) -> bool {
                                        return std::ranges::contains(slave->range, this->write_addr-slave->base_addr);
                                    });
                if (slave != slaves.end()) {
                    (*slave)->write(write_addr-(*slave)->base_addr, write_data, write_mask);
                    axi.bresp = 0b00; // OKAY
                } else {
                    axi.bresp = 0b11; // DECERR
                }

                if (axi.bready) {
                    write_state = STAT_WIDLE;
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

void cycle_verbose(uint64_t cycle, uint64_t pc, uint64_t raw_instr, uint64_t peek) {
    uint8_t raw_code[4] = {0};
    std::cout << std::format("Cycle: 0x{:04x} PC: 0x{:016x} Instr: 0x{:08x} Peek: 0x{:04x} Asm: ",cycle, pc, raw_instr, peek);
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
    try {
        cxxopts::Options options(argv[0], "MarkoRvCore simulator");
        
        options.add_options()
            ("rom-path", "Path to ROM payload", cxxopts::value<std::string>())
            ("ram-path", "Path to RAM payload", cxxopts::value<std::string>())
            ("ram-dump", "Dump the memory after the run is complete", cxxopts::value<std::string>())
            ("max-clock", "Maximum clock cycles to simulate (hex value)", cxxopts::value<std::string>()->default_value(std::to_string(DEFAULT_MAX_CLOCK)))
            ("verbose", "Enable verbose output")
            ("axi-debug", "Enable AXI debug output")
            ("help", "Print usage information")
        ;
        
        auto result = options.parse(argc, argv);
        
        if (result.count("help")) {
            std::cout << options.help() << std::endl;
            return 1;
        }
        
        // Required arguments
        if (!result.count("ram-path")) {
            std::cerr << "Error: --ram-path is required.\n";
            std::cout << options.help() << std::endl;
            return 1;
        }
        
        if (!result.count("rom-path")) {
            std::cerr << "Error: --rom-path is required.\n";
            std::cout << options.help() << std::endl;
            return 1;
        }

        if (result.count("ram-dump")) {
            args.ram_dump = result["ram-dump"].as<std::string>();
        }
        
        // Parse arguments
        args.ram_path = result["ram-path"].as<std::string>();
        args.rom_path = result["rom-path"].as<std::string>();
        
        if (result.count("max-clock")) {
            try {
                args.max_clock = std::stoull(result["max-clock"].as<std::string>(), nullptr, 16);
            } catch (const std::exception&) {
                std::cerr << "Invalid hex value for --max-clock\n";
                return 1;
            }
        }
        
        args.verbose = result.count("verbose") > 0;
        args.axi_debug = result.count("axi-debug") > 0;
        
        // Output parsed results
        std::cout << std::format("ROM payload path: {}\n", args.rom_path);
        std::cout << std::format("RAM payload path: {}\n", args.ram_path);
        
        return 0;
    } catch (const std::exception&) {
        std::cerr << "Error parsing options" << std::endl;
        return 1;
    }
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
    uint64_t rom_id  = slaves.register_slave(std::make_shared<VirtualRAM>(0x10000000, args.rom_path, ROM_SIZE));
    uint64_t ram_id  = slaves.register_slave(std::make_shared<VirtualRAM>(0x80000000, args.ram_path, RAM_SIZE));
    uint64_t uart_id = slaves.register_slave(std::make_shared<VirtualUart>(0x20000000));

    // RV64G only not for C extension.
    if (cs_open(CS_ARCH_RISCV, CS_MODE_RISCV64, &capstone_handle) != CS_ERR_OK) {
        std::cerr << "Capstone engine failed to init.\n";
        return 1;
    }

    // Main loop
    uint64_t clock_cnt = 0;
    axiSignal axi;    
    while (!Verilated::gotFinish() && clock_cnt < args.max_clock) {
        if (clock_cnt < 4) {
            top->reset = 1;
        } else {
            top->reset = 0;
        }

        // Debug out
        if(args.verbose)
            cycle_verbose(clock_cnt, top->io_pc, top->io_instr_now, top->io_peek);
        // Posedge clk
        context->timeInc(1);
        top->clock = 1;
        top->eval();
        init_stimulus(top);
        // Handle axi
        if (!top->reset) {
            std::memset(&axi, 0, sizeof(axiSignal));
            read_axi(top, axi);
            slaves.sim_step(axi);
            if (args.axi_debug)
                axi_debug(axi);
            set_axi(top, axi);
        }

        // Negedge clk
        context->timeInc(1);
        top->clock = 0;
        top->eval();
        clock_cnt++;
    }
    // Clean up
    top->final();

    // Save memory dump
    if(args.ram_dump.has_value()) {
        std::ofstream dump_file(args.ram_dump.value(), std::ios::out | std::ios::binary);
        if(!dump_file) {
            std::cerr << "Can't create dump file.\n";
            return 1;
        }
        auto ram = std::dynamic_pointer_cast<VirtualRAM>(slaves.get_slave(ram_id));
        if (!ram) {
            std::cerr << "Can't dump ram.\n";
            return 1;
        }
        dump_file.write(reinterpret_cast<const char*>(ram->ram), ram->size);
        dump_file.close();
    }
    return 0;
}