.text
.globl  _start

_start:
    /* RAM base addr */
    li a2, 0x80000000
    csrr a0, mhartid
    /* device tree */
    li a1, 0x10000400
    jalr ra, 0(a2)