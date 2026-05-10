#include "manager.hpp"

template<typename D, typename S> static inline void dbg_read(D &dst, S src)
{
    dst = static_cast<D>(src);
}

#define DBG_READ(dst, name) dbg_read((dst), (top->name))

#define DBG_FOR_EACH_5(M) M(0) M(1) M(2) M(3) M(4)

#define DBG_FOR_EACH_16(M) \
    M(0)                   \
    M(1) M(2) M(3) M(4) M(5) M(6) M(7) M(8) M(9) M(10) M(11) M(12) M(13) M(14) M(15)

#define DBG_FOR_EACH_64(M)                                                                                          \
    M(0)                                                                                                            \
    M(1)                                                                                                            \
    M(2)                                                                                                            \
    M(3)                                                                                                            \
    M(4)                                                                                                            \
    M(5)                                                                                                            \
    M(6)                                                                                                            \
    M(7) M(8) M(9) M(10) M(11) M(12) M(13) M(14) M(15) M(16) M(17) M(18) M(19) M(20) M(21) M(22) M(23) M(24) M(25)  \
        M(26) M(27) M(28) M(29) M(30) M(31) M(32) M(33) M(34) M(35) M(36) M(37) M(38) M(39) M(40) M(41) M(42) M(43) \
            M(44) M(45) M(46) M(47) M(48) M(49) M(50) M(51) M(52) M(53) M(54) M(55) M(56) M(57) M(58) M(59) M(60)   \
                M(61) M(62) M(63)

#define DBG_FOR_EACH_RT_COL(M, r)                                                                                     \
    M(r, 0)                                                                                                           \
    M(r, 1)                                                                                                           \
    M(r, 2)                                                                                                           \
    M(r, 3)                                                                                                           \
    M(r, 4)                                                                                                           \
    M(r, 5)                                                                                                           \
    M(r, 6)                                                                                                           \
    M(r, 7) M(r, 8) M(r, 9) M(r, 10) M(r, 11) M(r, 12) M(r, 13) M(r, 14) M(r, 15) M(r, 16) M(r, 17) M(r, 18) M(r, 19) \
        M(r, 20) M(r, 21) M(r, 22) M(r, 23) M(r, 24) M(r, 25) M(r, 26) M(r, 27) M(r, 28) M(r, 29) M(r, 30)

#define DBG_SAMPLE_RF(i)                                 \
    do {                                                 \
        DBG_READ(rf_data[i].state, dbgIo_rf_states_##i); \
        DBG_READ(rf_data[i].data, dbgIo_rf_regs_##i);    \
    } while (false);

#define DBG_SAMPLE_RT_CELL(r, c) DBG_READ(rt_data[r][c], dbgIo_rt_table_##r##_##c);

#define DBG_SAMPLE_RT_ROW(r)                       \
    do {                                           \
        DBG_FOR_EACH_RT_COL(DBG_SAMPLE_RT_CELL, r) \
    } while (false);

#define DBG_SAMPLE_ROB(i)                                                        \
    do {                                                                         \
        auto &e = rob_data[i];                                                   \
        DBG_READ(e.valid, dbgIo_rob_buffer_##i##_valid);                         \
        DBG_READ(e.exu, dbgIo_rob_buffer_##i##_exu);                             \
        DBG_READ(e.prd_valid, dbgIo_rob_buffer_##i##_prdValid);                  \
        DBG_READ(e.prd, dbgIo_rob_buffer_##i##_prd);                             \
        DBG_READ(e.prev_prd, dbgIo_rob_buffer_##i##_prevprd);                    \
        DBG_READ(e.f_ctrl.discon, dbgIo_rob_buffer_##i##_fCtrl_discon);          \
        DBG_READ(e.f_ctrl.discon_type, dbgIo_rob_buffer_##i##_fCtrl_disconType); \
        DBG_READ(e.f_ctrl.xret_type, dbgIo_rob_buffer_##i##_fCtrl_xretType);     \
        DBG_READ(e.commited, dbgIo_rob_buffer_##i##_commited);                   \
        DBG_READ(e.rename_ckpt_index, dbgIo_rob_buffer_##i##_renameCkptIndex);   \
        DBG_READ(e.f_ctrl.cause, dbgIo_rob_buffer_##i##_fCtrl_cause);            \
        DBG_READ(e.f_ctrl.xtval, dbgIo_rob_buffer_##i##_fCtrl_xtval);            \
        DBG_READ(e.f_ctrl.xepc, dbgIo_rob_buffer_##i##_fCtrl_xepc);              \
        DBG_READ(e.f_ctrl.event_pc, dbgIo_rob_buffer_##i##_fCtrl_eventPc);       \
    } while (false);

#define DBG_SAMPLE_RS(i)                                                                                     \
    do {                                                                                                     \
        auto &e = rs_data[i];                                                                                \
        DBG_READ(e.valid, dbgIo_rs_buffer_##i##_valid);                                                      \
        DBG_READ(e.exu, dbgIo_rs_buffer_##i##_exu);                                                          \
        DBG_READ(e.opcodes.alu_op.op32, dbgIo_rs_buffer_##i##_exuOpcode_aluOpcode_op32);                     \
        DBG_READ(e.opcodes.alu_op.sra_sub, dbgIo_rs_buffer_##i##_exuOpcode_aluOpcode_sraSub);                \
        DBG_READ(e.opcodes.alu_op.funct3, dbgIo_rs_buffer_##i##_exuOpcode_aluOpcode_funct3);                 \
        DBG_READ(e.opcodes.lsu_op.funct, dbgIo_rs_buffer_##i##_exuOpcode_lsuOpcode_funct);                   \
        DBG_READ(e.opcodes.lsu_op.size, dbgIo_rs_buffer_##i##_exuOpcode_lsuOpcode_size);                     \
        DBG_READ(e.opcodes.misc_op.misc_csr_funct, dbgIo_rs_buffer_##i##_exuOpcode_miscOpcode_miscCsrFunct); \
        DBG_READ(e.opcodes.misc_op.misc_sys_funct, dbgIo_rs_buffer_##i##_exuOpcode_miscOpcode_miscSysFunct); \
        DBG_READ(e.opcodes.misc_op.misc_mem_funct, dbgIo_rs_buffer_##i##_exuOpcode_miscOpcode_miscMemFunct); \
        DBG_READ(e.opcodes.bru_op.funct, dbgIo_rs_buffer_##i##_exuOpcode_branchOpcode_funct);                \
        DBG_READ(e.opcodes.bru_op.offset, dbgIo_rs_buffer_##i##_exuOpcode_branchOpcode_offset);              \
        DBG_READ(e.opcodes.mdu_op.op32, dbgIo_rs_buffer_##i##_exuOpcode_mduOpcode_op32);                     \
        DBG_READ(e.opcodes.mdu_op.funct3, dbgIo_rs_buffer_##i##_exuOpcode_mduOpcode_funct3);                 \
        DBG_READ(e.opcodes.misc_op.raw_instr, dbgIo_rs_buffer_##i##_exuOpcode_miscOpcode_rawInstr);          \
        DBG_READ(e.pred_taken, dbgIo_rs_buffer_##i##_predTaken);                                             \
        DBG_READ(e.pred_pc, dbgIo_rs_buffer_##i##_predPc);                                                   \
        DBG_READ(e.params.rob_index, dbgIo_rs_buffer_##i##_params_robIndex);                                 \
        DBG_READ(e.params.pc, dbgIo_rs_buffer_##i##_params_pc);                                              \
        DBG_READ(e.params.source1, dbgIo_rs_buffer_##i##_params_source1);                                    \
        DBG_READ(e.params.source2, dbgIo_rs_buffer_##i##_params_source2);                                    \
        DBG_READ(e.reg_req.prs1_valid, dbgIo_rs_buffer_##i##_regReq_prs1Valid);                              \
        DBG_READ(e.reg_req.prs2_valid, dbgIo_rs_buffer_##i##_regReq_prs2Valid);                              \
        DBG_READ(e.reg_req.prs1_is_rd, dbgIo_rs_buffer_##i##_regReq_prs1IsRd);                               \
        DBG_READ(e.reg_req.prs2_is_rd, dbgIo_rs_buffer_##i##_regReq_prs2IsRd);                               \
        DBG_READ(e.reg_req.prs1, dbgIo_rs_buffer_##i##_regReq_prs1);                                         \
        DBG_READ(e.reg_req.prs2, dbgIo_rs_buffer_##i##_regReq_prs2);                                         \
    } while (false);

#define DBG_SAMPLE_COMMIT(i)                                                 \
    do {                                                                     \
        if (top->dbgIo_events_commits_##i##_valid) {                         \
            CommitEvent e;                                                   \
            DBG_READ(e.prd_valid, dbgIo_events_commits_##i##_bits_prdValid); \
            DBG_READ(e.prd, dbgIo_events_commits_##i##_bits_prd);            \
            fire_commit(e);                                                  \
        }                                                                    \
    } while (false);

void DebugManager::sample(const std::unique_ptr<VMarkoRvCore> &top)
{
    DBG_READ(curr_pc, dbgIo_ifu_pc);

    if (top->dbgIo_ifu_fetchValid) {
        fetching_instr = static_cast<uint32_t>(top->dbgIo_ifu_fetchingInstr);
    } else {
        fetching_instr.reset();
    }

    DBG_FOR_EACH_64(DBG_SAMPLE_RF)
    DBG_FOR_EACH_16(DBG_SAMPLE_RT_ROW)
    DBG_FOR_EACH_16(DBG_SAMPLE_ROB)
    DBG_FOR_EACH_16(DBG_SAMPLE_RS)

    if (top->dbgIo_events_issue_valid) {
        IssueEvent e;
        DBG_READ(e.prd_valid, dbgIo_events_issue_bits_prdValid);
        DBG_READ(e.prd, dbgIo_events_issue_bits_prd);
        fire_issue(e);
    }

    DBG_FOR_EACH_5(DBG_SAMPLE_COMMIT)

    if (top->dbgIo_events_discon_valid) {
        DisconEvent e;
        DBG_READ(e.discon_type, dbgIo_events_discon_bits_disconType);
        DBG_READ(e.prd_valid, dbgIo_events_discon_bits_prdValid);
        DBG_READ(e.prd, dbgIo_events_discon_bits_prd);
        DBG_READ(e.prevprd, dbgIo_events_discon_bits_prevprd);
        DBG_READ(e.rename_ckpt_index, dbgIo_events_discon_bits_renameCkptIndex);
        fire_discon(e);
    }

    if (top->dbgIo_events_retire_valid) {
        RetireEvent e;
        DBG_READ(e.is_exception, dbgIo_events_retire_bits_isException);
        DBG_READ(e.inc_inst_ret, dbgIo_events_retire_bits_incInstRet);
        DBG_READ(e.prd_valid, dbgIo_events_retire_bits_prdValid);
        DBG_READ(e.prd, dbgIo_events_retire_bits_prd);
        DBG_READ(e.prevprd, dbgIo_events_retire_bits_prevprd);
        fire_retire(e);
    }
}

#undef DBG_SAMPLE_COMMIT
#undef DBG_SAMPLE_RS
#undef DBG_SAMPLE_ROB
#undef DBG_SAMPLE_RT_ROW
#undef DBG_SAMPLE_RT_CELL
#undef DBG_SAMPLE_RF
#undef DBG_FOR_EACH_RT_COL
#undef DBG_FOR_EACH_64
#undef DBG_FOR_EACH_16
#undef DBG_FOR_EACH_5
#undef DBG_READ

void DebugManager::print_rob()
{
    std::cout << "\n===== Reorder Buffer (ROB) Detailed Status =====\n";

    std::cout << std::format("{:<5} {:<6} {:<8} {:<18} {:<6} {:<8} {:<8} {:<6} {:<8}\n", "Idx", "Valid", "Commit", "PC",
                             "EXU", "PRD", "PrevPRD", "PRDok", "CkptIdx");
    std::cout << std::string(70, '-') << "\n";

    int valid_count = 0;
    int commit_count = 0;
    int discon_count = 0;

    for (size_t i = 0; i < CFG_ROB_SIZE; ++i) {
        const auto &e = rob_data[i];
        const auto &fc = e.f_ctrl;

        // EXU type string
        std::string exu_str;
        switch (static_cast<uint8_t>(e.exu)) {
        case EXUEnum::ALU:
            exu_str = "ALU";
            break;
        case EXUEnum::BRU:
            exu_str = "BRU";
            break;
        case EXUEnum::LSU:
            exu_str = "LSU";
            break;
        case EXUEnum::MDU:
            exu_str = "MDU";
            break;
        case EXUEnum::MISC:
            exu_str = "MISC";
            break;
        default:
            exu_str = std::format("?{:#x}", static_cast<uint8_t>(e.exu));
            break;
        }

        // Accumulate stats
        if (e.valid)
            ++valid_count;
        if (e.commited)
            ++commit_count;
        if (fc.discon)
            ++discon_count;

        // Main summary line
        std::cout << std::format("{:<5x} {:<6} {:<8} {:<6} {:<8} {:<8} {:<6} {:#x}\n", i, e.valid ? "Y" : "N",
                                 e.commited ? "Y" : "N", exu_str,
                                 e.prd_valid ? std::format("{:#x}", static_cast<uint16_t>(dbg_u(e.prd))) : "-",
                                 e.prd_valid ? std::format("{:#x}", static_cast<uint16_t>(dbg_u(e.prev_prd))) : "-",
                                 e.prd_valid ? "Y" : "N", static_cast<uint8_t>(e.rename_ckpt_index));

        // flowCtrl detail block (only printed when relevant)
        bool has_fc = fc.discon;
        if (has_fc) {
            std::cout << "  +-- flowCtrl "
                         "----------------------------------------------------\n";

            if (fc.discon) {
                std::cout << std::format("  |  discon      : Y  ->  event_pc = {:#018x}\n"
                                         "  |  trap        : Y\n"
                                         "  |    cause     : {:#x}\n"
                                         "  |    xtval     : {:#018x}\n"
                                         "  |  xret        : Y  type={} ({})  xepc={:#018x}\n",
                                         fc.event_pc, static_cast<int16_t>(fc.cause), fc.xtval,
                                         static_cast<uint8_t>(fc.xret_type), fc.xret_type ? "MRET" : "SRET", fc.xepc);
            } else {
                std::cout << "  |  discon      : N\n";
            }

            std::string discon_str;
            switch (static_cast<uint8_t>(dbg_u(fc.discon_type))) {
            case 0:
                discon_str = "INTERRUPT";
                break; // External Async Interrupt
            case 1:
                discon_str = "INSTR_EXCEPTION";
                break; // Sync Exception (syscall/illegal)
            case 2:
                discon_str = "INSTR_REDIRECT";
                break; // jalr redirect
            case 3:
                discon_str = "BRANCH_MISPRED";
                break; // Branch misprediction flush
            case 4:
                discon_str = "INSTR_SYNC";
                break; // fence.i etc.
            case 5:
                discon_str = "EXCEP_RETURN";
                break; // xret
            default:
                discon_str = std::format("?{:#x}", static_cast<uint8_t>(dbg_u(fc.discon_type)));
                break;
            }
            std::cout << std::format("  |  discon_type  : {}\n", discon_str);
            std::cout << "  "
                         "+----------------------------------------------------------"
                         "------\n";
        }
    }

    std::cout << std::string(70, '=') << "\n";
    std::cout << std::format("  Summary | valid={:<4} committed={:<4} discon={:<4} | total={}\n", valid_count,
                             commit_count, discon_count, CFG_ROB_SIZE);
    std::cout << std::string(70, '=') << "\n\n";
}

void DebugManager::print_rs()
{
    std::cout << "\n===== Reservation Station Status =====\n";
    std::cout << std::format("{:<5} {:<8} {:<10} {:<16} {:<10} {:<10} {:<16} {:<16}\n", "Idx", "Valid", "EXU", "PC",
                             "PRS1", "PRS2", "Source1", "Source2");

    for (size_t i = 0; i < CFG_RS_SIZE; ++i) {
        const auto &entry = rs_data[i];
        std::string exu_type;
        switch (entry.exu) {
        case EXUEnum::ALU:
            exu_type = "ALU";
            break;
        case EXUEnum::BRU:
            exu_type = "BRU";
            break;
        case EXUEnum::LSU:
            exu_type = "LSU";
            break;
        case EXUEnum::MDU:
            exu_type = "MDU";
            break;
        case EXUEnum::MISC:
            exu_type = "MISC";
            break;
        default:
            exu_type = "UNKNOWN";
            break;
        }

        std::cout << std::format("{:<5x} {:<8} {:<10} {:#016x} {:<10} {:<10} {:#016x} {:#016x}\n", i,
                                 entry.valid ? "Y" : "N", exu_type, entry.params.pc,
                                 entry.reg_req.prs1_valid ? std::format("{:#x}", dbg_u(entry.reg_req.prs1)) : "-",
                                 entry.reg_req.prs2_valid ? std::format("{:#x}", dbg_u(entry.reg_req.prs2)) : "-",
                                 entry.params.source1, entry.params.source2);

        switch (entry.exu) {
        case EXUEnum::ALU: {
            const auto &op = entry.opcodes.alu_op;
            std::cout << std::format("    ALU Op: funct3={:#x}, sra_sub={}, op32={}\n", dbg_u(op.funct3),
                                     op.sra_sub ? "Y" : "N", op.op32 ? "Y" : "N");
            break;
        }
        case EXUEnum::BRU: {
            const auto &op = entry.opcodes.bru_op;
            std::cout << std::format("    BRU Op: funct={:#x}, offset={:#x}, "
                                     "pred_taken={}, pred_pc={:#016x}\n",
                                     dbg_u(op.funct), dbg_u(op.offset), entry.pred_taken ? "Y" : "N", entry.pred_pc);
            break;
        }
        case EXUEnum::LSU: {
            const auto &op = entry.opcodes.lsu_op;
            std::cout << std::format("    LSU Op: funct={:#x}, size={:#x}\n", dbg_u(op.funct), op.size);
            break;
        }
        case EXUEnum::MDU: {
            const auto &op = entry.opcodes.mdu_op;
            std::cout << std::format("    MDU Op: funct3={:#x}, op32={}\n", dbg_u(op.funct3), op.op32 ? "Y" : "N");
            break;
        }
        case EXUEnum::MISC: {
            const auto &op = entry.opcodes.misc_op;
            std::cout << std::format("    MISC Op: mem_funct={:#x}, sys_funct={:#x}, csr_funct={:#x}\n",
                                     op.misc_mem_funct, op.misc_sys_funct, op.misc_csr_funct);
            break;
        }
        default:
            break;
        }
    }
    std::cout << "======================================\n";
}

void DebugManager::print_rt()
{
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

void DebugManager::print_rf()
{
    std::cout << "\n===== Register File Status =====\n";
    std::cout << std::format("{:<5} {:<18} {:<10}\n", "Idx", "Data", "State");

    for (size_t i = 0; i < CFG_RF_SIZE; ++i) {
        const auto &entry = rf_data[i];
        std::string state_str;
        switch (entry.state) {
        case PhyRegState::FREE:
            state_str = "FREE";
            break;
        case PhyRegState::ALLOCATED:
            state_str = "ALLOCATED";
            break;
        case PhyRegState::OCCUPIED:
            state_str = "OCCUPIED";
            break;
        case PhyRegState::COMMITTED:
            state_str = "COMMITTED";
            break;
        default:
            state_str = "UNKNOWN";
            break;
        }
        std::cout << std::format("{:<5} {:#018x} {:<10}\n", i, entry.data, state_str);
    }

    std::cout << "=================================\n";
}
