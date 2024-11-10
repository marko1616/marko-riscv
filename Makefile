# Tools
AS        = riscv64-unknown-elf-as
OBJCOPY   = riscv64-unknown-elf-objcopy
XXD       = xxd
FOLD      = fold
AWK       = awk
TR        = tr

# Directories and Files
ASM_TEST_DIR = tests/asmtst
CAPSTONE_DIR = $(shell realpath libs/capstone)
ASM_SRCS     = $(shell find $(ASM_TEST_DIR) -name '*.S')
OBJS         = $(ASM_SRCS:.S=.o)
BINS         = $(OBJS:.o=.bin)
HEXES        = $(BINS:.bin=.hex)

# Targets
.PHONY: compile emu gen-tests clean

compile:
	mill -i markorv.runMain markorv.MarkoRvCore

emu:
	mill -i markorv.runMain markorv.MarkoRvCore
	verilator --cc generated/MarkoRvCore.sv --exe tests/emulator/stimulus.cpp --build \
		-CFLAGS  "-g -I$(CAPSTONE_DIR)/include" \
		-LDFLAGS "-L$(CAPSTONE_DIR) -lcapstone"

gen-tests: $(HEXES)

# Rules
%.o: %.S
	$(AS) -o $@ $<

%.bin: %.o
	$(OBJCOPY) -O binary $< $@

%.hex: %.bin
	$(XXD) -p $< | $(TR) -d "\n" | $(FOLD) -w 8 | $(AWK) '{print substr($$0, 7, 2) substr($$0, 5, 2) substr($$0, 3, 2) substr($$0, 1, 2)}' > $@

clean:
	rm -f $(OBJS) $(BINS) $(HEXES)
