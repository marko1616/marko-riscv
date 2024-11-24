# Tools
GCC = riscv64-unknown-elf-gcc
OBJDUMP = riscv64-unknown-elf-objdump
OBJCOPY = riscv64-unknown-elf-objcopy

SRC = boot.s
LD_SCRIPT = boot.ld
ELF = boot.elf
BIN = boot.bin
DISASM = boot.dump

CFLAGS = -march=rv64i_zicsr -mabi=lp64 -nostdlib -nostartfiles
LDFLAGS = -T $(LD_SCRIPT)

all: $(BIN) $(DISASM)

$(ELF): $(SRC) $(LD_SCRIPT)
	$(GCC) $(CFLAGS) -o $@ -Wl,$(LDFLAGS) $<

$(BIN): $(ELF)
	$(OBJCOPY) -O binary $< $@

$(DISASM): $(ELF)
	$(OBJDUMP) -D $< > $@

clean:
	rm -f $(ELF) $(BIN) $(DISASM)
