#include <capstone/capstone.h>
#include <verilated_vcd_c.h>

#include "VMarkoRvCore.h"
#include "config.hpp"
#include "elf.hpp"
#include "arg_parser.hpp"
#include "axi_signal.hpp"
#include "axi_bus.hpp"
#include "slaves/slave.hpp"
#include "slaves/clint.hpp"
#include "slaves/plic.hpp"
#include "slaves/virtual_ram.hpp"
#include "slaves/virtual_uart.hpp"
#include "input_manager.hpp"
#include "dpi/manager.hpp"

csh capstone_handle;

inline cs_mode operator|(cs_mode a, cs_mode b) {
    return static_cast<cs_mode>(static_cast<int>(a) | static_cast<int>(b));
}

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

std::string cycle_verbose(uint64_t cycle, uint64_t pc, std::optional<uint32_t> raw_instr) {
    std::string result = std::format(
        "Cycle: 0x{:04x} PC: 0x{:016x} Instr: 0x{:08x} Asm: ",
        cycle, pc, raw_instr.value_or(0)
    );

    if (!raw_instr) {
        result += "null\n";
        return "";
    }

    uint8_t raw_code[4] = {
        static_cast<uint8_t>(raw_instr.value() >> 0),
        static_cast<uint8_t>(raw_instr.value() >> 8),
        static_cast<uint8_t>(raw_instr.value() >> 16),
        static_cast<uint8_t>(raw_instr.value() >> 24),
    };

    const size_t size = ((raw_instr.value() & 0x3) == 0x3) ? 4 : 2;

    cs_insn *insn = nullptr;
    size_t count = cs_disasm(capstone_handle, raw_code, size, pc, 1, &insn);

    if (count > 0) {
        result += insn[0].mnemonic;
        if (insn[0].op_str[0] != '\0') {
            result += " ";
            result += insn[0].op_str;
        }
        result += "\n";
        cs_free(insn, count);
    } else {
        result += "invalid\n";
    }

    return result;
}

void init_stimulus(const std::unique_ptr<VMarkoRvCore> &top) {
    clear_axi(top);
    top->io_dcacheCleanAllReq = false;
}

struct CycleSnapshot {
    std::string cycle_info;
};

class SimulationManager {
public:
    SimulationManager(parsedArgs& args) {
        context = std::make_unique<VerilatedContext>();
        top = std::make_unique<VMarkoRvCore>();
        input_manager = std::make_unique<InputManager>();

        if (args.vcd_dump.has_value()) {
            vcd_context = std::make_unique<VerilatedVcdC>();
            Verilated::traceEverOn(true);
            top->trace(vcd_context.get(), 0);
            vcd_context->open(args.vcd_dump.value().c_str());
        }

        top->clock = 0;
        top->reset = 0;

        clint_id = slaves.register_slave(std::make_shared<VirtualCLINT>(0x02000000, args.timer_scale, args.stable_clock));
        plic_id  = slaves.register_slave(std::make_shared<VirtualPLIC>(0x0C000000));
        rom_id   = slaves.register_slave(std::make_shared<VirtualRAM>(0x01000000, CFG_ROM_SIZE));
        ram_id   = slaves.register_slave(std::make_shared<VirtualRAM>(0x80000000, CFG_RAM_SIZE));
        uart_id  = slaves.register_slave(std::make_shared<VirtualUart>(0x10000000, 0x0a));

        uart_ = std::dynamic_pointer_cast<VirtualUart>(slaves.get_slave(uart_id));
        uart_->set_interrupt_controller(
            std::dynamic_pointer_cast<VirtualPLIC>(slaves.get_slave(plic_id)));

        auto rom = std::dynamic_pointer_cast<VirtualRAM>(slaves.get_slave(rom_id));
        auto ram = std::dynamic_pointer_cast<VirtualRAM>(slaves.get_slave(ram_id));
        if (!rom || !ram) {
            throw std::runtime_error("Failed to get VirtualRAM instance");
        }

        load_payloads(rom, args.rom_payloads, "ROM");
        load_payloads(ram, args.ram_payloads, "RAM");

        if (cs_open(CS_ARCH_RISCV, CS_MODE_RISCV64 | CS_MODE_RISCV_C, &capstone_handle) != CS_ERR_OK) {
            throw std::runtime_error("Capstone engine failed to init.");
        }
    }

    ~SimulationManager() {
        top->final();
    }

    void run_simulation(parsedArgs args) {
        uint64_t clock_cnt = 0;
        axiSignal axi;
        DpiManager& dpi = DpiManager::get_instance();
        register_event_breakpoints(args, dpi);

        uint64_t cleanup_dcache_at = args.max_clock - args.cleanup_dcache_addrs.size() * DCACHE_CLEANUP_TIME;

        while (!Verilated::gotFinish() && clock_cnt < args.max_clock) {
            if (clock_cnt < 4) {
                top->reset = 1;
            } else {
                top->reset = 0;
            }

            if (auto_pause_pending_.exchange(false, std::memory_order_relaxed)) {
                std::cerr << std::format(
                    "[AUTO-PAUSE] Event breakpoint hit at cycle=0x{:x} pc=0x{:x}\r\n",
                    clock_cnt, dpi.curr_pc);
                input_manager->force_pause();
            }

            auto enqueue = [this](uint8_t ch) { uart_->enqueue_char(ch); };
            auto action = input_manager->poll(enqueue);
            if (action == InputManager::InputAction::Exit) {
                goto simulation_end;
            }

            while (input_manager->is_paused()) {
                print_all_debug(clock_cnt, dpi, axi);
                std::string cycle_info = cycle_verbose(clock_cnt, dpi.curr_pc, dpi.fetching_instr);

                std::cerr << "[PAUSED cycle=0x" << std::hex << clock_cnt << std::dec
                          << "] s/Enter=step  c=continue  q=quit  Ctrl+A h=help\r\n";

                auto action = input_manager->wait_paused(enqueue);
                switch (action) {
                    case InputManager::InputAction::PauseStep:
                        replay_buffer_.push_back({cycle_info});
                        execute_one_cycle(clock_cnt, args, dpi, axi);
                        clock_cnt++;
                        continue;
                    case InputManager::InputAction::PauseResume:
                        break;
                    case InputManager::InputAction::PauseQuit:
                        std::cerr << "\r\nQuit from debug pause.\r\n";
                        goto simulation_end;
                    case InputManager::InputAction::Exit:
                        goto simulation_end;
                }
                break;
            }

            {
                bool verbose = args.verbose || input_manager->is_force_verbose();
                std::string cycle_info = cycle_verbose(clock_cnt, dpi.curr_pc, dpi.fetching_instr);
                replay_buffer_.push_back({cycle_info});
                if (replay_buffer_.size() > REPLAY_BUFFER_SIZE) {
                    replay_buffer_.pop_front();
                }
                if (verbose) {
                    auto pc = dpi.curr_pc;
                    auto raw_instr = dpi.fetching_instr;
                    std::cout << cycle_info;
                }
                if (args.rob_debug) dpi.print_rob();
                if (args.rs_debug)  dpi.print_rs();
                if (args.rt_debug)  dpi.print_rt();
                if (args.rf_debug)  dpi.print_rf();
            }

            execute_one_cycle(clock_cnt, args, dpi, axi);
            clock_cnt++;
        }

    simulation_end:
        if (args.vcd_dump.has_value()) {
            vcd_context->close();
        }
        if (args.ram_dump.has_value()) {
            save_ram_dump(args.ram_dump.value());
        }
    }

private:
    std::unique_ptr<VerilatedContext> context;
    std::unique_ptr<VerilatedVcdC> vcd_context;
    std::unique_ptr<VMarkoRvCore> top;
    std::unique_ptr<InputManager> input_manager;
    VirtualAxiSlaves slaves;
    std::shared_ptr<VirtualUart> uart_;
    uint64_t clint_id;
    uint64_t plic_id;
    uint64_t rom_id;
    uint64_t ram_id;
    uint64_t uart_id;

    std::atomic<bool> auto_pause_pending_{false}; // Possible accessed by DPI callbacks in other threads
    std::deque<CycleSnapshot> replay_buffer_;

    // Execute one posedge+negedge simulation cycle (does NOT increment clock_cnt)
    void execute_one_cycle(uint64_t clock_cnt, const parsedArgs& args,
                           DpiManager& dpi, axiSignal& axi) {
        context->timeInc(1);
        top->clock = 1;
        top->eval();

        if (args.vcd_dump.has_value() &&
            static_cast<int64_t>(args.max_clock) - clock_cnt <= VCD_DUMP_MAX) {
            vcd_context->dump(
                (static_cast<int64_t>(clock_cnt) - args.max_clock + VCD_DUMP_MAX) * 2);
        }

        init_stimulus(top);

        if (clock_cnt > args.max_clock - args.cleanup_dcache_addrs.size() * DCACHE_CLEANUP_TIME) {
            top->io_dcacheCleanAllReq = true;
        }

        if (!top->reset) {
            std::memset(&axi, 0, sizeof(axiSignal));
            read_axi(top, axi);
            slaves.sim_step(top, axi);
            if (args.axi_debug) axi_debug(axi);
            set_axi(top, axi);
            top->io_time = slaves.get_slave(clint_id)->read(MTIME_OFFSET, 8);
        }

        context->timeInc(1);
        top->clock = 0;
        top->eval();

        if (args.vcd_dump.has_value() &&
            static_cast<int64_t>(args.max_clock) - clock_cnt <= VCD_DUMP_MAX) {
            vcd_context->dump(
                (static_cast<int64_t>(clock_cnt) - args.max_clock + VCD_DUMP_MAX) * 2 + 1);
        }
    }

    // Print all debug information (used in pause mode)
    void print_all_debug(uint64_t clock_cnt, DpiManager& dpi, const axiSignal& axi) {
        std::cout << "\r\n===================================\r\n";
        for (const auto& snapshot : replay_buffer_) {
            std::cout << snapshot.cycle_info;
        }
        replay_buffer_.clear();
        dpi.print_rob();
        dpi.print_rs();
        dpi.print_rt();
        dpi.print_rf();
        axi_debug(axi);
        std::cout << "===================================\r\n";
    }

    void save_ram_dump(const std::string& dump_path) {
        std::ofstream dump_file(dump_path, std::ios::out | std::ios::binary);
        if (!dump_file) {
            std::cerr << "Can't create dump file.\n";
            return;
        }

        auto ram = std::dynamic_pointer_cast<VirtualRAM>(slaves.get_slave(ram_id));
        if (!ram) {
            std::cerr << "Can't dump ram.\n";
            return;
        }

        dump_file.write(reinterpret_cast<const char*>(ram->ram), ram->size);
        dump_file.close();
    }

    static bool filter_matches(const std::map<std::string, uint64_t>& filters,
                                const std::string& key, uint64_t actual_value)
    {
        auto it = filters.find(key);
        if (it == filters.end()) return true;
        return it->second == actual_value;
    }

    static void load_payloads(const std::shared_ptr<VirtualRAM>& mem,
                              const std::vector<PayloadSpec>& payloads,
                              const std::string& mem_name)
    {
        for (const auto& payload : payloads) {
            if (payload.type == PayloadType::Elf) {
                std::cout << std::format("Loading {} ELF: {}\n", mem_name, payload.file_path);
                mem->load_elf(payload.file_path);
            } else {
                std::cout << std::format("Loading {} BIN: {} @ {:#x}\n",
                                         mem_name,
                                         payload.file_path,
                                         payload.addr.value());
                mem->load_bin(payload.file_path, payload.addr.value());
            }
        }
    }

    void register_event_breakpoints(const parsedArgs& args, DpiManager& dpi)
    {
        for (const auto& bp : args.event_breakpoints) {

            if (bp.event_type == "issue") {
                dpi.on_issue([this, bp](const IssueEvent& e) {
                    if (filter_matches(bp.filters, "prd_valid", e.prd_valid) &&
                        filter_matches(bp.filters, "prd",       e.prd))
                    {
                        auto_pause_pending_.store(true, std::memory_order_relaxed);
                    }
                });

            } else if (bp.event_type == "commit") {
                dpi.on_commit([this, bp](const CommitEvent& e) {
                    if (filter_matches(bp.filters, "prd_valid", e.prd_valid) &&
                        filter_matches(bp.filters, "prd",       e.prd))
                    {
                        auto_pause_pending_.store(true, std::memory_order_relaxed);
                    }
                });

            } else if (bp.event_type == "discon") {
                dpi.on_discon([this, bp](const DisconEvent& e) {
                    if (filter_matches(bp.filters, "discon_type",       e.discon_type) &&
                        filter_matches(bp.filters, "prd_valid",         e.prd_valid) &&
                        filter_matches(bp.filters, "prd",               e.prd) &&
                        filter_matches(bp.filters, "prevprd",           e.prevprd) &&
                        filter_matches(bp.filters, "rename_ckpt_index", e.rename_ckpt_index))
                    {
                        auto_pause_pending_.store(true, std::memory_order_relaxed);
                    }
                });

            } else if (bp.event_type == "retire") {
                dpi.on_retire([this, bp](const RetireEvent& e) {
                    if (filter_matches(bp.filters, "is_exception", e.is_exception) &&
                        filter_matches(bp.filters, "prd_valid",    e.prd_valid) &&
                        filter_matches(bp.filters, "prd",          e.prd) &&
                        filter_matches(bp.filters, "prevprd",      e.prevprd))
                    {
                        auto_pause_pending_.store(true, std::memory_order_relaxed);
                    }
                });
            }
        }
    }
};

int main(int argc, char **argv, char **env) {
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
