    .section .text
    .global _start
_start:
    j main
trap_handler:
    csrr a0, mcause
    mret

main:
    auipc t0, 0
    la t0, trap_handler
    csrw mtvec, t0
    li t0, 0x8
    csrs mstatus, t0

    addi a2, x0, 0x40
    la t0, data
    addi t0, t0, 1016

    ecall
    ebreak

loop:
    addi a1, a1, 1
    nop
    nop
    bne a1, a2, loop

.section .data
data:
    .space 2048
