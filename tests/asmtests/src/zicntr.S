    .section .text
    .global _start
_start:
    rdcycle a0
    rdtime a0
    rdinstret a0
    j _start