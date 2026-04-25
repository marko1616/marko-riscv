#include "manager.hpp"
#include <iostream>
#include <format>
#include <string>
#include <vector>

extern "C" {
    
void update_rob(const svBitVecVal* entry, const uint32_t index) {
    auto decoded_entry = bytes_to_struct<robEntry>(entry);

    if (index < CFG_ROB_SIZE) {
        rob_data[index] = decoded_entry;
    } else {
        throw std::runtime_error("Rob index out of range.");
    }
}

void update_rs(const svBitVecVal* entry, const uint32_t index) {
    auto decoded_entry = bytes_to_struct<ReservationStationEntry>(entry);

    if (index < CFG_RS_SIZE) {
        rs_data[index] = decoded_entry;
    } else {
        throw std::runtime_error("Rs index out of range.");
    }
}

void update_rt(const svOpenArrayHandle handle, const uint32_t rt_index) {
    size_t table_index = 0;
    for (int i = 0; i < 31; i++) {
        svGetBitArrElem1VecVal(&rt_data[rt_index][table_index], handle, i);
        table_index++;
    }
}

void update_rf(const svOpenArrayHandle regs_handle, const svOpenArrayHandle states_handle) {
    for (int i = 0; i < CFG_RF_SIZE; ++i) {
        svBitVecVal data_buffer[2] = {};
        svGetBitArrElem1VecVal(data_buffer, regs_handle, i);

        uint64_t data = 0;
        if constexpr (std::endian::native == std::endian::little) {
            std::memcpy(&data, data_buffer, sizeof(uint64_t));
        } else {
            data = static_cast<uint64_t>(data_buffer[1]) |
                   (static_cast<uint64_t>(data_buffer[0]) << 32);
        }

        uint32_t state_buffer;
        svGetBitArrElem1VecVal(&state_buffer, states_handle, i);

        rf_data[i] = RegisterEntry{data, static_cast<uint8_t>(state_buffer)};
    }
}

void update_pc(uint64_t pc) {
    DpiManager& dpi_manager = DpiManager::get_instance();
    dpi_manager.curr_pc = pc;
}

void update_fetching_instr(bool valid, uint32_t instr) {
    DpiManager& dpi_manager = DpiManager::get_instance();
    if (valid) {
        dpi_manager.fetching_instr = instr;
    } else {
        dpi_manager.fetching_instr = std::nullopt;
    }
}

void fire_issue_event(const svBitVecVal* entry) {
    auto in = bytes_to_struct<IssueEventInput>(entry);
    DpiManager::get_instance().fire_issue(IssueEvent{
        .prd_valid = static_cast<bool>(in.prd_valid),
        .prd       = static_cast<rf_index_t>(in.prd.value)
    });
}

void fire_commit_event(const svBitVecVal* entry) {
    auto in = bytes_to_struct<CommitEventInput>(entry);
    DpiManager::get_instance().fire_commit(CommitEvent{
        .prd_valid = static_cast<bool>(in.prd_valid),
        .prd       = static_cast<rf_index_t>(in.prd.value)
    });
}

void fire_discon_event(const svBitVecVal* entry) {
    auto in = bytes_to_struct<DisconEventInput>(entry);
    DpiManager::get_instance().fire_discon(DisconEvent{
        .discon_type       = static_cast<DisconEventEnum::Type>(in.discon_type.value),
        .prd_valid         = static_cast<bool>(in.prd_valid),
        .prd               = static_cast<rf_index_t>(in.prd.value),
        .prevprd           = static_cast<rf_index_t>(in.prevprd.value),
        .rename_ckpt_index = static_cast<rt_index_t>(in.rename_ckpt_index.value)
    });
}

void fire_retire_event(const svBitVecVal* entry) {
    auto in = bytes_to_struct<RetireEventInput>(entry);
    DpiManager::get_instance().fire_retire(RetireEvent{
        .is_exception = static_cast<bool>(in.is_exception),
        .prd_valid    = static_cast<bool>(in.prd_valid),
        .prd          = static_cast<rf_index_t>(in.prd.value),
        .prevprd      = static_cast<rf_index_t>(in.prevprd.value),
    });
}

} // extern "C"

void DpiManager::print_rob() {
    std::cout << "\n===== Reorder Buffer (ROB) Detailed Status =====\n";

    std::cout << std::format(
        "{:<5} {:<6} {:<8} {:<18} {:<6} {:<8} {:<8} {:<6} {:<8}\n",
        "Idx", "Valid", "Commit", "PC",
        "EXU", "PRD", "PrevPRD", "PRDok", "CkptIdx");
    std::cout << std::string(70, '-') << "\n";

    int valid_count   = 0;
    int commit_count  = 0;
    int discon_count = 0;

    for (size_t i = 0; i < CFG_ROB_SIZE; ++i) {
        const auto& e  = rob_data[i];
        const auto& fc = e.f_ctrl;

        // EXU type string
        std::string exu_str;
        switch (static_cast<uint8_t>(e.exu)) {
            case EXUEnum::ALU:  exu_str = "ALU";  break;
            case EXUEnum::BRU:  exu_str = "BRU";  break;
            case EXUEnum::LSU:  exu_str = "LSU";  break;
            case EXUEnum::MDU:  exu_str = "MDU";  break;
            case EXUEnum::MISC: exu_str = "MISC"; break;
            default:            exu_str = std::format("?{:#x}", static_cast<uint8_t>(e.exu)); break;
        }

        // Accumulate stats
        if (e.valid)    ++valid_count;
        if (e.commited) ++commit_count;
        if (fc.discon) ++discon_count;

        // Main summary line
        std::cout << std::format(
            "{:<5x} {:<6} {:<8} {:<6} {:<8} {:<8} {:<6} {:#x}\n",
            i,
            e.valid    ? "Y" : "N",
            e.commited ? "Y" : "N",
            exu_str,
            e.prd_valid ? std::format("{:#x}", static_cast<uint16_t>(e.prd.value))      : "-",
            e.prd_valid ? std::format("{:#x}", static_cast<uint16_t>(e.prev_prd.value)) : "-",
            e.prd_valid ? "Y" : "N",
            static_cast<uint8_t>(e.rename_ckpt_index.value));

        // flowCtrl detail block (only printed when relevant)
        bool has_fc = fc.discon;
        if (has_fc) {
            std::cout << "  +-- flowCtrl ----------------------------------------------------\n";

            if (fc.discon) {
                std::cout << std::format(
                    "  |  discon      : Y  ->  event_pc = {:#018x}\n"
                    "  |  trap        : Y\n"
                    "  |    cause     : {:#x}\n"
                    "  |    xtval     : {:#018x}\n"
                    "  |  xret        : Y  type={} ({})  xepc={:#018x}\n",
                    fc.event_pc.value,
                    static_cast<int16_t>(fc.cause.value),
                    fc.xtval.value,
                    static_cast<uint8_t>(fc.xret_type),
                    fc.xret_type ? "MRET" : "SRET",
                    fc.xepc.value);
            } else {
                std::cout << "  |  discon      : N\n";
            }

            std::string discon_str;
            switch (static_cast<uint8_t>(fc.discon_type.value)) {
                case 0:  discon_str = "INTERRUPT";       break;  // External Async Interrupt
                case 1:  discon_str = "INSTR_EXCEPTION"; break;  // Sync Exception (syscall/illegal)
                case 2:  discon_str = "INSTR_REDIRECT";  break;  // jalr redirect
                case 3:  discon_str = "BRANCH_MISPRED";  break;  // Branch misprediction flush
                case 4:  discon_str = "INSTR_SYNC";      break;  // fence.i etc.
                case 5:  discon_str = "EXCEP_RETURN";    break;  // xret
                default: discon_str = std::format("?{:#x}", static_cast<uint8_t>(fc.discon_type.value)); break;
            }
            std::cout << std::format("  |  discon_type  : {}\n", discon_str);
            std::cout << "  +----------------------------------------------------------------\n";
        }
    }

    std::cout << std::string(70, '=') << "\n";
    std::cout << std::format(
        "  Summary | valid={:<4} committed={:<4} discon={:<4} | total={}\n",
        valid_count, commit_count, discon_count, CFG_ROB_SIZE);
    std::cout << std::string(70, '=') << "\n\n";
}

void DpiManager::print_rs() {
    std::cout << "\n===== Reservation Station Status =====\n";
    std::cout << std::format("{:<5} {:<8} {:<10} {:<16} {:<10} {:<10} {:<16} {:<16}\n",
                            "Idx", "Valid", "EXU", "PC", "PRS1", "PRS2", "Source1", "Source2");

    for (size_t i = 0; i < CFG_RS_SIZE; ++i) {
        const auto& entry = rs_data[i];
        std::string exu_type;
        switch (entry.exu) {
            case EXUEnum::ALU: exu_type = "ALU"; break;
            case EXUEnum::BRU: exu_type = "BRU"; break;
            case EXUEnum::LSU: exu_type = "LSU"; break;
            case EXUEnum::MDU: exu_type = "MDU"; break;
            case EXUEnum::MISC: exu_type = "MISC"; break;
            default: exu_type = "UNKNOWN"; break;
        }

        std::cout << std::format("{:<5x} {:<8} {:<10} {:#016x} {:<10} {:<10} {:#016x} {:#016x}\n",
                                i,
                                entry.valid ? "Y" : "N",
                                exu_type,
                                entry.params.pc.value,
                                entry.reg_req.prs1_valid ? std::format("{:#x}", entry.reg_req.prs1.value) : "-",
                                entry.reg_req.prs2_valid ? std::format("{:#x}", entry.reg_req.prs2.value) : "-",
                                entry.params.source1.value,
                                entry.params.source2.value);


        switch (entry.exu) {
            case EXUEnum::ALU: {
                const auto& op = entry.opcodes.alu_op;
                std::cout << std::format("    ALU Op: funct3={:#x}, sra_sub={}, op32={}\n",
                                        op.funct3.value,
                                        op.sra_sub ? "Y" : "N",
                                        op.op32 ? "Y" : "N");
                break;
            }
            case EXUEnum::BRU: {
                const auto& op = entry.opcodes.bru_op;
                std::cout << std::format("    BRU Op: funct={:#x}, offset={:#x}, pred_taken={}, pred_pc={:#016x}\n",
                                        op.funct.value,
                                        op.offset.value,
                                        entry.pred_taken ? "Y" : "N",
                                        entry.pred_pc.value);
                break;
            }
            case EXUEnum::LSU: {
                const auto& op = entry.opcodes.lsu_op;
                std::cout << std::format("    LSU Op: funct={:#x}, size={:#x}\n",
                                        op.funct.value,
                                        op.size.value);
                break;
            }
            case EXUEnum::MDU: {
                const auto& op = entry.opcodes.mdu_op;
                std::cout << std::format("    MDU Op: funct3={:#x}, op32={}\n",
                                        op.funct3.value,
                                        op.op32 ? "Y" : "N");
                break;
            }
            case EXUEnum::MISC: {
                const auto& op = entry.opcodes.misc_op;
                std::cout << std::format("    MISC Op: mem_funct={:#x}, sys_funct={:#x}, csr_funct={:#x}\n",
                                        op.misc_mem_funct.value,
                                        op.misc_sys_funct.value,
                                        op.misc_csr_funct.value);
                break;
            }
            default:
                break;
        }
    }
    std::cout << "======================================\n";
}

void DpiManager::print_rt() {
    std::cout << "\n===== Rename Table Status =====\n";
    std::cout << "Checkpoint ID: [Register] = Physical Register ID\n";
    for (size_t checkpoint = 0; checkpoint < CFG_RT_SIZE; ++checkpoint) {
        std::cout << std::format("\nCheckpoint {:#x}:\n", checkpoint);
        for (size_t reg = 1; reg <= 31; reg += 8) {
            std::cout << "  ";
            for (size_t i = 0; i < 8 && (reg + i) <= 31; ++i) {
                std::cout << std::format("x{:<2}={:#04x} ", reg + i, rt_data[checkpoint][reg + i - 1]);
            }
            std::cout << "\n";
        }
    }
    std::cout << "===============================\n";
}

void DpiManager::print_rf() {
    std::cout << "\n===== Register File Status =====\n";
    std::cout << std::format("{:<5} {:<18} {:<10}\n", "Idx", "Data", "State");

    for (size_t i = 0; i < CFG_RF_SIZE; ++i) {
        const auto& entry = rf_data[i];
        std::string state_str;
        switch (entry.state) {
            case PhyRegState::FREE:      state_str = "FREE"; break;
            case PhyRegState::ALLOCATED: state_str = "ALLOCATED"; break;
            case PhyRegState::OCCUPIED:  state_str = "OCCUPIED"; break;
            case PhyRegState::COMMITTED: state_str = "COMMITTED"; break;
            default:                     state_str = "UNKNOWN"; break;
        }
        std::cout << std::format("{:<5} {:#018x} {:<10}\n", i, entry.data, state_str);
    }

    std::cout << "=================================\n";
}
