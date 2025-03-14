#include "elf.hpp"

template <typename T, typename U>
U read_data(const uint8_t* data, size_t offset, std::endian endianness) {
    T value;
    std::memcpy(&value, data + offset, sizeof(T));
    if (sizeof(T) > 1 && std::endian::native != endianness) {
        value = std::byteswap(value);
    }
    return static_cast<U>(value);
}

std::string ELF::segment_type_to_string(SegmentType type) {
    switch (type) {
        case SegmentType::PT_NULL:
            return "PT_NULL";
        case SegmentType::PT_LOAD:
            return "PT_LOAD";
        case SegmentType::PT_DYNAMIC:
            return "PT_DYNAMIC";
        case SegmentType::PT_INTERP:
            return "PT_INTERP";
        case SegmentType::PT_NOTE:
            return "PT_NOTE";
        case SegmentType::PT_SHLIB:
            return "PT_SHLIB";
        case SegmentType::PT_PHDR:
            return "PT_PHDR";
        case SegmentType::PT_TLS:
            return "PT_TLS";
        case SegmentType::PT_LOOS:
            return "PT_LOOS";
        case SegmentType::PT_HIOS:
            return "PT_HIOS";
        case SegmentType::PT_LOPROC:
            return "PT_LOPROC";
        case SegmentType::PT_HIPROC:
            return "PT_HIPROC";
        default: {
            std::stringstream ss;
            ss << "PT_UNKNOWN (0x" << std::hex << static_cast<uint32_t>(type)
               << ")";
            return ss.str();
        }
    }
}

std::string ELF::segment_flags_to_string(uint32_t flags) {
    std::stringstream ss;
    if (flags & static_cast<uint32_t>(SegmentFlags::PF_R)) ss << "R";
    if (flags & static_cast<uint32_t>(SegmentFlags::PF_W)) ss << "W";
    if (flags & static_cast<uint32_t>(SegmentFlags::PF_X)) ss << "E";
    if (ss.str().empty()) ss << "NONE";
    return ss.str();
}

std::string ELF::section_type_to_string(SectionType type) {
    switch (type) {
        case SectionType::SHT_NULL:
            return "SHT_NULL";
        case SectionType::SHT_PROGBITS:
            return "SHT_PROGBITS";
        case SectionType::SHT_SYMTAB:
            return "SHT_SYMTAB";
        case SectionType::SHT_STRTAB:
            return "SHT_STRTAB";
        case SectionType::SHT_RELA:
            return "SHT_RELA";
        case SectionType::SHT_HASH:
            return "SHT_HASH";
        case SectionType::SHT_DYNAMIC:
            return "SHT_DYNAMIC";
        case SectionType::SHT_NOTE:
            return "SHT_NOTE";
        case SectionType::SHT_NOBITS:
            return "SHT_NOBITS";
        case SectionType::SHT_REL:
            return "SHT_REL";
        case SectionType::SHT_SHLIB:
            return "SHT_SHLIB";
        case SectionType::SHT_DYNSYM:
            return "SHT_DYNSYM";
        case SectionType::SHT_INIT_ARRAY:
            return "SHT_INIT_ARRAY";
        case SectionType::SHT_FINI_ARRAY:
            return "SHT_FINI_ARRAY";
        case SectionType::SHT_PREINIT_ARRAY:
            return "SHT_PREINIT_ARRAY";
        case SectionType::SHT_GROUP:
            return "SHT_GROUP";
        case SectionType::SHT_SYMTAB_SHNDX:
            return "SHT_SYMTAB_SHNDX";
        case SectionType::SHT_NUM:
            return "SHT_NUM";
        case SectionType::SHT_LOOS:
            return "SHT_LOOS";
        case SectionType::SHT_HIOS:
            return "SHT_HIOS";
        case SectionType::SHT_LOPROC:
            return "SHT_LOPROC";
        case SectionType::SHT_HIPROC:
            return "SHT_HIPROC";
        case SectionType::SHT_LOUSER:
            return "SHT_LOUSER";
        case SectionType::SHT_HIUSER:
            return "SHT_HIUSER";
        default: {
            std::stringstream ss;
            ss << "SHT_UNKNOWN (0x" << std::hex << static_cast<uint32_t>(type)
               << ")";
            return ss.str();
        }
    }
}

std::string ELF::section_flags_to_string(uint64_t flags) {
    std::stringstream ss;
    if (flags & static_cast<uint64_t>(SectionFlags::SHF_WRITE)) ss << "W";
    if (flags & static_cast<uint64_t>(SectionFlags::SHF_ALLOC)) ss << "A";
    if (flags & static_cast<uint64_t>(SectionFlags::SHF_EXECINSTR)) ss << "X";
    if (flags & static_cast<uint64_t>(SectionFlags::SHF_MERGE)) ss << "M";
    if (flags & static_cast<uint64_t>(SectionFlags::SHF_STRINGS)) ss << "S";
    if (ss.str().empty()) ss << "NONE";
    return ss.str();
}

std::string ELF::ProgramHeader32::get_type_string() const {
    return segment_type_to_string(p_type);
}
std::string ELF::ProgramHeader32::get_flags_string() const {
    return segment_flags_to_string(p_flags);
}

std::string ELF::ProgramHeader64::get_type_string() const {
    return segment_type_to_string(p_type);
}
std::string ELF::ProgramHeader64::get_flags_string() const {
    return segment_flags_to_string(p_flags);
}

std::string ELF::SectionHeader32::get_type_string() const {
    return section_type_to_string(sh_type);
}
std::string ELF::SectionHeader32::get_flags_string() const {
    return section_flags_to_string(sh_flags);
}

std::string ELF::SectionHeader64::get_type_string() const {
    return section_type_to_string(sh_type);
}
std::string ELF::SectionHeader64::get_flags_string() const {
    return section_flags_to_string(sh_flags);
}

ELF ELF::from_file(const fs::path& file_path) {
    std::ifstream file(file_path, std::ios::binary);
    if (!file.is_open()) {
        throw std::runtime_error("Failed to open file: " + file_path.string());
    }

    std::vector<uint8_t> raw_data;
    char buffer[4096];
    while (file.read(buffer, sizeof(buffer)) || file.gcount() > 0) {
        raw_data.insert(raw_data.end(), buffer, buffer + file.gcount());
    }

    return from_raw(raw_data);
}

ELF ELF::from_raw(const std::vector<uint8_t>& raw_data) {
    ELF elf_file;
    elf_file.raw_data_ = raw_data;
    elf_file.parse_header();
    elf_file.parse_program_headers();
    elf_file.parse_section_headers();
    elf_file.load_section_names_string_table();
    return elf_file;
}

const ELF::Header32& ELF::get_header_32() const {
    if (class_type_ != ClassType::ELFCLASS32) {
        throw std::runtime_error("Not a 32-bit ELF file");
    }
    return header_32_;
}

const ELF::Header64& ELF::get_header_64() const {
    if (class_type_ != ClassType::ELFCLASS64) {
        throw std::runtime_error("Not a 64-bit ELF file");
    }
    return header_64_;
}

ELF::ClassType ELF::get_class_type() const { return class_type_; }

ELF::MachineType ELF::get_machine_type() const {
    if (this->get_class_type() == ELF::ClassType::ELFCLASS32) {
        return this->header_32_.e_machine;
    } else if(this->get_class_type() == ELF::ClassType::ELFCLASS64) {
        return this->header_64_.e_machine;
    } else {
        throw std::runtime_error("Class type must be ELFCLASS32 or ELFCLASS64");
    }
}

const std::vector<ELF::ProgramHeader32>& ELF::get_program_headers_32() const {
    if (class_type_ != ClassType::ELFCLASS32) {
        throw std::runtime_error("Not a 32-bit ELF file");
    }
    return program_headers_32_;
}

const std::vector<ELF::ProgramHeader64>& ELF::get_program_headers_64() const {
    if (class_type_ != ClassType::ELFCLASS64) {
        throw std::runtime_error("Not a 64-bit ELF file");
    }
    return program_headers_64_;
}

const std::vector<ELF::SectionHeader32>& ELF::get_section_headers_32() const {
    if (class_type_ != ClassType::ELFCLASS32) {
        throw std::runtime_error("Not a 32-bit ELF file");
    }
    return section_headers_32_;
}

const std::vector<ELF::SectionHeader64>& ELF::get_section_headers_64() const {
    if (class_type_ != ClassType::ELFCLASS64) {
        throw std::runtime_error("Not a 64-bit ELF file");
    }
    return section_headers_64_;
}

void ELF::print_program_header(const ProgramHeader32& ph) const {
    std::cout << "  Type:             " << ph.get_type_string() << "\n";
    std::cout << "  Offset:           0x" << std::hex << ph.p_offset << "\n";
    std::cout << "  Virtual Address:  0x" << std::hex << ph.p_vaddr << "\n";
    std::cout << "  Physical Address: 0x" << std::hex << ph.p_paddr << "\n";
    std::cout << "  File Size:        0x" << std::hex << ph.p_filesz << "\n";
    std::cout << "  Memory Size:      0x" << std::hex << ph.p_memsz << "\n";
    std::cout << "  Flags:            " << ph.get_flags_string() << " (0x"
              << std::hex << ph.p_flags << ")\n";
    std::cout << "  Alignment:        0x" << std::hex << ph.p_align << "\n";
}

void ELF::print_program_header(const ProgramHeader64& ph) const {
    std::cout << "  Type:             " << ph.get_type_string() << "\n";
    std::cout << "  Flags:            " << ph.get_flags_string() << " (0x"
              << std::hex << ph.p_flags << ")\n";
    std::cout << "  Offset:           0x" << std::hex << ph.p_offset << "\n";
    std::cout << "  Virtual Address:  0x" << std::hex << ph.p_vaddr << "\n";
    std::cout << "  Physical Address: 0x" << std::hex << ph.p_paddr << "\n";
    std::cout << "  File Size:        0x" << std::hex << ph.p_filesz << "\n";
    std::cout << "  Memory Size:      0x" << std::hex << ph.p_memsz << "\n";
    std::cout << "  Alignment:        0x" << std::hex << ph.p_align << "\n";
}

void ELF::print_section_header(const SectionHeader32& sh) const {
    std::cout << "  Name:             " << get_section_name(sh) << "\n";
    std::cout << "  Type:             " << sh.get_type_string() << "\n";
    std::cout << "  Flags:            " << sh.get_flags_string() << " (0x"
              << std::hex << sh.sh_flags << ")\n";
    std::cout << "  Address:          0x" << std::hex << sh.sh_addr << "\n";
    std::cout << "  Offset:           0x" << std::hex << sh.sh_offset << "\n";
    std::cout << "  Size:             0x" << std::hex << sh.sh_size << "\n";
    std::cout << "  Link:             0x" << std::hex << sh.sh_link << "\n";
    std::cout << "  Info:             0x" << std::hex << sh.sh_info << "\n";
    std::cout << "  Address Align:    0x" << std::hex << sh.sh_addralign
              << "\n";
    std::cout << "  Entry Size:       0x" << std::hex << sh.sh_entsize << "\n";
}

void ELF::print_section_header(const SectionHeader64& sh) const {
    std::cout << "  Name:             " << get_section_name(sh) << "\n";
    std::cout << "  Type:             " << sh.get_type_string() << "\n";
    std::cout << "  Flags:            " << sh.get_flags_string() << " (0x"
              << std::hex << sh.sh_flags << ")\n";
    std::cout << "  Address:          0x" << std::hex << sh.sh_addr << "\n";
    std::cout << "  Offset:           0x" << std::hex << sh.sh_offset << "\n";
    std::cout << "  Size:             0x" << std::hex << sh.sh_size << "\n";
    std::cout << "  Link:             0x" << std::hex << sh.sh_link << "\n";
    std::cout << "  Info:             0x" << std::hex << sh.sh_info << "\n";
    std::cout << "  Address Align:    0x" << std::hex << sh.sh_addralign
              << "\n";
    std::cout << "  Entry Size:       0x" << std::hex << sh.sh_entsize << "\n";
}

void ELF::print_program_headers() const {
    std::cout << "Program Headers:\n";
    if (class_type_ == ClassType::ELFCLASS32) {
        for (const auto& ph : program_headers_32_) {
            print_program_header(ph);
            std::cout << "\n";
        }
    } else {
        for (const auto& ph : program_headers_64_) {
            print_program_header(ph);
            std::cout << "\n";
        }
    }
}

void ELF::print_section_headers() const {
    std::cout << "Section Headers:\n";
    if (class_type_ == ClassType::ELFCLASS32) {
        for (const auto& sh : section_headers_32_) {
            print_section_header(sh);
            std::cout << "\n";
        }
    } else {
        for (const auto& sh : section_headers_64_) {
            print_section_header(sh);
            std::cout << "\n";
        }
    }
}

void ELF::print_header() const {
    std::cout << "ELF Header:\n";
    if (class_type_ == ClassType::ELFCLASS32) {
        std::cout << "  Class:                             ELF32\n";
        std::cout << "  Data:                              ";
        if (data_encoding_ == DataEncoding::ELFDATA2LSB)
            std::cout << "2's complement, little endian\n";
        else if (data_encoding_ == DataEncoding::ELFDATA2MSB)
            std::cout << "2's complement, big endian\n";
        else
            std::cout << "unknown\n";
        std::cout << "  Type:                              "
                  << static_cast<int>(header_32_.e_type) << "\n";
        std::cout << "  Machine:                           "
                  << static_cast<int>(header_32_.e_machine) << "\n";
        std::cout << "  Version:                           0x" << std::hex
                  << header_32_.e_version << "\n";
        std::cout << "  Entry point address:               0x" << std::hex
                  << header_32_.e_entry << "\n";
        std::cout << "  Start of program headers:          "
                  << header_32_.e_phoff << " (bytes into file)\n";
        std::cout << "  Start of section headers:          "
                  << header_32_.e_shoff << " (bytes into file)\n";
        std::cout << "  Flags:                             0x" << std::hex
                  << header_32_.e_flags << "\n";
        std::cout << "  Size of this header:               "
                  << header_32_.e_ehsize << " (bytes)\n";
        std::cout << "  Size of program headers:           "
                  << header_32_.e_phentsize << " (bytes)\n";
        std::cout << "  Number of program headers:         "
                  << header_32_.e_phnum << "\n";
        std::cout << "  Size of section headers:           "
                  << header_32_.e_shentsize << " (bytes)\n";
        std::cout << "  Number of section headers:         "
                  << header_32_.e_shnum << "\n";
        std::cout << "  Section header string table index: "
                  << header_32_.e_shstrndx << "\n";

    } else {
        std::cout << "  Class:                             ELF64\n";
        std::cout << "  Data:                              ";
        if (data_encoding_ == DataEncoding::ELFDATA2LSB)
            std::cout << "2's complement, little endian\n";
        else if (data_encoding_ == DataEncoding::ELFDATA2MSB)
            std::cout << "2's complement, big endian\n";
        else
            std::cout << "unknown\n";
        std::cout << "  Type:                              "
                  << static_cast<int>(header_64_.e_type) << "\n";
        std::cout << "  Machine:                           "
                  << static_cast<int>(header_64_.e_machine) << "\n";
        std::cout << "  Version:                           0x" << std::hex
                  << header_64_.e_version << "\n";
        std::cout << "  Entry point address:               0x" << std::hex
                  << header_64_.e_entry << "\n";
        std::cout << "  Start of program headers:          "
                  << header_64_.e_phoff << " (bytes into file)\n";
        std::cout << "  Start of section headers:          "
                  << header_64_.e_shoff << " (bytes into file)\n";
        std::cout << "  Flags:                             0x" << std::hex
                  << header_64_.e_flags << "\n";
        std::cout << "  Size of this header:               "
                  << header_64_.e_ehsize << " (bytes)\n";
        std::cout << "  Size of program headers:           "
                  << header_64_.e_phentsize << " (bytes)\n";
        std::cout << "  Number of program headers:         "
                  << header_64_.e_phnum << "\n";
        std::cout << "  Size of section headers:           "
                  << header_64_.e_shentsize << " (bytes)\n";
        std::cout << "  Number of section headers:         "
                  << header_64_.e_shnum << "\n";
        std::cout << "  Section header string table index: "
                  << header_64_.e_shstrndx << "\n";
    }
}

void ELF::parse_header() {
    if (raw_data_.size() < 16) {
        throw std::runtime_error("Incomplete ELF header data");
    }

    if (raw_data_[0] != 0x7F || raw_data_[1] != 'E' || raw_data_[2] != 'L' ||
        raw_data_[3] != 'F') {
        throw std::runtime_error("Invalid ELF magic number");
    }

    class_type_ = static_cast<ClassType>(raw_data_[4]);
    if (class_type_ != ClassType::ELFCLASS64 &&
        class_type_ != ClassType::ELFCLASS32) {
        throw std::runtime_error("Unsupported ELF class");
    }

    data_encoding_ = static_cast<DataEncoding>(raw_data_[5]);
    if (data_encoding_ == DataEncoding::ELFDATA2LSB) {
        target_endian_ = std::endian::little;
    } else if (data_encoding_ == DataEncoding::ELFDATA2MSB) {
        target_endian_ = std::endian::big;
    } else {
        throw std::runtime_error("Unknown ELF data encoding");
    }

    size_t header_size = (class_type_ == ClassType::ELFCLASS32)
                             ? sizeof(Header32)
                             : sizeof(Header64);
    if (raw_data_.size() < header_size) {
        throw std::runtime_error("Incomplete ELF header data");
    }

    if (class_type_ == ClassType::ELFCLASS32) {
        std::memcpy(&header_32_.e_ident, raw_data_.data(), 16);
        header_32_.e_type = read_data<uint16_t, FileType>(
            raw_data_.data(), offsetof(Header32, e_type), target_endian_);
        header_32_.e_machine = read_data<uint16_t, MachineType>(
            raw_data_.data(), offsetof(Header32, e_machine), target_endian_);
        header_32_.e_version = read_data<uint32_t>(
            raw_data_.data(), offsetof(Header32, e_version), target_endian_);
        header_32_.e_entry = read_data<uint32_t>(
            raw_data_.data(), offsetof(Header32, e_entry), target_endian_);
        header_32_.e_phoff = read_data<uint32_t>(
            raw_data_.data(), offsetof(Header32, e_phoff), target_endian_);
        header_32_.e_shoff = read_data<uint32_t>(
            raw_data_.data(), offsetof(Header32, e_shoff), target_endian_);
        header_32_.e_flags = read_data<uint32_t>(
            raw_data_.data(), offsetof(Header32, e_flags), target_endian_);
        header_32_.e_ehsize = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header32, e_ehsize), target_endian_);
        header_32_.e_phentsize = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header32, e_phentsize), target_endian_);
        header_32_.e_phnum = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header32, e_phnum), target_endian_);
        header_32_.e_shentsize = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header32, e_shentsize), target_endian_);
        header_32_.e_shnum = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header32, e_shnum), target_endian_);
        header_32_.e_shstrndx = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header32, e_shstrndx), target_endian_);
    } else {
        std::memcpy(&header_64_.e_ident, raw_data_.data(), 16);
        header_64_.e_type = read_data<uint16_t, FileType>(
            raw_data_.data(), offsetof(Header64, e_type), target_endian_);
        header_64_.e_machine = read_data<uint16_t, MachineType>(
            raw_data_.data(), offsetof(Header64, e_machine), target_endian_);
        header_64_.e_version = read_data<uint32_t>(
            raw_data_.data(), offsetof(Header64, e_version), target_endian_);
        header_64_.e_entry = read_data<uint64_t>(
            raw_data_.data(), offsetof(Header64, e_entry), target_endian_);
        header_64_.e_phoff = read_data<uint64_t>(
            raw_data_.data(), offsetof(Header64, e_phoff), target_endian_);
        header_64_.e_shoff = read_data<uint64_t>(
            raw_data_.data(), offsetof(Header64, e_shoff), target_endian_);
        header_64_.e_flags = read_data<uint32_t>(
            raw_data_.data(), offsetof(Header64, e_flags), target_endian_);
        header_64_.e_ehsize = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header64, e_ehsize), target_endian_);
        header_64_.e_phentsize = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header64, e_phentsize), target_endian_);
        header_64_.e_phnum = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header64, e_phnum), target_endian_);
        header_64_.e_shentsize = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header64, e_shentsize), target_endian_);
        header_64_.e_shnum = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header64, e_shnum), target_endian_);
        header_64_.e_shstrndx = read_data<uint16_t>(
            raw_data_.data(), offsetof(Header64, e_shstrndx), target_endian_);
    }
}

void ELF::parse_program_headers() {
    if (class_type_ == ClassType::ELFCLASS32) {
        uint32_t ph_offset = header_32_.e_phoff;
        uint16_t ph_entsize = header_32_.e_phentsize;
        uint16_t ph_num = header_32_.e_phnum;

        program_headers_32_.reserve(ph_num);

        for (uint16_t i = 0; i < ph_num; i++) {
            uint32_t offset = ph_offset + i * ph_entsize;
            if (offset + ph_entsize > raw_data_.size()) {
                throw std::runtime_error("Program header outside file bounds");
            }

            ProgramHeader32 ph;
            ph.p_type = read_data<uint32_t, SegmentType>(
                raw_data_.data(), offset, target_endian_);
            ph.p_offset = read_data<uint32_t>(raw_data_.data(), offset + 4,
                                              target_endian_);
            ph.p_vaddr = read_data<uint32_t>(raw_data_.data(), offset + 8,
                                             target_endian_);
            ph.p_paddr = read_data<uint32_t>(raw_data_.data(), offset + 12,
                                             target_endian_);
            ph.p_filesz = read_data<uint32_t>(raw_data_.data(), offset + 16,
                                              target_endian_);
            ph.p_memsz = read_data<uint32_t>(raw_data_.data(), offset + 20,
                                             target_endian_);
            ph.p_flags = read_data<uint32_t>(raw_data_.data(), offset + 24,
                                             target_endian_);
            ph.p_align = read_data<uint32_t>(raw_data_.data(), offset + 28,
                                             target_endian_);

            program_headers_32_.push_back(ph);
        }
    } else {
        uint64_t ph_offset = header_64_.e_phoff;
        uint16_t ph_entsize = header_64_.e_phentsize;
        uint16_t ph_num = header_64_.e_phnum;

        program_headers_64_.reserve(ph_num);

        for (uint16_t i = 0; i < ph_num; i++) {
            uint64_t offset = ph_offset + i * ph_entsize;
            if (offset + ph_entsize > raw_data_.size()) {
                throw std::runtime_error("Program header outside file bounds");
            }

            ProgramHeader64 ph;
            ph.p_type = read_data<uint32_t, SegmentType>(
                raw_data_.data(), offset, target_endian_);
            ph.p_flags = read_data<uint32_t>(raw_data_.data(), offset + 4,
                                             target_endian_);
            ph.p_offset = read_data<uint64_t>(raw_data_.data(), offset + 8,
                                              target_endian_);
            ph.p_vaddr = read_data<uint64_t>(raw_data_.data(), offset + 16,
                                             target_endian_);
            ph.p_paddr = read_data<uint64_t>(raw_data_.data(), offset + 24,
                                             target_endian_);
            ph.p_filesz = read_data<uint64_t>(raw_data_.data(), offset + 32,
                                              target_endian_);
            ph.p_memsz = read_data<uint64_t>(raw_data_.data(), offset + 40,
                                             target_endian_);
            ph.p_align = read_data<uint64_t>(raw_data_.data(), offset + 48,
                                             target_endian_);

            program_headers_64_.push_back(ph);
        }
    }
}

void ELF::parse_section_headers() {
    if (class_type_ == ClassType::ELFCLASS32) {
        uint32_t sh_offset = header_32_.e_shoff;
        uint16_t sh_entsize = header_32_.e_shentsize;
        uint16_t sh_num = header_32_.e_shnum;

        section_headers_32_.reserve(sh_num);

        for (uint16_t i = 0; i < sh_num; i++) {
            uint32_t offset = sh_offset + i * sh_entsize;
            if (offset + sh_entsize > raw_data_.size()) {
                throw std::runtime_error("Section header outside file bounds");
            }

            SectionHeader32 sh;
            sh.sh_name =
                read_data<uint32_t>(raw_data_.data(), offset, target_endian_);
            sh.sh_type = read_data<uint32_t, SectionType>(
                raw_data_.data(), offset + 4, target_endian_);
            sh.sh_flags = read_data<uint32_t>(raw_data_.data(), offset + 8,
                                              target_endian_);
            sh.sh_addr = read_data<uint32_t>(raw_data_.data(), offset + 12,
                                             target_endian_);
            sh.sh_offset = read_data<uint32_t>(raw_data_.data(), offset + 16,
                                               target_endian_);
            sh.sh_size = read_data<uint32_t>(raw_data_.data(), offset + 20,
                                             target_endian_);
            sh.sh_link = read_data<uint32_t>(raw_data_.data(), offset + 24,
                                             target_endian_);
            sh.sh_info = read_data<uint32_t>(raw_data_.data(), offset + 28,
                                             target_endian_);
            sh.sh_addralign = read_data<uint32_t>(raw_data_.data(), offset + 32,
                                                  target_endian_);
            sh.sh_entsize = read_data<uint32_t>(raw_data_.data(), offset + 36,
                                                target_endian_);

            section_headers_32_.push_back(sh);
        }
    } else {
        uint64_t sh_offset = header_64_.e_shoff;
        uint16_t sh_entsize = header_64_.e_shentsize;
        uint16_t sh_num = header_64_.e_shnum;

        section_headers_64_.reserve(sh_num);

        for (uint16_t i = 0; i < sh_num; i++) {
            uint64_t offset = sh_offset + i * sh_entsize;
            if (offset + sh_entsize > raw_data_.size()) {
                throw std::runtime_error("Section header outside file bounds");
            }

            SectionHeader64 sh;
            sh.sh_name =
                read_data<uint32_t>(raw_data_.data(), offset, target_endian_);
            sh.sh_type = read_data<uint32_t, SectionType>(
                raw_data_.data(), offset + 4, target_endian_);
            sh.sh_flags = read_data<uint64_t>(raw_data_.data(), offset + 8,
                                              target_endian_);
            sh.sh_addr = read_data<uint64_t>(raw_data_.data(), offset + 16,
                                             target_endian_);
            sh.sh_offset = read_data<uint64_t>(raw_data_.data(), offset + 24,
                                               target_endian_);
            sh.sh_size = read_data<uint64_t>(raw_data_.data(), offset + 32,
                                             target_endian_);
            sh.sh_link = read_data<uint32_t>(raw_data_.data(), offset + 40,
                                             target_endian_);
            sh.sh_info = read_data<uint32_t>(raw_data_.data(), offset + 44,
                                             target_endian_);
            sh.sh_addralign = read_data<uint64_t>(raw_data_.data(), offset + 48,
                                                  target_endian_);
            sh.sh_entsize = read_data<uint64_t>(raw_data_.data(), offset + 56,
                                                target_endian_);

            section_headers_64_.push_back(sh);
        }
    }
}

void ELF::load_section_names_string_table() {
    uint16_t shstrndx;
    uint64_t sh_offset;
    uint64_t sh_size;

    if (class_type_ == ClassType::ELFCLASS32) {
        shstrndx = header_32_.e_shstrndx;
        if (shstrndx == SHN_UNDEF) return;           // No string table
        if (shstrndx >= header_32_.e_shnum) return;  // Invalid index

        if (section_headers_32_.empty() ||
            shstrndx >= section_headers_32_.size())
            return;

        sh_offset = section_headers_32_[shstrndx].sh_offset;
        sh_size = section_headers_32_[shstrndx].sh_size;
    } else {
        shstrndx = header_64_.e_shstrndx;
        if (shstrndx == SHN_UNDEF) return;           // No string table
        if (shstrndx >= header_64_.e_shnum) return;  // Invalid index

        if (section_headers_64_.empty() ||
            shstrndx >= section_headers_64_.size())
            return;

        sh_offset = section_headers_64_[shstrndx].sh_offset;
        sh_size = section_headers_64_[shstrndx].sh_size;
    }

    if (sh_offset + sh_size > raw_data_.size()) {
        return;  // String table out of bounds
    }

    section_names_string_table_.resize(sh_size);
    std::memcpy(section_names_string_table_.data(),
                raw_data_.data() + sh_offset, sh_size);
}

std::string ELF::get_section_name(const SectionHeader32& sh) const {
    if (section_names_string_table_.empty() ||
        sh.sh_name >= section_names_string_table_.size()) {
        std::stringstream ss;
        ss << "[index: 0x" << std::hex << sh.sh_name << "]";
        return ss.str();
    }
    if (section_names_string_table_[sh.sh_name] == 0) {
        return "[empty name]";
    }
    return &section_names_string_table_[sh.sh_name];
}

std::string ELF::get_section_name(const SectionHeader64& sh) const {
    if (section_names_string_table_.empty() ||
        sh.sh_name >= section_names_string_table_.size()) {
        std::stringstream ss;
        ss << "[index: 0x" << std::hex << sh.sh_name << "]";
        return ss.str();
    }
    if (section_names_string_table_[sh.sh_name] == 0) {
        return "[empty name]";
    }
    return &section_names_string_table_[sh.sh_name];
}
