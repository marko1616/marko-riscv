AS = riscv64-unknown-elf-as
OBJCOPY = riscv64-unknown-elf-objcopy
XXD = xxd
FOLD = fold
AWK = awk
TR = tr

TEST_DIR = tests
ASM_SRCS = $(shell find $(TEST_DIR) -name '*.S')
OBJS = $(ASM_SRCS:.S=.o)
BINS = $(OBJS:.o=.bin)
HEXES = $(BINS:.bin=.hex)

init:
	wget https://github.com/com-lihaoyi/mill/releases/download/0.12.1/0.12.1 -O ./mill
	chmod +x ./mill

compile:
	./mill -i markorv.runMain markorv.MarkoRvCore

emu:
	./mill markorv.test

gen-tests: $(HEXES)

%.o: %.S
	$(AS) -o $@ $<

%.bin: %.o
	$(OBJCOPY) -O binary $< $@

%.hex: %.bin
	$(XXD) -p $< | $(TR) -d "\n" | $(FOLD) -w 8 | $(AWK) '{print substr($$0, 7, 2) substr($$0, 5, 2) substr($$0, 3, 2) substr($$0, 1, 2)}' > $@

clean:
	rm -f $(OBJS) $(BINS) $(HEXES)
