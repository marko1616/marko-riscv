    .section .text
.global _start
_start:
    la a0, string
output_loop:
    lb a1, 0(a0)
    beqz a1, end
    li a2, 0x20000000
    sb a1, 0(a2)
    addi a0, a0, 1
    j output_loop

end:
    j end

    .section .data
string:
    .asciz "Hello, World!\n"
