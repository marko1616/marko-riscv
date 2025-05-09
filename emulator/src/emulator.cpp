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
#define ROM_SIZE (1024LL * 32)
#define RAM_SIZE (1024LL * 1024 * 8)
#define MAX_RESERVED 2

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
    uint8_t awsize;                // Master (3 bits)
    uint8_t awburst;               // Master (2 bits)
    uint8_t awcache;               // Master (4 bits)
    uint8_t awprot;                // Master (3 bits)
    uint16_t awid;                 // Master (axid_len bits)
    uint8_t awlen;                 // Master (8 bits)
    uint8_t awlock;                // Master (1 bit)
    uint8_t awqos;                 // Master (4 bits)
    uint8_t awregion;              // Master (4 bits)

    // Write data signals
    bool wvalid;                   // Master
    bool wready;                   // Slave
    bool wlast;                    // Master
    uint64_t wdata;                // Master
    uint8_t wstrb;                 // Master (data_width / 8, assuming data_width = 32)

    // Write response signals
    bool bvalid;                   // Slave
    bool bready;                   // Master
    uint8_t bresp;                 // Slave (2 bits)
    uint16_t bid;                  // Slave (axid_len bits)

    // Read request signals
    bool arvalid;                  // Master
    bool arready;                  // Slave
    uint64_t araddr;               // Master
    uint8_t arsize;                // Master (3 bits)
    uint8_t arburst;               // Master (2 bits)
    uint8_t arcache;               // Master (4 bits)
    uint16_t arid;                 // Master (axid_len bits)
    uint8_t arlen;                 // Master (8 bits)
    uint8_t arlock;                // Master (1 bit)
    uint8_t arqos;                 // Master (4 bits)
    uint8_t arregion;              // Master (4 bits)
    uint8_t arprot;                // Master (3 bits)

    // Read data signals
    bool rvalid;                   // Slave
    bool rready;                   // Master
    bool rlast;                    // Slave
    uint64_t rdata;                // Slave
    uint8_t rresp;                 // Slave (2 bits)
    uint16_t rid;                  // Slave (axid_len bits)
};

class Slave {
public:
    uint64_t base_addr;
    std::ranges::iota_view<uint64_t, uint64_t> range;
    virtual ~Slave() = default;

    virtual uint64_t read(uint64_t addr, uint8_t size) = 0;
    virtual void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) = 0;

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

    uint64_t read(uint64_t addr, uint8_t size) override {
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

    void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) override {
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
        assert((size & 0x0fff) == 0 && "Virtual RAM size must be 4k aligned.");
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

    uint64_t read(uint64_t addr, uint8_t size) override {
        assert((addr + (1 << size) - 1) < this->size && "Read address out of bounds");

        uint64_t data = 0;
        for (int i = 0; i < (1 << size); i++) {
            data |= static_cast<uint64_t>(ram[addr + i]) << (8 * i);
        }
        return data;
    }

    void write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) override {
        assert((addr + 7) < this->size && "Write address out of bounds");
        for (int i = 0; i < (1 << size); i++) {
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
    enum axi_resp_t {
        RESP_OKAY,
        RESP_EXOKAY,
        RESP_SLVERR,
        RESP_DECERR
    };

    enum axi_burst_t {
        BURST_FIXED,
        BURST_INCR,
        BURST_WRAP,
        BURST_RESERVED
    };

    enum axi_read_state_t {
        STAT_RIDLE,
        STAT_SEND
    };

    enum axi_write_state_t {
        STAT_WIDLE,
        STAT_WRITE_DATA,
        STAT_WRITE_RESP
    };

    struct ReadTransaction {
        axi_read_state_t state;
        uint8_t beat;
        std::optional<uint64_t> held_data;

        uint64_t addr;
        uint8_t size;
        axi_burst_t burst;
        uint16_t id;
        uint8_t len;
        bool lock;
    };

    struct WriteTransaction {
        axi_write_state_t state;
        uint8_t beat;

        uint64_t addr;
        uint8_t size;
        axi_burst_t burst;
        uint16_t id;
        uint8_t len;
        bool lock;

        axi_resp_t resp;
    };

    struct ReservedItem {
        bool valid = false;
        uint64_t addr = 0;
        uint8_t size = 0;
        bool isConflict(uint64_t addr, uint8_t size) {
            if (!valid) return false;
            uint64_t this_bytes = 1ULL << this->size;
            uint64_t target_bytes = 1ULL << size;

            uint64_t this_start = this->addr;
            uint64_t this_end = this->addr + this_bytes;

            uint64_t target_start = addr;
            uint64_t target_end = addr + target_bytes;

            return (this_end > target_start) && (this_start < target_end);
        }
    };

    explicit VirtualAxiSlaves() {
        empty_read_transaction();
        empty_write_transaction();
        reserved_items.resize(MAX_RESERVED);
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
    ReadTransaction current_read;
    WriteTransaction current_write;
    std::vector<ReservedItem> reserved_items;

    void empty_read_transaction() {
        current_read.state = STAT_RIDLE;
        current_read.beat = 0;
        current_read.held_data = std::nullopt;
        current_read.addr = 0;
        current_read.size = 0;
        current_read.burst = BURST_RESERVED;
        current_read.id = 0;
        current_read.len = 0;
        current_read.lock = false;
    }

    void empty_write_transaction() {
        current_write.state = STAT_WIDLE;
        current_write.beat = 0;
        current_write.addr = 0;
        current_write.size = 0;
        current_write.burst = BURST_RESERVED;
        current_write.id = 0;
        current_write.len = 0;
        current_write.lock = false;
        current_write.resp = RESP_OKAY;
    }

    uint64_t calculate_next_addr(uint64_t base_addr, uint8_t size, axi_burst_t burst, uint8_t beat) {
        const uint64_t bytes_per_beat = 1 << size;
        switch (burst) {
            case BURST_FIXED:
                return base_addr;
            case BURST_INCR:
                return base_addr + beat*bytes_per_beat;
            case BURST_WRAP: {
                const uint64_t num_beats = current_read.len + 1;
                const uint64_t wrap_boundary = num_beats * bytes_per_beat;
                const uint64_t aligned_base = base_addr & ~(wrap_boundary - 1);
                const uint64_t offset = beat * bytes_per_beat;
                return aligned_base + (offset % wrap_boundary);
            }
            default:
                std::cerr << "Not valid burst mode";
                return base_addr;
        }
    }

    void handle_read(axiSignal &axi) {
        switch (current_read.state) {
            case STAT_RIDLE:
                axi.arready = true;
                if (axi.arvalid) {
                    current_read.addr = axi.araddr;
                    current_read.size = axi.arsize;
                    current_read.burst = static_cast<axi_burst_t>(axi.arburst);
                    current_read.id = axi.arid;
                    current_read.len = axi.arlen;
                    current_read.lock = axi.arlock;
                    current_read.state = STAT_SEND;
                    current_read.beat = 0;
                }
                break;
            case STAT_SEND:
                axi.rvalid = true;
                axi.rid = current_read.id;
                uint64_t current_addr = calculate_next_addr(
                    current_read.addr, 
                    current_read.size,
                    current_read.burst,
                    current_read.beat
                );
                auto slave = std::ranges::find_if(slaves,
                                    [current_addr](const std::shared_ptr<Slave> &s) -> bool {
                                        return std::ranges::contains(s->range, current_addr-s->base_addr);
                                    });
                // Found and not cross 4k boundary.
                bool addr_valid = (slave != slaves.end()) && ((current_addr & 0xfffff000) == (current_read.addr & 0xfffff000));
                if (addr_valid) {
                    if(current_read.held_data) {
                        // Prevent multiple read side effects
                        axi.rdata = *current_read.held_data;
                        axi.rresp = current_read.lock ? RESP_EXOKAY : RESP_OKAY;
                    } else {
                        uint64_t data = (*slave)->read(current_addr - (*slave)->base_addr, current_read.size);
                        current_read.held_data = data;
                        axi.rdata = data;
                        axi.rresp = current_read.lock ? RESP_EXOKAY : RESP_OKAY;
                    }
                    axi.rlast = (current_read.beat == current_read.len);
                } else {
                    axi.rdata = 0;
                    axi.rlast = true;
                    axi.rresp = RESP_DECERR;
                }

                if (axi.rready) {
                    // Load reserved
                    current_read.held_data = std::nullopt;
                    if (current_read.lock) {
                        bool reserved = false;
                        for (auto &item : reserved_items) {
                            if (!item.valid) {
                                item.valid = true;
                                item.addr = current_read.addr;
                                item.size = current_read.size;
                                reserved = true;
                                break;
                            }
                        }
                        if (!reserved) {
                            static size_t replace_ptr = 0;
                            reserved_items[replace_ptr] = {true, current_read.addr, current_read.size};
                            replace_ptr = (replace_ptr + 1) % MAX_RESERVED;
                        }
                    }
                    if(axi.rresp == RESP_DECERR | current_read.beat == current_read.len) {
                        empty_read_transaction();
                        break;
                    }
                    current_read.beat++;
                }
                break;
        }
    }

    void handle_write(axiSignal &axi) {
        switch (current_write.state) {
            case STAT_WIDLE:
                axi.awready = true;
                if (axi.awvalid) {
                    current_write.addr = axi.awaddr;
                    current_write.size = axi.awsize;
                    current_write.burst = static_cast<axi_burst_t>(axi.awburst);
                    current_write.id = axi.awid;
                    current_write.len = axi.awlen;
                    current_write.lock = axi.awlock;
                    current_write.beat = 0;
                    current_write.state = STAT_WRITE_DATA;
                }
                break;
            case STAT_WRITE_DATA:
                axi.wready = true;
                if (axi.wvalid) {
                    uint64_t current_addr = calculate_next_addr(
                        current_write.addr, 
                        current_write.size,
                        current_write.burst,
                        current_write.beat
                    );
                    auto slave = std::ranges::find_if(slaves,
                        [current_addr](const auto& s) {
                            return std::ranges::contains(s->range, current_addr-s->base_addr);
                        });
                    bool addr_valid = (slave != slaves.end()) && ((current_addr & 0xfffff000) == (current_write.addr & 0xfffff000));
                    if (addr_valid) {
                        bool reserved_hit = false;
                        for (auto &item : reserved_items) {
                            if (item.valid && item.addr == current_addr) {
                                reserved_hit = true;
                            }
                            if (item.valid && item.isConflict(current_addr, current_write.size)) {
                                item.valid = false;
                            }
                        }
                        if(!current_write.lock || (current_write.lock && reserved_hit)) {
                            (*slave)->write(current_addr-(*slave)->base_addr,axi.wdata,current_write.size,axi.wstrb);
                        }
                        current_write.resp = current_write.lock && reserved_hit ? RESP_EXOKAY : RESP_OKAY;
                    } else {
                        current_write.resp = RESP_DECERR;
                        current_write.state = STAT_WRITE_RESP;
                    }
                    if (axi.wlast) {
                        current_write.state = STAT_WRITE_RESP;
                    }
                    current_write.beat++;
                }
                break;
            case STAT_WRITE_RESP:
                axi.bvalid = true;
                axi.bresp = current_write.resp;
                axi.bid = current_write.id;
                if (axi.bready) {
                    empty_write_transaction();
                }
                break;
        }
    }
};

csh capstone_handle;

void read_axi(const std::unique_ptr<VMarkoRvCore> &top, axiSignal &axi) {
    // Write request signals (Master->Slave)
    axi.awvalid = top->io_axi_aw_valid;
    axi.awaddr  = top->io_axi_aw_bits_addr;
    axi.awsize  = top->io_axi_aw_bits_size;
    axi.awburst = top->io_axi_aw_bits_burst;
    axi.awcache = top->io_axi_aw_bits_cache;
    axi.awprot  = top->io_axi_aw_bits_prot;
    axi.awid    = top->io_axi_aw_bits_id;
    axi.awlen   = top->io_axi_aw_bits_len;
    axi.awlock  = top->io_axi_aw_bits_lock;
    axi.awqos   = top->io_axi_aw_bits_qos;
    axi.awregion= top->io_axi_aw_bits_region;

    // Write data signals (Master->Slave)
    axi.wvalid  = top->io_axi_w_valid;
    axi.wlast   = top->io_axi_w_bits_last;
    axi.wdata   = top->io_axi_w_bits_data;
    axi.wstrb   = top->io_axi_w_bits_strb;

    // Write response signals (Slave->Master)
    axi.bready  = top->io_axi_b_ready;

    // Read request signals (Master->Slave)
    axi.arvalid = top->io_axi_ar_valid;
    axi.araddr  = top->io_axi_ar_bits_addr;
    axi.arsize  = top->io_axi_ar_bits_size;
    axi.arburst = top->io_axi_ar_bits_burst;
    axi.arcache = top->io_axi_ar_bits_cache;
    axi.arid    = top->io_axi_ar_bits_id;
    axi.arlen   = top->io_axi_ar_bits_len;
    axi.arlock  = top->io_axi_ar_bits_lock;
    axi.arqos   = top->io_axi_ar_bits_qos;
    axi.arregion= top->io_axi_ar_bits_region;
    axi.arprot  = top->io_axi_ar_bits_prot;

    // Read data signals (Slave->Master)
    axi.rready  = top->io_axi_r_ready;
}


void set_axi(const std::unique_ptr<VMarkoRvCore> &top, const axiSignal &axi) {
    // Write response
    top->io_axi_b_valid      = axi.bvalid;
    top->io_axi_b_bits_resp  = axi.bresp;
    top->io_axi_b_bits_id    = axi.bid;
    
    // Read response
    top->io_axi_r_valid      = axi.rvalid;
    top->io_axi_r_bits_data  = axi.rdata;
    top->io_axi_r_bits_resp  = axi.rresp;
    top->io_axi_r_bits_id    = axi.rid;
    top->io_axi_r_bits_last  = axi.rlast;
    
    // Flow control
    top->io_axi_aw_ready     = axi.awready;
    top->io_axi_w_ready      = axi.wready;
    top->io_axi_ar_ready     = axi.arready;
}

void clear_axi(const std::unique_ptr<VMarkoRvCore> &top) {
    // Write response
    top->io_axi_b_valid      = false;
    top->io_axi_b_bits_resp  = 0;
    top->io_axi_b_bits_id    = 0;

    // Read response
    top->io_axi_r_valid      = false;
    top->io_axi_r_bits_data  = 0;
    top->io_axi_r_bits_resp  = 0;
    top->io_axi_r_bits_id    = 0;
    top->io_axi_r_bits_last  = false;

    // Flow control
    top->io_axi_aw_ready     = false;
    top->io_axi_w_ready      = false;
    top->io_axi_ar_ready     = false;
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

void set_time(const std::unique_ptr<VMarkoRvCore> &top) {
    top->io_time = std::time(nullptr);
}

void cycle_verbose(uint64_t cycle, uint64_t pc, uint64_t raw_instr) {
    uint8_t raw_code[4] = {0};
    std::cout << std::format("Cycle: 0x{:04x} PC: 0x{:016x} Instr: 0x{:08x} Asm: ",cycle, pc, raw_instr);
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
    set_time(top);
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
            cycle_verbose(clock_cnt, top->io_pc, top->io_instrNow);
        // Posedge clk
        context->timeInc(1);
        top->clock = 1;
        top->eval();
        // Handle axi
        init_stimulus(top);
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