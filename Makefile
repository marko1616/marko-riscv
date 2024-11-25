# Tools
GCC = riscv64-unknown-elf-gcc
OBJDUMP = riscv64-unknown-elf-objdump
OBJCOPY = riscv64-unknown-elf-objcopy

# Directories and Files
ASM_TEST_DIR = tests/asmtst/src
CAPSTONE_DIR = $(shell realpath libs/capstone)
ASM_SRCS     = $(shell find $(ASM_TEST_DIR) -name '*.S')
OBJS         = $(ASM_SRCS:.S=.o)
ELFS         = $(OBJS:.o=.elf)
BINS         = $(OBJS:.o=.bin)

LD_SCRIPT = tests/asmtst/general.ld
CFLAGS = -march=rv64ia_zicsr -mabi=lp64 -nostdlib -nostartfiles
LDFLAGS = -T $(LD_SCRIPT)

# Targets
.PHONY: init compile gen-tests gen-rom clean

init:
	git submodule update --init --recursive
	$(MAKE) -C $(CAPSTONE_DIR) -j $(nproc)

compile:
	mill -i markorv.runMain markorv.MarkoRvCore
	verilator --cc generated/MarkoRvCore.sv --exe tests/emulator/src/stimulus.cpp --build \
		-CFLAGS  "-g -I$(CAPSTONE_DIR)/include -std=c++23" \
		-LDFLAGS "-L$(CAPSTONE_DIR) -lcapstone"

gen-tests: $(BINS)

gen-rom:
	$(MAKE) -C tests/emulator/assets

# Rules
%.o: %.S
	$(GCC) $(CFLAGS) -c -o $@ $<

%.elf: %.o
	$(GCC) $(CFLAGS) $(LDFLAGS) -o $@ $<

%.bin: %.elf
	$(OBJCOPY) -O binary $< $@

clean:
	rm -f $(OBJS) $(ELFS) $(BINS)
	rm -rf obj_dir
	rm -rf generated
