j main

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
        jal     fibonacci
        mv      a5,a0
        addi    a0,s1,-2
        jal     fibonacci
        add     a0,a5,a0

.L2:
        ld      a5,16(sp)
        ld      s1,24(sp)
        ld      ra,32(sp)
        addi    sp,sp,40
        jr      ra

main:
        li      sp,2048
        li      a0,4
        jal     fibonacci
        sd      a0,1016(x0)