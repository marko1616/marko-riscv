    .section .text
    .global _start
_start:
    j main

trap_handler:
    csrrw t0, mepc, zero
    addi t0, t0, 4
    csrrw zero, mepc, t0
    mret

main:
    la t0, trap_handler
    csrw mtvec, t0

    li t0, 0x02004000
    li t1, 0x4000
    sd t1, 0(t0)

    li t0, (1 << 7)
    csrs mie, t0

    li t0, 0x8
    csrs mstatus, t0

    .byte 0x00, 0x00, 0x00, 0x00

    .byte 0x10, 0x00, 0x00, 0x33
loop:
    j loop