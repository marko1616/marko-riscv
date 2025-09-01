    .section .text
    .global _start
_start:
    li t0, 0x02000000
    li t1, 0xff
    sb t1, 0(t0)
    auipc t0, 0
    jalr t0