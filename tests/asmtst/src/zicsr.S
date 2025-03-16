.section .text
.global _start
_start:
    li t0, 0x55
    csrrw a0, mscratch, t0
    li t0, 0x0F
    csrrs a0, mscratch, t0
    li t0, 0x0F
    csrrc a0, mscratch, t0

    csrrsi a0, mscratch, 0x3
    csrrci a0, mscratch, 0x2
    csrrwi a0, mscratch, 0x1f

    csrr a0, mscratch
end:
    j end
