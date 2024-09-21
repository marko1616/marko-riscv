j main
fibonacci:
        addi    sp,sp,-48
        sd      ra,40(sp)
        sd      s0,32(sp)
        sd      s1,24(sp)
        addi    s0,sp,48
        mv      a5,a0
        sw      a5,-36(s0)
        lw      a5,-36(s0)
        sext.w  a5,a5
        bne     a5,zero,.L2
        li      a5,0
        j       .L3
.L2:
        lw      a5,-36(s0)
        sext.w  a4,a5
        li      a5,1
        bne     a4,a5,.L4
        li      a5,1
        j       .L3
.L4:
        lw      a5,-36(s0)
        addiw   a5,a5,-1
        sext.w  a5,a5
        mv      a0,a5
        jal     fibonacci
        mv      a5,a0
        mv      s1,a5
        lw      a5,-36(s0)
        addiw   a5,a5,-2
        sext.w  a5,a5
        mv      a0,a5
        jal     fibonacci
        mv      a5,a0
        addw    a5,s1,a5
        sext.w  a5,a5
.L3:
        mv      a0,a5
        ld      ra,40(sp)
        ld      s0,32(sp)
        ld      s1,24(sp)
        addi    sp,sp,48
        jr      ra

main:
        li    sp,2047
        li    a0, 2
        jal   fibonacci
        sd    a0,1016(x0)
