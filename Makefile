# Tools
GCC = riscv64-unknown-elf-gcc
OBJDUMP = riscv64-unknown-elf-objdump

# Directories and Files
ASM_TEST_DIR = tests/asmtests/src
NPROC = $(shell nproc)
CAPSTONE_DIR = $(shell realpath libs/capstone)
CXXOPTS_DIR = $(shell realpath libs/cxxopts)
ASM_SRCS     = $(shell find $(ASM_TEST_DIR) -name '*.S')
OBJS         = $(ASM_SRCS:.S=.o)
ELFS         = $(OBJS:.o=.elf)

LD_SCRIPT = tests/asmtests/general.ld
CFLAGS = -march=rv64g -mabi=lp64 -static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles 
LDFLAGS = -T $(LD_SCRIPT)

# Targets
.PHONY: init compile gen-tests gen-rom clean

init:
	git submodule update --init --recursive
	$(MAKE) -C $(CAPSTONE_DIR) -j $(NPROC)

compile:
	mill -i markorv.runMain markorv.MarkoRvCore
	verilator -j $(NPROC) --cc generated/MarkoRvCore.sv --exe \
		$(wildcard emulator/src/*.cpp) \
		$(wildcard emulator/src/slaves/*.cpp) \
		--build \
		-CFLAGS  "-g -I$(CAPSTONE_DIR)/include -I$(CXXOPTS_DIR)/include -Iinclude -std=c++23" \
		-LDFLAGS "-L$(CAPSTONE_DIR) -lcapstone"

gen-tests: $(ELFS)

gen-rom:
	$(MAKE) -C emulator/assets

%.elf: %.S
	$(GCC) $(CFLAGS) $(LDFLAGS) $< -o $@

clean:
	rm -f $(OBJS) $(ELFS)
	rm -rf out
	rm -rf obj_dir
	rm -rf generated
