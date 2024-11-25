.section .text
.global _start
_start:
    j main
trap_handler:
    li a0, 0x1
    mret

main:
    auipc t0, 0
    addi t0, t0, -0x8
    csrw mtvec, t0
    li t0, 0x8
    csrs mstatus, t0

    addi a2, x0, 0x40
    la t0, data
    addi t0, t0, 1016

loop:
    addi a1, a1, 1
    bne a1, a2, loop
end:
    j end

.section .data
data:
    .space 2048
