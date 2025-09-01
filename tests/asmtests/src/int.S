    .section .text
    .global _start
_start:
    j main

print:
    li t1, 0x10000000
output_loop:
    li t0,0
    lb t0, 0(a0)
    beqz t0, end_output
    sb t0, 0(t1)
    addi a0, a0, 1
    j output_loop
end_output:
    ret

trap_handler:
    la a0, stringMtip
    call print

    li t0, 0x02004000
    ld t1, 0(t0)
    li t2, 0x4000
    add t1, t1, t2
    sd t1, 0(t0)

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

    la a0, stringBoot
    call print
loop:
    j loop

.section .data
data:
    .space 2048
stringBoot:
    .asciz "Booting success waiting for mtip.\r\n"
stringMtip:
    .asciz "Got a mtip.\r\n"