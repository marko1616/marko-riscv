OUTPUT_ARCH("riscv")
OUTPUT_FORMAT("elf64-littleriscv")

ENTRY(_start)
SECTIONS
{
    /* ROM address */
    .text 0x01000000 : {
        *(.text)
    }

    .data 0x01000400 : {
        *(.data)
    }
}
