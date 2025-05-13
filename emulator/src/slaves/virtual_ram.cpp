#include "virtual_ram.hpp"

VirtualRAM::VirtualRAM(uint64_t base_addr, const std::string& file_path, uint64_t size) {
    this->base_addr = base_addr;
    this->size = size;
    assert((size & 0x0fff) == 0 && "Virtual RAM size must be 4k aligned.");
    range = std::ranges::iota_view<uint64_t, uint64_t>(0x0, size);

    ram = static_cast<uint8_t*>(std::malloc(size));
    if (!ram) throw std::runtime_error("Failed to allocate RAM");
    std::memset(ram, 0, size);

    if (init_ram(file_path, size)) {
        throw std::runtime_error("Failed to initialize RAM");
    }
}

VirtualRAM::~VirtualRAM() {
    if (ram) std::free(ram);
}

uint64_t VirtualRAM::read(uint64_t addr, uint8_t size) {
    assert((addr + (1 << size) - 1) < this->size && "Read address out of bounds");
    uint64_t data = 0;
    for (int i = 0; i < (1 << size); i++) {
        data |= static_cast<uint64_t>(ram[addr + i]) << (8 * i);
    }
    return data;
}

void VirtualRAM::write(uint64_t addr, uint64_t data, uint8_t size, uint8_t strb) {
    assert((addr + 7) < this->size && "Write address out of bounds");
    for (int i = 0; i < (1 << size); i++) {
        if (strb & (1 << i)) {
            ram[addr + i] = static_cast<uint8_t>(data >> (8 * i));
        }
    }
}

int VirtualRAM::init_ram(const std::string& file_path, uint64_t size) {
    std::ifstream file(file_path, std::ios::binary);
    if (!file) {
        std::cerr << "Can't open file:" << file_path << std::endl;
        return 1;
    }
    std::vector<uint8_t> raw((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    auto elf = ELF::from_raw(raw);

    if (elf.get_class_type() != ELF::ClassType::ELFCLASS64)
        throw std::runtime_error("ELF class type must be ELFCLASS64");
    if (elf.get_machine_type() != ELF::MachineType::EM_RISCV)
        throw std::runtime_error("ELF machine type must be EM_RISCV");

    for (const auto& ph : elf.get_program_headers_64()) {
        if (ph.p_type != ELF::SegmentType::PT_LOAD) continue;
        uint64_t target_addr = ph.p_paddr - this->base_addr;
        if (target_addr >= this->size)
            throw std::runtime_error(std::format("Target address({:x}) is out of bounds", target_addr));
        if (ph.p_filesz > this->size - target_addr)
            throw std::runtime_error(std::format("Write size({:x}) exceeds available memory space", ph.p_filesz));

        std::memcpy(ram + target_addr, raw.data() + ph.p_offset, ph.p_filesz);
    }

    return 0;
}
