GCC_TEST     = riscv64-unknown-elf-gcc
OBJDUMP_TEST = riscv64-unknown-elf-objdump
NPROC = $(shell nproc)

ASM_TEST_DIR   = tests/asmtests/src
CAPSTONE_DIR   = $(shell realpath libs/capstone)
CXXOPTS_DIR    = $(shell realpath libs/cxxopts)
BOOSTPFR_DIR   = $(shell realpath libs/pfr)
GENERATED_DIR  = $(shell realpath generated)
VERIFICATION_DIR = $(shell realpath generated/verification)

ASM_SRCS = $(shell find $(ASM_TEST_DIR) -name '*.S')
OBJS     = $(ASM_SRCS:.S=.o)
ELFS     = $(OBJS:.o=.elf)

TEST_LD_SCRIPT = tests/asmtests/general.ld

CFLAGS_TEST  = -march=rv64g -mabi=lp64 -static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles
LDFLAGS_TEST = -T $(TEST_LD_SCRIPT)

DEBUG_SANITIZE_FLAGS = -fsanitize=address,undefined,leak
ifeq ($(SANITIZE),1)
    CXX_SANITIZE_FLAGS = $(DEBUG_SANITIZE_FLAGS)
    LD_SANITIZE_FLAGS  = $(DEBUG_SANITIZE_FLAGS)
else
    CXX_SANITIZE_FLAGS =
    LD_SANITIZE_FLAGS  =
endif

.PHONY: init build-simulator build-test-elves build-sim-rom clean-all

init:
	git submodule update --init --recursive
	$(MAKE) -C "$(CAPSTONE_DIR)" -j $(NPROC)

build-simulator:
	python3 scripts/gen_config.py
	mill -i markorv.runMain markorv.Main
	verilator --cc -j $(NPROC) generated/MarkoRvCore.sv -I"$(GENERATED_DIR)" -I"$(VERIFICATION_DIR)" --exe \
		$(wildcard emulator/src/*.cpp) \
		$(wildcard emulator/src/dpi/*.cpp) \
		$(wildcard emulator/src/slaves/*.cpp) \
		--build \
		-CFLAGS  "-g $(CXX_SANITIZE_FLAGS) -I$(CAPSTONE_DIR)/include -I$(CXXOPTS_DIR)/include -I$(BOOSTPFR_DIR)/include -Iinclude -std=c++23" \
		-LDFLAGS "$(LD_SANITIZE_FLAGS) -L$(CAPSTONE_DIR) -lcapstone" \
		--MAKEFLAGS "CXX=clang++ CXXFLAGS=-O3 OPT=-O3"

build-test-elves: $(ELFS)

build-sim-rom:
	$(MAKE) -C emulator/assets

%.elf: %.S
	$(GCC_TEST) $(CFLAGS_TEST) $(LDFLAGS_TEST) $< -o $@

clean-all:
	rm -f $(OBJS) $(ELFS)
	rm -rf out obj_dir generated
