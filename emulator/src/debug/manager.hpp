#include <array>
#include <cstdint>
#include <functional>
#include <optional>
#include <type_traits>
#include <vector>
#include <iostream>

#include "VMarkoRvCore.h"
#include "../config.hpp"

/**
 * @brief Computes ceiling of log2 for a value at compile-time
 */
constexpr int log2_ceil(unsigned int n, int p = 0) {
    return (1U << p) >= n ? p : log2_ceil(n, p + 1);
}

/**
 * @brief Utilities for determining appropriate type based on bit width
 */
template <size_t W>
struct BitUtils {
    static_assert(W > 0, "Bit width must be positive");

    struct RawBits {
        uint8_t data[(W + 7) / 8];
    };

    using type = decltype([]<size_t Width = W>() {
        if constexpr (Width == 1)
            return bool{};
        else if constexpr (Width > 1 && Width <= 8)
            return uint8_t{};
        else if constexpr (Width > 8 && Width <= 16)
            return uint16_t{};
        else if constexpr (Width > 16 && Width <= 32)
            return uint32_t{};
        else if constexpr (Width > 32 && Width <= 64)
            return uint64_t{};
        else
            return RawBits{};
    }());
};

namespace EXUEnum {
    enum Type : uint8_t {
        ALU = 0,
        BRU = 1,
        LSU = 2,
        MDU = 3,
        MISC = 4
    };
}

namespace DisconEventEnum {
    enum Type : uint8_t {
        INTERRUPT = 0,
        INSTR_EXCEPTION = 1,
        INSTR_REDIRECT = 2,
        BRANCH_MISPRED = 3,
        INSTR_SYNC = 4,
        EXCEP_RETURN = 5
    };
}

namespace PhyRegState {
    enum Type : uint8_t {
        FREE = 0,
        ALLOCATED = 1,
        OCCUPIED = 2,
        COMMITTED = 3
    };
}

constexpr size_t rob_idx_width = log2_ceil(CFG_ROB_SIZE);
constexpr size_t rs_idx_width = log2_ceil(CFG_RS_SIZE);
constexpr size_t rt_idx_width = log2_ceil(CFG_RT_SIZE);
constexpr size_t rf_idx_width = log2_ceil(CFG_RF_SIZE);

using rob_index_t = BitUtils<rob_idx_width>::type;
using rs_index_t = BitUtils<rs_idx_width>::type;
using rt_index_t = BitUtils<rt_idx_width>::type;
using rf_index_t = BitUtils<rf_idx_width>::type;

template <typename T>
constexpr uint64_t dbg_u(T v) {
    if constexpr (std::is_enum_v<T>) {
        return static_cast<uint64_t>(static_cast<std::underlying_type_t<T>>(v));
    } else {
        return static_cast<uint64_t>(v);
    }
}

struct flowCtrl {
    uint64_t event_pc = 0;
    bool xret_type = false;
    uint64_t xepc = 0;
    uint64_t xtval = 0;
    int16_t cause = 0;
    uint8_t discon_type = 0;
    bool discon = false;
};

struct robEntry {
    rt_index_t rename_ckpt_index = 0;
    bool commited = false;
    flowCtrl f_ctrl;
    rf_index_t prev_prd = 0;
    rf_index_t prd = 0;
    bool prd_valid = false;
    uint8_t exu = 0;
    bool valid = false;
};

struct PhyRegRequests {
    rf_index_t prs2 = 0;
    rf_index_t prs1 = 0;
    bool prs2_is_rd = false;
    bool prs1_is_rd = false;
    bool prs2_valid = false;
    bool prs1_valid = false;
};

struct EXUParams {
    uint64_t source2 = 0;
    uint64_t source1 = 0;
    uint64_t pc = 0;
    rob_index_t rob_index = 0;
};

struct MDUOpcode {
    uint8_t funct3 = 0;
    bool op32 = false;
};

struct MISCOpcode {
    uint8_t misc_mem_funct = 0;
    uint8_t misc_sys_funct = 0;
    uint8_t misc_csr_funct = 0;
    uint32_t raw_instr = 0;
};

struct LoadStoreOpcode {
    uint8_t size = 0;
    uint8_t funct = 0;
};

struct BranchOpcode {
    uint16_t offset = 0;
    uint8_t funct = 0;
};

struct ALUOpcode {
    uint8_t funct3 = 0;
    bool sra_sub = false;
    bool op32 = false;
};

struct ExuOpcode {
    MISCOpcode misc_op;
    MDUOpcode mdu_op;
    LoadStoreOpcode lsu_op;
    BranchOpcode bru_op;
    ALUOpcode alu_op;
};

struct ReservationStationEntry {
    PhyRegRequests reg_req;
    EXUParams params;
    uint64_t pred_pc = 0;
    bool pred_taken = false;
    ExuOpcode opcodes;
    uint8_t exu = 0;
    bool valid = false;
};

struct IssueEvent {
    bool prd_valid = false;
    rf_index_t prd = 0;
};

struct CommitEvent {
    bool prd_valid = false;
    rf_index_t prd = 0;
};

struct DisconEvent {
    DisconEventEnum::Type discon_type = DisconEventEnum::INTERRUPT;
    bool prd_valid = false;
    rf_index_t prd = 0;
    rf_index_t prevprd = 0;
    rt_index_t rename_ckpt_index = 0;
};

struct RetireEvent {
    bool is_exception = false;
    bool inc_inst_ret = false;
    bool prd_valid = false;
    rf_index_t prd = 0;
    rf_index_t prevprd = 0;
};

struct RegisterEntry {
    uint64_t data = 0;
    uint8_t state = 0;
};

class DebugManager {
public:
    uint64_t curr_pc = 0;
    std::optional<uint32_t> fetching_instr;

    static DebugManager& get_instance() {
        static DebugManager instance;
        return instance;
    }

    void sample(const std::unique_ptr<VMarkoRvCore> &top);

    void print_rob();
    void print_rs();
    void print_rt();
    void print_rf();

    using IssueCallback  = std::function<void(const IssueEvent&)>;
    using CommitCallback = std::function<void(const CommitEvent&)>;
    using DisconCallback = std::function<void(const DisconEvent&)>;
    using RetireCallback = std::function<void(const RetireEvent&)>;

    void on_issue (IssueCallback  cb) { issue_callbacks_.push_back(std::move(cb)); }
    void on_commit(CommitCallback cb) { commit_callbacks_.push_back(std::move(cb)); }
    void on_discon(DisconCallback cb) { discon_callbacks_.push_back(std::move(cb)); }
    void on_retire(RetireCallback cb) { retire_callbacks_.push_back(std::move(cb)); }

private:
    DebugManager() = default;
    ~DebugManager() = default;

    DebugManager(const DebugManager&) = delete;
    DebugManager& operator=(const DebugManager&) = delete;

    std::array<robEntry, CFG_ROB_SIZE> rob_data{};
    std::array<ReservationStationEntry, CFG_RS_SIZE> rs_data{};
    std::array<std::array<uint32_t, 31>, CFG_RT_SIZE> rt_data{};
    std::array<RegisterEntry, CFG_RF_SIZE> rf_data{};

    std::vector<IssueCallback>  issue_callbacks_;
    std::vector<CommitCallback> commit_callbacks_;
    std::vector<DisconCallback> discon_callbacks_;
    std::vector<RetireCallback> retire_callbacks_;

    template<typename CallbackList, typename Event>
    static void dispatch(const CallbackList& list, const Event& e) {
        for (const auto& cb : list) {
            cb(e);
        }
    }

    void fire_issue (const IssueEvent&  e) { dispatch(issue_callbacks_,  e); }
    void fire_commit(const CommitEvent& e) { dispatch(commit_callbacks_, e); }
    void fire_discon(const DisconEvent& e) { dispatch(discon_callbacks_, e); }
    void fire_retire(const RetireEvent& e) { dispatch(retire_callbacks_, e); }
};
