OUTPUT_ARCH("riscv")
OUTPUT_FORMAT("elf64-littleriscv")

ENTRY(_start)
SECTIONS
{
    /* ROM address */
    .text 0x10000000 : {
        *(.text)
    }

    .data 0x10000400 : {
        *(.data)
        dtb_start = .;
        KEEP(*(.dtb))
        dtb_end = .;
    }
}
