#include <capstone/capstone.h>

#include "VMarkoRvCore.h"
#include "config.hpp"
#include "elf.hpp"
#include "arg_parser.hpp"
#include "axi_signal.hpp"
#include "axi_bus.hpp"
#include "slaves/slave.hpp"
#include "slaves/clint.hpp"
#include "slaves/virtual_ram.hpp"
#include "slaves/virtual_uart.hpp"
#include "dpi/manager.hpp"

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

void init_stimulus(const std::unique_ptr<VMarkoRvCore> &top) {
    clear_axi(top);
    set_time(top);
}

class SimulationManager {
public:
    SimulationManager(parsedArgs& args) {
        context = std::make_unique<VerilatedContext>();
        top = std::make_unique<VMarkoRvCore>();
        top->clock = 0;
        top->reset = 0;

        slaves.register_slave(std::make_shared<VirtualCLINT>(0x02000000));
        slaves.register_slave(std::make_shared<VirtualRAM>  (0x00001000, args.rom_path, CFG_ROM_SIZE));
        slaves.register_slave(std::make_shared<VirtualRAM>  (0x80000000, args.ram_path, CFG_RAM_SIZE));
        slaves.register_slave(std::make_shared<VirtualUart> (0x10000000));

        if (cs_open(CS_ARCH_RISCV, CS_MODE_RISCV64, &capstone_handle) != CS_ERR_OK) {
            throw std::runtime_error("Capstone engine failed to init.");
        }
    }

    ~SimulationManager() {
        top->final(); // Ensure top is finalized before destruction
    }

    void run_simulation(parsedArgs args) {
        uint64_t clock_cnt = 0;
        axiSignal axi;
        DpiManager& dpi = DpiManager::getInstance();

        while (!Verilated::gotFinish() && clock_cnt < args.max_clock) {
            // Reset handling
            if (clock_cnt < 4) {
                top->reset = 1;
            } else {
                top->reset = 0;
            }

            // Debug output
            if (args.verbose)
                cycle_verbose(clock_cnt, top->io_pc, top->io_instrNow);
            if (args.rob_debug)
                dpi.print_rob();
            if (args.rs_debug)
                dpi.print_rs();
            if (args.rt_debug)
                dpi.print_rt();
            if (args.rf_debug)
                dpi.print_rf();

            // Posedge and Negedge clock simulation
            context->timeInc(1);
            top->clock = 1;
            top->eval();
            init_stimulus(top);

            if (!top->reset) {
                std::memset(&axi, 0, sizeof(axiSignal));
                read_axi(top, axi);
                slaves.sim_step(top, axi);
                if (args.axi_debug)
                    axi_debug(axi);
                set_axi(top, axi);
            }

            context->timeInc(1);
            top->clock = 0;
            top->eval();

            clock_cnt++;
        }

        if (args.ram_dump.has_value()) {
            save_ram_dump(args.ram_dump.value());
        }
    }

private:
    std::unique_ptr<VerilatedContext> context;
    std::unique_ptr<VMarkoRvCore> top;
    VirtualAxiSlaves slaves;

    void save_ram_dump(const std::string& dump_path) {
        std::ofstream dump_file(dump_path, std::ios::out | std::ios::binary);
        if (!dump_file) {
            std::cerr << "Can't create dump file.\n";
            return;
        }

        auto ram = std::dynamic_pointer_cast<VirtualRAM>(slaves.get_slave(2));
        if (!ram) {
            std::cerr << "Can't dump ram.\n";
            return;
        }

        dump_file.write(reinterpret_cast<const char*>(ram->ram), ram->size);
        dump_file.close();
    }
};

int main(int argc, char **argv, char **env)
{
    Verilated::commandArgs(argc, argv);

    parsedArgs args;
    if (parse_args(argc, argv, args) != 0)
        return 1;

    try {
        SimulationManager sim_manager(args);
        sim_manager.run_simulation(args);
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << "\n";
        return 1;
    }

    return 0;
}
