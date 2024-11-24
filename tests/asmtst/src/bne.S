addi x2, x0, 16
loop:
sb a0, 1016(x0)
addi a0, a0, 1
addi x0, x0, 0
addi x0, x0, 1
addi x0, x0, 2
addi x0, x0, 3
addi x0, x0, 4
addi x0, x0, 5
addi x0, x0, 6
addi x0, x0, 7
bne a0, x2, loop
sb x0, 1016(x0)