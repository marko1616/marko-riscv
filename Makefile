AS = riscv64-linux-gnu-as
OBJCOPY = riscv64-linux-gnu-objcopy
XXD = xxd
FOLD = fold
AWK = awk

TEST_DIR = tests
ASM_SRCS = $(shell find $(TEST_DIR) -name '*.S')
OBJS = $(ASM_SRCS:.S=.o)
BINS = $(OBJS:.o=.bin)
HEXES = $(BINS:.bin=.hex)

gen-tests: $(HEXES)
%.o: %.S
	$(AS) -o $@ $<
%.bin: %.o
	$(OBJCOPY) -O binary $< $@
%.hex: %.bin
	$(XXD) -p $< | $(FOLD) -w 8 | $(AWK) '{print substr($$0, 7, 2) substr($$0, 5, 2) substr($$0, 3, 2) substr($$0, 1, 2)}' > $@

clean:
	rm -f $(OBJS) $(BINS) $(HEXES)
