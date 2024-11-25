# Tools
GCC = riscv64-unknown-elf-gcc
OBJDUMP = riscv64-unknown-elf-objdump
OBJCOPY = riscv64-unknown-elf-objcopy
DTC = dtc
AS = riscv64-unknown-elf-as

# Source files
SRC = boot.s
LD_SCRIPT = boot.ld
ELF = boot.elf
BIN = boot.bin
DISASM = boot.dump

# Device Tree
DTS = device.dts
DTB = device.dtb
DTB_OBJ = device.o

# Compiler flags
CFLAGS = -march=rv64ia_zicsr -mabi=lp64 -nostdlib -nostartfiles
LDFLAGS = -T $(LD_SCRIPT)

all: $(BIN) $(DISASM)

# Compile assembly and link with DTB
$(ELF): $(SRC) $(LD_SCRIPT) $(DTB_OBJ)
	$(GCC) $(CFLAGS) -o $@ -Wl,$(LDFLAGS) $(SRC) $(DTB_OBJ)

# Convert ELF to binary
$(BIN): $(ELF)
	$(OBJCOPY) -O binary $< $@

# Generate disassembly
$(DISASM): $(ELF)
	$(OBJDUMP) -D $< > $@

# Compile DTS to DTB
$(DTB): $(DTS)
	$(DTC) -I dts -O dtb -o $@ $<

# Convert DTB to object file
$(DTB_OBJ): $(DTB)
	$(OBJCOPY) -I binary -O elf64-littleriscv -B riscv $< $@

clean:
	rm -f $(ELF) $(BIN) $(DISASM) $(DTB) $(DTB_OBJ)
