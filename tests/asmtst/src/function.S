.section .text
.global _start
_start:
    la      sp, stack
    call    main

fibonacci:
    addi    sp,sp,-32
    sd      ra,32(sp)
    sd      s1,24(sp)
    sd      s2,16(sp)
    mv      s1,a0

    li      s2,1
    bgtu    s1,s2,.L1
    mv      a0,s1
    j       .L2

.L1:
    addi    a0,s1,-1
    call    fibonacci
    mv      s2,a0
    addi    a0,s1,-2
    call    fibonacci
    add     a0,s2,a0

.L2:
    ld      s2,16(sp)
    ld      s1,24(sp)
    ld      ra,32(sp)
    addi    sp,sp,32
    jr      ra

main:
    li      a0,4
    call    fibonacci
    la      t0,out
    sd      a0,0(t0)
end:
    j       end

.section .data
    .align  12
    .space  4096
stack:
    .align 12
    .space 8
out: