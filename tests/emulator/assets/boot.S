.text
.globl	_start

/* . = 0xffff0000; */
_start:
li a2, 0x80000000
csrr a0, mhartid
li a1, 0x8ff00000
jalr ra, 0(a2)