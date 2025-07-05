#include "clint.hpp"

VirtualCLINT::VirtualCLINT(uint64_t base_addr) : Slave(base_addr) {
    range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, 0xc0000);
}

uint64_t VirtualCLINT::read(uint64_t addr, uint8_t size) {
    if(addr == MTIME_OFFSET)
        return mtime;

    if(addr == MTIMECMP_OFFSET)
        return mtimecmp;

    if(addr == MSIP_OFFSET)
        return msip;

    return 0;
}

void VirtualCLINT::write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) {
    if(addr == MTIME_OFFSET) {
        mtime = data;
        return;
    }

    if(addr == MTIMECMP_OFFSET) {
        mtimecmp = data;
        return;
    }

    if(addr == MSIP_OFFSET) {
        msip = data;
        return;
    }

    return;
}

void VirtualCLINT::step(const std::unique_ptr<VMarkoRvCore> &top) {
    mtime++;

    if (mtime >= mtimecmp)
        top->io_mtip = 1;
    else
        top->io_mtip = 0;

    if (msip & 1)
        top->io_msip = 1;
    else
        top->io_msip = 0;
}