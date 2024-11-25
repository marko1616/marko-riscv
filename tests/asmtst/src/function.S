    .section .text
    .global _start
_start:
    call main

fibonacci:
    addi    sp,sp,-40
    sd      ra,32(sp)
    sd      s1,24(sp)
    sd      a5,16(sp)
    mv      s1,a0

    li      a5,1
    bgtu    s1,a5,.L1
    mv      a0,s1
    j       .L2

.L1:
    addi    a0,s1,-1
    call    fibonacci
    mv      a5,a0
    addi    a0,s1,-2
    call    fibonacci
    add     a0,a5,a0

.L2:
    ld      a5,16(sp)
    ld      s1,24(sp)
    ld      ra,32(sp)
    addi    sp,sp,40
    jr      ra

main:
    la      sp, stack
    addi    sp, sp, 1024
    li      a0,4
    call    fibonacci
end:
    j end

    .section .data
stack:
    .space 2048
