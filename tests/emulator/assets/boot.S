.text
.globl  _start

_start:
    li a2, 0x80000000
    csrr a0, mhartid
    jalr ra, 0(a2)
