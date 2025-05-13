OUTPUT_ARCH("riscv")
OUTPUT_FORMAT("elf64-littleriscv")

ENTRY(_start)
SECTIONS
{
    /* ROM address */
    .text 0x00001000 : {
        *(.text)
    }

    .data 0x00001400 : {
        *(.data)
    }
}
