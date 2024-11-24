OUTPUT_ARCH("riscv")
OUTPUT_FORMAT("elf64-littleriscv")

ENTRY(_start)
SECTIONS
{
    /* RAM base address */
    .text 0x80000000 : {
        *(.text)
    }

    .data 0x80001000 : {
        *(.data)
    }
}
