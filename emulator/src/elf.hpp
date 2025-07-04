#pragma once
#include <bit>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;

template <typename T, typename U = T>
U read_data(const uint8_t* data, size_t offset, std::endian endianness);

class ELF {
   public:
    /**
     * @brief ELF class type (32-bit or 64-bit).
     */
    enum class ClassType : uint8_t {
        ELFCLASSNONE = 0,  // Invalid class
        ELFCLASS32 = 1,    // 32-bit objects
        ELFCLASS64 = 2     // 64-bit objects
    };

    /**
     * @brief Data encoding type.
     */
    enum class DataEncoding : uint8_t {
        ELFDATANONE = 0,  // Invalid data encoding
        ELFDATA2LSB = 1,  // Little-endian
        ELFDATA2MSB = 2   // Big-endian
    };

    /**
     * @brief ELF file type.
     */
    enum class FileType : uint16_t {
        ET_NONE = 0,  // Unknown type
        ET_REL = 1,   // Relocatable file
        ET_EXEC = 2,  // Executable file
        ET_DYN = 3,   // Shared object file
        ET_CORE = 4   // Core file
    };

    /**
     * @brief Machine architecture type.
     */
    enum class MachineType : uint16_t {
        EM_NONE = 0,                  // No machine
        EM_M32 = 1,                   // AT&T WE 32100
        EM_SPARC = 2,                 // SPARC
        EM_386 = 3,                   // Intel 80386
        EM_68K = 4,                   // Motorola 68000
        EM_88K = 5,                   // Motorola 88000
        EM_IAMCU = 6,                 // Intel MCU
        EM_860 = 7,                   // Intel 80860
        EM_MIPS = 8,                  // MIPS I Architecture
        EM_S370 = 9,                  // IBM System/370 Processor
        EM_MIPS_RS3_LE = 10,            // MIPS RS3000 Little-endian
        // reserved  11-14   Reserved for future use
        EM_PARISC = 15,               // Hewlett-Packard PA-RISC
        // reserved  16      Reserved for future use
        EM_VPP500 = 17,               // Fujitsu VPP500
        EM_SPARC32PLUS = 18,          // Enhanced instruction set SPARC
        EM_960 = 19,                  // Intel 80960
        EM_PPC = 20,                  // PowerPC
        EM_PPC64 = 21,                // 64-bit PowerPC
        EM_S390 = 22,                 // IBM System/390 Processor
        EM_SPU = 23,                  // IBM SPU/SPC
        // reserved  24-35   Reserved for future use
        EM_V800 = 36,                 // NEC V800
        EM_FR20 = 37,                 // Fujitsu FR20
        EM_RH32 = 38,                 // TRW RH-32
        EM_RCE = 39,                  // Motorola RCE
        EM_ARM = 40,                  // ARM 32-bit architecture (AARCH32)
        EM_ALPHA = 41,                // Digital Alpha
        EM_SH = 42,                   // Hitachi SH
        EM_SPARCV9 = 43,              // SPARC Version 9
        EM_TRICORE = 44,              // Siemens TriCore embedded processor
        EM_ARC = 45,                  // Argonaut RISC Core, Argonaut Technologies Inc.
        EM_H8_300 = 46,               // Hitachi H8/300
        EM_H8_300H = 47,              // Hitachi H8/300H
        EM_H8S = 48,                  // Hitachi H8S
        EM_H8_500 = 49,               // Hitachi H8/500
        EM_IA_64 = 50,                // Intel IA-64 processor architecture
        EM_MIPS_X = 51,               // Stanford MIPS-X
        EM_COLDFIRE = 52,             // Motorola ColdFire
        EM_68HC12 = 53,               // Motorola M68HC12
        EM_MMA = 54,                  // Fujitsu MMA Multimedia Accelerator
        EM_PCP = 55,                  // Siemens PCP
        EM_NCPU = 56,                 // Sony nCPU embedded RISC processor
        EM_NDR1 = 57,                 // Denso NDR1 microprocessor
        EM_STARCORE = 58,             // Motorola Star*Core processor
        EM_ME16 = 59,                 // Toyota ME16 processor
        EM_ST100 = 60,                // STMicroelectronics ST100 processor
        EM_TINYJ = 61,                // Advanced Logic Corp. TinyJ embedded processor family
        EM_X86_64 = 62,               // AMD x86-64 architecture
        EM_PDSP = 63,                 // Sony DSP Processor
        EM_PDP10 = 64,                // Digital Equipment Corp. PDP-10
        EM_PDP11 = 65,                // Digital Equipment Corp. PDP-11
        EM_FX66 = 66,                 // Siemens FX66 microcontroller
        EM_ST9PLUS = 67,              // STMicroelectronics ST9+ 8/16 bit microcontroller
        EM_ST7 = 68,                  // STMicroelectronics ST7 8-bit microcontroller
        EM_68HC16 = 69,               // Motorola MC68HC16 Microcontroller
        EM_68HC11 = 70,               // Motorola MC68HC11 Microcontroller
        EM_68HC08 = 71,               // Motorola MC68HC08 Microcontroller
        EM_68HC05 = 72,               // Motorola MC68HC05 Microcontroller
        EM_SVX = 73,                  // Silicon Graphics SVx
        EM_ST19 = 74,                 // STMicroelectronics ST19 8-bit microcontroller
        EM_VAX = 75,                  // Digital VAX
        EM_CRIS = 76,                 // Axis Communications 32-bit embedded processor
        EM_JAVELIN = 77,              // Infineon Technologies 32-bit embedded processor
        EM_FIREPATH = 78,             // Element 14 64-bit DSP Processor
        EM_ZSP = 79,                  // LSI Logic 16-bit DSP Processor
        EM_MMIX = 80,                 // Donald Knuth's educational 64-bit processor
        EM_HUANY = 81,                // Harvard University machine-independent object files
        EM_PRISM = 82,                // SiTera Prism
        EM_AVR = 83,                  // Atmel AVR 8-bit microcontroller
        EM_FR30 = 84,                 // Fujitsu FR30
        EM_D10V = 85,                 // Mitsubishi D10V
        EM_D30V = 86,                 // Mitsubishi D30V
        EM_V850 = 87,                 // NEC v850
        EM_M32R = 88,                 // Mitsubishi M32R
        EM_MN10300 = 89,              // Matsushita MN10300
        EM_MN10200 = 90,              // Matsushita MN10200
        EM_PJ = 91,                   // picoJava
        EM_OPENRISC = 92,             // OpenRISC 32-bit embedded processor
        EM_ARC_COMPACT = 93,          // ARC International ARCompact processor (old spelling/synonym: EM_ARC_A5)
        EM_XTENSA = 94,               // Tensilica Xtensa Architecture
        EM_VIDEOCORE = 95,            // Alphamosaic VideoCore processor
        EM_TMM_GPP = 96,              // Thompson Multimedia General Purpose Processor
        EM_NS32K = 97,                // National Semiconductor 32000 series
        EM_TPC = 98,                  // Tenor Network TPC processor
        EM_SNP1K = 99,                // Trebia SNP 1000 processor
        EM_ST200 = 100,               // STMicroelectronics (www.st.com) ST200 microcontroller
        EM_IP2K = 101,                // Ubicom IP2xxx microcontroller family
        EM_MAX = 102,                 // MAX Processor
        EM_CR = 103,                  // National Semiconductor CompactRISC microprocessor
        EM_F2MC16 = 104,              // Fujitsu F2MC16
        EM_MSP430 = 105,              // Texas Instruments embedded microcontroller msp430
        EM_BLACKFIN = 106,            // Analog Devices Blackfin (DSP) processor
        EM_SE_C33 = 107,              // S1C33 Family of Seiko Epson processors
        EM_SEP = 108,                 // Sharp embedded microprocessor
        EM_ARCA = 109,                // Arca RISC Microprocessor
        EM_UNICORE = 110,             // Microprocessor series from PKU-Unity Ltd. and MPRC of Peking University
        EM_EXCESS = 111,              // eXcess: 16/32/64-bit configurable embedded CPU
        EM_DXP = 112,                 // Icera Semiconductor Inc. Deep Execution Processor
        EM_ALTERA_NIOS2 = 113,        // Altera Nios II soft-core processor
        EM_CRX = 114,                 // National Semiconductor CompactRISC CRX microprocessor
        EM_XGATE = 115,               // Motorola XGATE embedded processor
        EM_C166 = 116,                // Infineon C16x/XC16x processor
        EM_M16C = 117,                // Renesas M16C series microprocessors
        EM_DSPIC30F = 118,            // Microchip Technology dsPIC30F Digital Signal Controller
        EM_CE = 119,                  // Freescale Communication Engine RISC core
        EM_M32C = 120,                // Renesas M32C series microprocessors
        // reserved  121-130 Reserved for future use
        EM_TSK3000 = 131,             // Altium TSK3000 core
        EM_RS08 = 132,                // Freescale RS08 embedded processor
        EM_SHARC = 133,               // Analog Devices SHARC family of 32-bit DSP processors
        EM_ECOG2 = 134,               // Cyan Technology eCOG2 microprocessor
        EM_SCORE7 = 135,              // Sunplus S+core7 RISC processor
        EM_DSP24 = 136,               // New Japan Radio (NJR) 24-bit DSP Processor
        EM_VIDEOCORE3 = 137,          // Broadcom VideoCore III processor
        EM_LATTICEMICO32 = 138,       // RISC processor for Lattice FPGA architecture
        EM_SE_C17 = 139,              // Seiko Epson C17 family
        EM_TI_C6000 = 140,            // The Texas Instruments TMS320C6000 DSP family
        EM_TI_C2000 = 141,            // The Texas Instruments TMS320C2000 DSP family
        EM_TI_C5500 = 142,            // The Texas Instruments TMS320C55x DSP family
        EM_TI_ARP32 = 143,            // Texas Instruments Application Specific RISC Processor, 32bit fetch
        EM_TI_PRU = 144,              // Texas Instruments Programmable Realtime Unit
        // reserved  145-159 Reserved for future use
        EM_MMDSP_PLUS = 160,          // STMicroelectronics 64bit VLIW Data Signal Processor
        EM_CYPRESS_M8C = 161,         // Cypress M8C microprocessor
        EM_R32C = 162,                // Renesas R32C series microprocessors
        EM_TRIMEDIA = 163,            // NXP Semiconductors TriMedia architecture family
        EM_QDSP6 = 164,               // QUALCOMM DSP6 Processor
        EM_8051 = 165,                // Intel 8051 and variants
        EM_STXP7X = 166,              // STMicroelectronics STxP7x family of configurable and extensible RISC processors
        EM_NDS32 = 167,               // Andes Technology compact code size embedded RISC processor family
        EM_ECOG1 = 168,               // Cyan Technology eCOG1X family
        EM_ECOG1X = 168,              // Cyan Technology eCOG1X family
        EM_MAXQ30 = 169,              // Dallas Semiconductor MAXQ30 Core Micro-controllers
        EM_XIMO16 = 170,              // New Japan Radio (NJR) 16-bit DSP Processor
        EM_MANIK = 171,               // M2000 Reconfigurable RISC Microprocessor
        EM_CRAYNV2 = 172,             // Cray Inc. NV2 vector architecture
        EM_RX = 173,                  // Renesas RX family
        EM_METAG = 174,               // Imagination Technologies META processor architecture
        EM_MCST_ELBRUS = 175,         // MCST Elbrus general purpose hardware architecture
        EM_ECOG16 = 176,              // Cyan Technology eCOG16 family
        EM_CR16 = 177,                // National Semiconductor CompactRISC CR16 16-bit microprocessor
        EM_ETPU = 178,                // Freescale Extended Time Processing Unit
        EM_SLE9X = 179,               // Infineon Technologies SLE9X core
        EM_L10M = 180,                // Intel L10M
        EM_K10M = 181,                // Intel K10M
        // reserved  182     Reserved for future Intel use
        EM_AARCH64 = 183,             // ARM 64-bit architecture (AARCH64)
        // reserved  184     Reserved for future ARM use
        EM_AVR32 = 185,               // Atmel Corporation 32-bit microprocessor family
        EM_STM8 = 186,                // STMicroeletronics STM8 8-bit microcontroller
        EM_TILE64 = 187,              // Tilera TILE64 multicore architecture family
        EM_TILEPRO = 188,             // Tilera TILEPro multicore architecture family
        EM_MICROBLAZE = 189,          // Xilinx MicroBlaze 32-bit RISC soft processor core
        EM_CUDA = 190,                // NVIDIA CUDA architecture
        EM_TILEGX = 191,              // Tilera TILE-Gx multicore architecture family
        EM_CLOUDSHIELD = 192,         // CloudShield architecture family
        EM_COREA_1ST = 193,           // KIPO-KAIST Core-A 1st generation processor family
        EM_COREA_2ND = 194,           // KIPO-KAIST Core-A 2nd generation processor family
        EM_ARC_COMPACT2 = 195,        // Synopsys ARCompact V2
        EM_OPEN8 = 196,               // Open8 8-bit RISC soft processor core
        EM_RL78 = 197,                // Renesas RL78 family
        EM_VIDEOCORE5 = 198,          // Broadcom VideoCore V processor
        EM_78KOR = 199,               // Renesas 78KOR family
        EM_56800EX = 200,             // Freescale 56800EX Digital Signal Controller (DSC)
        EM_BA1 = 201,                 // Beyond BA1 CPU architecture
        EM_BA2 = 202,                 // Beyond BA2 CPU architecture
        EM_XCORE = 203,               // XMOS xCORE processor family
        EM_MCHP_PIC = 204,            // Microchip 8-bit PIC(r) family
        EM_INTEL205 = 205,            // Reserved by Intel
        EM_INTEL206 = 206,            // Reserved by Intel
        EM_INTEL207 = 207,            // Reserved by Intel
        EM_INTEL208 = 208,            // Reserved by Intel
        EM_INTEL209 = 209,            // Reserved by Intel
        EM_KM32 = 210,                // KM211 KM32 32-bit processor
        EM_KMX32 = 211,               // KM211 KMX32 32-bit processor
        EM_KMX16 = 212,               // KM211 KMX16 16-bit processor
        EM_KMX8 = 213,                // KM211 KMX8 8-bit processor
        EM_KVARC = 214,               // KM211 KVARC processor
        EM_CDP = 215,                 // Paneve CDP architecture family
        EM_COGE = 216,                // Cognitive Smart Memory Processor
        EM_COOL = 217,                // Bluechip Systems CoolEngine
        EM_NORC = 218,                // Nanoradio Optimized RISC
        EM_CSR_KALIMBA = 219,         // CSR Kalimba architecture family
        EM_Z80 = 220,                 // Zilog Z80
        EM_VISIUM = 221,              // Controls and Data Services VISIUMcore processor
        EM_FT32 = 222,                // FTDI Chip FT32 high performance 32-bit RISC architecture
        EM_MOXIE = 223,               // Moxie processor family
        EM_AMDGPU = 224,              // AMD GPU architecture
        // unknown/reserve?  225 - 242
        EM_RISCV = 243,               // RISC-V
        EM_BPF = 247,                 // Linux BPF - in-kernel virtual machine
        EM_CSKY = 252,                // C-SKY
        EM_LOONGARCH = 258,           // LoongArch
        EM_FRV = 0x5441,              // Fujitsu FR-V
    };

    /**
     * @brief Segment type.
     */
    enum class SegmentType : uint32_t {
        PT_NULL = 0,             // Program header table entry unused
        PT_LOAD = 1,             // Loadable segment
        PT_DYNAMIC = 2,          // Dynamic linking information
        PT_INTERP = 3,           // Program interpreter
        PT_NOTE = 4,             // Auxiliary information
        PT_SHLIB = 5,            // Reserved
        PT_PHDR = 6,             // Entry for header table itself
        PT_TLS = 7,              // Thread-local storage segment
        PT_LOOS = 0x60000000,    // Operating system-specific
        PT_HIOS = 0x6fffffff,    // Operating system-specific
        PT_LOPROC = 0x70000000,  // Processor-specific
        PT_HIPROC = 0x7fffffff   // Processor-specific
    };

    static std::string segment_type_to_string(SegmentType type);

    /**
     * @brief Segment flags.
     */
    enum class SegmentFlags : uint32_t {
        PF_X = 0x1,               // Execute/Instruction
        PF_W = 0x2,               // Write
        PF_R = 0x4,               // Read
        PF_MASKOS = 0x00ff0000,   // Operating system-specific
        PF_MASKPROC = 0xff000000  // Processor-specific
    };

    static std::string segment_flags_to_string(uint32_t flags);

    /**
     * @brief Section type.
     */
    enum class SectionType : uint32_t {
        SHT_NULL = 0,            // Section header table entry unused
        SHT_PROGBITS = 1,        // Program data
        SHT_SYMTAB = 2,          // Symbol table
        SHT_STRTAB = 3,          // String table
        SHT_RELA = 4,            // Relocation entries with addends
        SHT_HASH = 5,            // Symbol hash table
        SHT_DYNAMIC = 6,         // Dynamic linking information
        SHT_NOTE = 7,            // Notes
        SHT_NOBITS = 8,          // Program space with no data (bss)
        SHT_REL = 9,             // Relocation entries, no addends
        SHT_SHLIB = 10,          // Reserved
        SHT_DYNSYM = 11,         // Dynamic symbol table
        SHT_INIT_ARRAY = 14,     // Array of constructors
        SHT_FINI_ARRAY = 15,     // Array of destructors
        SHT_PREINIT_ARRAY = 16,  // Array of pre-constructors
        SHT_GROUP = 17,          // Section group
        SHT_SYMTAB_SHNDX = 18,   // Extended section indices
        SHT_NUM = 19,  // Number of section header types - not a section
        SHT_LOOS = 0x60000000,    // Operating system-specific
        SHT_HIOS = 0x6fffffff,    // Operating system-specific
        SHT_LOPROC = 0x70000000,  // Processor-specific
        SHT_HIPROC = 0x7fffffff,  // Processor-specific
        SHT_LOUSER = 0x80000000,  // Application programs
        SHT_HIUSER = 0xffffffff   // Application programs
    };

    static std::string section_type_to_string(SectionType type);

    /**
     * @brief Section flags.
     */
    enum class SectionFlags : uint64_t {
        SHF_WRITE = 0x1,        // Writable
        SHF_ALLOC = 0x2,        // Occupies memory during execution
        SHF_EXECINSTR = 0x4,    // Executable
        SHF_MERGE = 0x10,       // Might be merged
        SHF_STRINGS = 0x20,     // Contains null-terminated strings
        SHF_INFO_LINK = 0x40,   // `sh_info' contains section index
        SHF_LINK_ORDER = 0x80,  // Preserve order after combining
        SHF_OS_NONCONFORMING =
            0x100,          // Non-standard OS specific handling required
        SHF_GROUP = 0x200,  // Section is member of a group
        SHF_TLS = 0x400,    // Section holds thread-local storage
        SHF_MASKOS = 0x0ff00000,    // Operating system-specific
        SHF_MASKPROC = 0xf0000000,  // Processor-specific
        SHF_ORDERED = 0x4000000,    // Special ordering requirement (Solaris)
        SHF_EXCLUDE =
            0x8000000  // Section is excluded unless referenced (GNU)
    };

    /**
     * @brief Special section index.
     */
    enum SpecialSectionIndex : uint16_t {
        SHN_UNDEF = 0,
        SHN_LORESERVE = 0xff00,
        SHN_LOPROC = 0xff00,
        SHN_HIPROC = 0xff1f,
        SHN_ABS = 0xfff1,
        SHN_COMMON = 0xfff2,
        SHN_HIRESERVE = 0xffff
    };

    static std::string section_flags_to_string(uint64_t flags);

    /**
     * @brief 32-bit ELF header structure.
     */
    struct Header32 {
        unsigned char e_ident[16];  // Magic number and other info
        FileType e_type;            // Object file type
        MachineType e_machine;      // Architecture
        uint32_t e_version;         // Object file version
        uint32_t e_entry;           // Entry point virtual address
        uint32_t e_phoff;           // Program header table file offset
        uint32_t e_shoff;           // Section header table file offset
        uint32_t e_flags;           // Processor-specific flags
        uint16_t e_ehsize;          // ELF header size in bytes
        uint16_t e_phentsize;       // Program header table entry size
        uint16_t e_phnum;           // Program header table entry count
        uint16_t e_shentsize;       // Section header table entry size
        uint16_t e_shnum;           // Section header table entry count
        uint16_t e_shstrndx;        // Section header string table index
    };

    /**
     * @brief 64-bit ELF header structure.
     */
    struct Header64 {
        unsigned char e_ident[16];  // Magic number and other info
        FileType e_type;            // Object file type
        MachineType e_machine;      // Architecture
        uint32_t e_version;         // Object file version
        uint64_t e_entry;           // Entry point virtual address
        uint64_t e_phoff;           // Program header table file offset
        uint64_t e_shoff;           // Section header table file offset
        uint32_t e_flags;           // Processor-specific flags
        uint16_t e_ehsize;          // ELF header size in bytes
        uint16_t e_phentsize;       // Program header table entry size
        uint16_t e_phnum;           // Program header table entry count
        uint16_t e_shentsize;       // Section header table entry size
        uint16_t e_shnum;           // Section header table entry count
        uint16_t e_shstrndx;        // Section header string table index
    };

    /**
     * @brief 32-bit Program header structure.
     */
    struct ProgramHeader32 {
        SegmentType p_type;  // Segment type
        uint32_t p_offset;   // Segment file offset
        uint32_t p_vaddr;    // Segment virtual address
        uint32_t p_paddr;    // Segment physical address
        uint32_t p_filesz;   // Segment size in file
        uint32_t p_memsz;    // Segment size in memory
        uint32_t p_flags;    // Segment flags
        uint32_t p_align;    // Segment alignment

        std::string get_type_string() const;
        std::string get_flags_string() const;
    };

    /**
     * @brief 64-bit Program header structure.
     */
    struct ProgramHeader64 {
        SegmentType p_type;  // Segment type
        uint32_t p_flags;    // Segment flags
        uint64_t p_offset;   // Segment file offset
        uint64_t p_vaddr;    // Segment virtual address
        uint64_t p_paddr;    // Segment physical address
        uint64_t p_filesz;   // Segment size in file
        uint64_t p_memsz;    // Segment size in memory
        uint64_t p_align;    // Segment alignment

        std::string get_type_string() const;
        std::string get_flags_string() const;
    };

    /**
     * @brief 32-bit Section header structure.
     */
    struct SectionHeader32 {
        uint32_t sh_name;       // Section name (index into string table)
        SectionType sh_type;    // Section type
        uint32_t sh_flags;      // Section flags
        uint32_t sh_addr;       // Section virtual address at execution
        uint32_t sh_offset;     // Section file offset
        uint32_t sh_size;       // Section size in bytes
        uint32_t sh_link;       // Link to another section
        uint32_t sh_info;       // Additional section information
        uint32_t sh_addralign;  // Section alignment
        uint32_t sh_entsize;    // Entry size if section holds table

        std::string get_type_string() const;
        std::string get_flags_string() const;
    };

    /**
     * @brief 64-bit Section header structure.
     */
    struct SectionHeader64 {
        uint32_t sh_name;       // Section name (index into string table)
        SectionType sh_type;    // Section type
        uint64_t sh_flags;      // Section flags
        uint64_t sh_addr;       // Section virtual address at execution
        uint64_t sh_offset;     // Section file offset
        uint64_t sh_size;       // Section size in bytes
        uint32_t sh_link;       // Link to another section
        uint32_t sh_info;       // Additional section information
        uint64_t sh_addralign;  // Section alignment
        uint64_t sh_entsize;    // Entry size if section holds table

        std::string get_type_string() const;
        std::string get_flags_string() const;
    };

    ELF() = default;
    ~ELF() = default;

    static ELF from_file(const fs::path& file_path);
    static ELF from_raw(const std::vector<uint8_t>& raw_data);

    const Header32& get_header_32() const;
    const Header64& get_header_64() const;

    ClassType get_class_type() const;
    MachineType get_machine_type() const;

    const std::vector<ProgramHeader32>& get_program_headers_32() const;
    const std::vector<ProgramHeader64>& get_program_headers_64() const;

    const std::vector<SectionHeader32>& get_section_headers_32() const;
    const std::vector<SectionHeader64>& get_section_headers_64() const;

    void print_program_header(const ProgramHeader32& ph) const;
    void print_program_header(const ProgramHeader64& ph) const;

    void print_section_header(const SectionHeader32& sh) const;
    void print_section_header(const SectionHeader64& sh) const;

    void print_program_headers() const;
    void print_section_headers() const;
    void print_header() const;

    std::string get_section_name(const SectionHeader32& sh) const;
    std::string get_section_name(const SectionHeader64& sh) const;

   private:
    std::vector<uint8_t> raw_data_;
    Header64 header_64_;
    Header32 header_32_;
    std::vector<ProgramHeader32> program_headers_32_;
    std::vector<ProgramHeader64> program_headers_64_;
    std::vector<SectionHeader32> section_headers_32_;
    std::vector<SectionHeader64> section_headers_64_;
    ClassType class_type_;
    DataEncoding data_encoding_;
    std::endian target_endian_;
    std::vector<char> section_names_string_table_;

    void parse_header();
    void parse_program_headers();
    void parse_section_headers();
    void load_section_names_string_table();
};
