    .section .text
    .global _start
_start:
    la sp,stack_bottom
loop:
    csrr a0,time
    la a1,time_buffer
    li a2,24
    call timestamp_to_iso8601
    la a0, message_prefix
    call print
    la a0, time_buffer
    call print
    la a0, message_suffix
    call print 
    j loop

print:
    li t1, 0x20000000
output_loop:
    li t0,0
    lb t0, 0(a0)
    beqz t0, end_output
    sb t0, 0(t1)
    addi a0, a0, 1
    j output_loop
end_output:
    ret

    .section .data
time_buffer:
    .space 24
stack_top:
    .space 16384
stack_bottom:
message_prefix:
    .asciz "Hello from marko-riscv at time: \0"
message_suffix:
    .asciz "\n\0"