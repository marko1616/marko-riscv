#include <boost/pfr.hpp>
#include <iostream>
#include <format>

#include "svdpi.h"
#include "verilator_abi.hpp"
#include "../config.hpp"

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

// Reorder Buffer
using rob_index_t = BitUtils<rob_idx_width>::type;
// Reservation Station
using rs_index_t = BitUtils<rs_idx_width>::type;
// Rename Table
using rt_index_t = BitUtils<rt_idx_width>::type;
// Register File
using rf_index_t = BitUtils<rf_idx_width>::type;

struct flowCtrl {
    Field<uint64_t, 64> recover_pc;
    Field<bool, 1> recover;
    Field<bool, 1> xret;
    Field<uint64_t, 64> xepc;
    Field<uint64_t, 64> xtval;
    Field<int16_t, 16> cause;
    Field<bool, 1> trap;
    Field<uint8_t, 3> discon_type;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

struct robEntry {
    Field<uint8_t, rt_idx_width> rename_ckpt_index;
    Field<bool, 1> commited;

    flowCtrl f_ctrl;

    Field<uint64_t, 64> pc;
    Field<rf_index_t, rf_idx_width> prev_prd;
    Field<rf_index_t, rf_idx_width> prd;
    Field<bool, 1> prd_valid;

    Field<uint8_t, 3> exu;
    Field<bool, 1> valid;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};
static std::array<robEntry, CFG_ROB_SIZE> rob_data;

struct PhyRegRequests {
    Field<uint8_t, log2_ceil(CFG_RF_SIZE)> prs2;
    Field<uint8_t, log2_ceil(CFG_RF_SIZE)> prs1;
    Field<bool, 1> prs2_is_rd;
    Field<bool, 1> prs1_is_rd;
    Field<bool, 1> prs2_valid;
    Field<bool, 1> prs1_valid;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

struct EXUParams {
    Field<uint64_t, 64> source2;
    Field<uint64_t, 64> source1;
    Field<uint64_t, 64> pc;
    Field<uint8_t, log2_ceil(CFG_ROB_SIZE)> rob_index;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

struct MDUOpcode {
    Field<uint8_t, 3> funct3;
    Field<bool, 1> op32;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

struct MISCOpcode {
    Field<uint8_t, 2> misc_mem_funct;
    Field<uint8_t, 3> misc_sys_funct;
    Field<uint8_t, 4> misc_csr_funct;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

struct LoadStoreOpcode {
    Field<uint8_t, 3> size;
    Field<uint8_t, 6> funct;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

struct BranchOpcode {
    Field<uint16_t, 12> offset;
    Field<uint8_t, 4> funct;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

struct ALUOpcode {
    Field<uint8_t, 3> funct3;
    Field<bool, 1> sra_sub;
    Field<bool, 1> op32;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

struct OpcodeBundle {
    MISCOpcode misc_op;
    MDUOpcode mdu_op;
    LoadStoreOpcode lsu_op;
    BranchOpcode bru_op;
    ALUOpcode alu_op;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

struct ReservationStationEntry {
    PhyRegRequests reg_req;
    EXUParams params;
    Field<uint64_t, 64> pred_pc;
    Field<bool, 1> pred_taken;
    OpcodeBundle opcodes;
    Field<uint8_t, 3> exu;  // EXUEnum::Type
    Field<bool, 1> valid;

    constexpr auto as_tuple() {
        return boost::pfr::structure_tie(*this);
    }
};

static std::array<ReservationStationEntry, CFG_RS_SIZE> rs_data;

static std::array<std::array<uint32_t, 31>, CFG_RT_SIZE> rt_data;

struct RegisterEntry {
    uint64_t data;
    uint8_t state;
};
static std::array<RegisterEntry, CFG_RF_SIZE> rf_data;

class DpiManager {
public:
    uint64_t curr_pc;
    std::optional<uint32_t> fetching_instr;

    static DpiManager& get_instance() {
        static DpiManager instance;
        return instance;
    }
    void print_rob();
    void print_rs();
    void print_rt();
    void print_rf();
private:
    DpiManager() {}
    ~DpiManager() {}
    DpiManager(const DpiManager&) = delete;
    DpiManager& operator=(const DpiManager&) = delete;
};
