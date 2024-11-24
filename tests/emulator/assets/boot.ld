OUTPUT_ARCH("riscv")
OUTPUT_FORMAT("elf64-littleriscv")

ENTRY(_start)
SECTIONS
{
    /* ROM address */
    .text 0x10000000 : {
        *(.text)
    }
}
