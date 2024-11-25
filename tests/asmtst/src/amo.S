    .section .text
    .global _start
_start:
    la t0, amo_data
    li t1, 0xabcd
    sw t1, 0(t0)
    
    li t1, 0x1234
    /* a0 shall be 0x0000->0xabcd->0x1234 */
    amoswap.w a0, t1, 0(t0)
    lw a0, 0(t0)
end:
    j end

    .section .data
amo_data:
    .space 8
