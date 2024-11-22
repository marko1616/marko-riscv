j main
trap_handler:
li a0, 0x1
mret

main:
auipc t0, 0
addi t0, t0, -0x8
csrw mtvec, t0
li t0, 0x3
csrs mstatus, t0

addi a2, x0, 16
loop:
sb a1, 1016(x0)
addi a1, a1, 1
bne a1, a2, loop
sb x0, 1016(x0)