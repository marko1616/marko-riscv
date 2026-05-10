#include "virtual_ram.hpp"

VirtualRAM::VirtualRAM(uint64_t base_addr, uint64_t size) : Slave(base_addr)
{
    this->size = size;
    assert((size & 0x0fff) == 0 && "Virtual RAM size must be 4k aligned.");
    range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, size);

    ram = static_cast<uint8_t *>(std::malloc(size));
    if (!ram) {
        throw std::runtime_error("Failed to allocate RAM");
    }
    std::memset(ram, 0, size);
}

VirtualRAM::~VirtualRAM()
{
    if (ram) {
        std::free(ram);
    }
}

uint64_t VirtualRAM::read(uint64_t addr, uint8_t size)
{
    const uint64_t bytes = 1ULL << size;
    check_range(addr, bytes, "AXI Read");

    uint64_t data = 0;
    for (uint64_t i = 0; i < bytes; i++) {
        data |= static_cast<uint64_t>(ram[addr + i]) << (8 * i);
    }
    return data;
}

void VirtualRAM::write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb)
{
    const uint64_t bytes = 1ULL << size;
    check_range(addr, bytes, "AXI Write");

    for (uint64_t i = 0; i < bytes; i++) {
        if (strb & (1 << i)) {
            ram[addr + i] = static_cast<uint8_t>(data >> (8 * i));
        }
    }
}

std::vector<uint8_t> VirtualRAM::read_file(const std::string &file_path)
{
    std::ifstream file(file_path, std::ios::binary);
    if (!file) {
        throw std::runtime_error(std::format("Can't open file: {}", file_path));
    }

    return std::vector<uint8_t>(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
}

void VirtualRAM::check_range(uint64_t addr, uint64_t len, const std::string &what) const
{
    if (addr >= size || len > size - addr) {
        throw std::runtime_error(
            std::format("{} out of bounds: addr=0x{:x}, len=0x{:x}, ram_size=0x{:x}", what, addr, len, size));
    }
}

void VirtualRAM::load_elf(const std::string &file_path)
{
    auto raw = read_file(file_path);
    auto elf = ELF::from_raw(raw);

    if (elf.get_class_type() != ELF::ClassType::ELFCLASS64) {
        throw std::runtime_error("ELF class type must be ELFCLASS64");
    }
    if (elf.get_machine_type() != ELF::MachineType::EM_RISCV) {
        throw std::runtime_error("ELF machine type must be EM_RISCV");
    }

    auto header = elf.get_header_64();
    const bool is_pie = (static_cast<int>(header.e_type) == 3);

    uint64_t elf_base = UINT64_MAX;
    for (const auto &ph : elf.get_program_headers_64()) {
        if (ph.p_type != ELF::SegmentType::PT_LOAD) {
            continue;
        }
        if (ph.p_paddr < elf_base) {
            elf_base = ph.p_paddr;
        }
    }

    if (elf_base == UINT64_MAX) {
        elf_base = 0;
    }

    const uint64_t load_offset = is_pie ? elf_base : this->base_addr;

    for (const auto &ph : elf.get_program_headers_64()) {
        if (ph.p_type != ELF::SegmentType::PT_LOAD) {
            continue;
        }

        if (ph.p_paddr < load_offset) {
            throw std::runtime_error(std::format("ELF segment address underflow: p_paddr=0x{:x}, load_offset=0x{:x}",
                                                 ph.p_paddr, load_offset));
        }

        if (ph.p_offset + ph.p_filesz > raw.size()) {
            throw std::runtime_error(std::format("ELF segment exceeds file size: offset=0x{:x}, "
                                                 "filesz=0x{:x}, raw=0x{:x}",
                                                 ph.p_offset, ph.p_filesz, raw.size()));
        }

        const uint64_t target_addr = ph.p_paddr - load_offset;
        check_range(target_addr, ph.p_memsz, "ELF segment");

        std::memcpy(ram + target_addr, raw.data() + ph.p_offset, ph.p_filesz);

        if (ph.p_memsz > ph.p_filesz) {
            std::memset(ram + target_addr + ph.p_filesz, 0, ph.p_memsz - ph.p_filesz);
        }
    }
}

void VirtualRAM::load_bin(const std::string &file_path, uint64_t addr)
{
    auto raw = read_file(file_path);

    if (addr < this->base_addr) {
        throw std::runtime_error(
            std::format("BIN load address(0x{:x}) is below base address(0x{:x})", addr, this->base_addr));
    }

    const uint64_t target_addr = addr - this->base_addr;
    check_range(target_addr, raw.size(), "BIN payload");

    std::memcpy(ram + target_addr, raw.data(), raw.size());
}
