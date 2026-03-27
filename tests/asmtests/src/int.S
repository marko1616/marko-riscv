    .section .text
    .global _start
_start:
    j main

print:
    li t1, 0x10000000
output_loop:
    lb t0, 0(a0)
    beqz t0, end_output
    sb t0, 0(t1)
    addi a0, a0, 1
    j output_loop
end_output:
    ret

# print 64-bit a1 as 16-char hex
print_hex:
    li t1, 0x10000000
    li t2, 60
    la t3, hex_table
hex_loop:
    srl t0, a1, t2
    andi t0, t0, 0xF
    add t0, t3, t0
    lb t0, 0(t0)
    sb t0, 0(t1)
    addi t2, t2, -4
    bgez t2, hex_loop
    ret

# print "label: 0xVALUE\r\n"
print_reg:
    addi sp, sp, -16
    sd ra, 0(sp)
    sd a1, 8(sp)
    call print
    li t1, 0x10000000
    li t0, '0'
    sb t0, 0(t1)
    li t0, 'x'
    sb t0, 0(t1)
    ld a1, 8(sp)
    call print_hex
    li t1, 0x10000000
    li t0, '\r'
    sb t0, 0(t1)
    li t0, '\n'
    sb t0, 0(t1)
    ld ra, 0(sp)
    addi sp, sp, 16
    ret

trap_handler:
    addi sp, sp, -64
    sd ra,  0(sp)
    sd a0,  8(sp)
    sd a1, 16(sp)
    sd t0, 24(sp)
    sd t1, 32(sp)
    sd t2, 40(sp)
    sd t3, 48(sp)

    la a0, stringMtip
    call print

    la a0, str_mcause
    csrr a1, mcause
    call print_reg

    la a0, str_mepc
    csrr a1, mepc
    call print_reg

    la a0, str_mstatus
    csrr a1, mstatus
    call print_reg

    la a0, str_mip
    csrr a1, mip
    call print_reg

    la a0, str_mie
    csrr a1, mie
    call print_reg

    la a0, str_mtval
    csrr a1, mtval
    call print_reg

    la a0, str_separator
    call print

    # reset mtimecmp
    li t0, 0x02004000
    ld t1, 0(t0)
    li t2, 1000000
    add t1, t1, t2
    sd t1, 0(t0)

    ld ra,  0(sp)
    ld a0,  8(sp)
    ld a1, 16(sp)
    ld t0, 24(sp)
    ld t1, 32(sp)
    ld t2, 40(sp)
    ld t3, 48(sp)
    addi sp, sp, 64
    mret

main:
    la sp, stack_top

    la t0, trap_handler
    csrw mtvec, t0

    csrr t0, time
    li t1, 1000000
    li t2, 0x02004000
    add t3, t0 ,t1
    sd t3, 0(t2)

    li t0, (1 << 7)
    csrs mie, t0

    li t0, 0x8
    csrs mstatus, t0

    la a0, stringBoot
    call print
loop:
    j loop

.section .data

hex_table:
    .ascii "0123456789abcdef"
stringBoot:
    .asciz "Booting success, waiting for mtip.\r\n"
stringMtip:
    .asciz ">>> Got a mtip!\r\n"
str_mcause:
    .asciz "  mcause  : "
str_mepc:
    .asciz "  mepc    : "
str_mstatus:
    .asciz "  mstatus : "
str_mip:
    .asciz "  mip     : "
str_mie:
    .asciz "  mie     : "
str_mtval:
    .asciz "  mtval   : "
str_separator:
    .asciz "--------------------------------\r\n"

    .space 2048
stack_top:
